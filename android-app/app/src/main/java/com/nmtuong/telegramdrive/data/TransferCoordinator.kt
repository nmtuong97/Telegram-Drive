package com.nmtuong.telegramdrive.data

import com.nmtuong.telegramdrive.domain.ActionResult
import com.nmtuong.telegramdrive.domain.DownloadState
import com.nmtuong.telegramdrive.domain.LibraryState
import com.nmtuong.telegramdrive.domain.MediaItem
import com.nmtuong.telegramdrive.domain.TransferIdentity
import com.nmtuong.telegramdrive.domain.TransferState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore

/**
 * Coordinates file transfers with a single source of truth.
 *
 * Identity:
 * - Each transfer is identified by [TransferIdentity](accountId, databaseGeneration, fileId).
 * - Raw TDLib file ID alone is not a global identity.
 * - Late updates from a previous database generation are silently ignored.
 *
 * Single source of truth:
 * - [transferStates] is the one and only transfer state map.
 * - UI/ViewModel observe this; do not maintain a separate map.
 *
 * Concurrency:
 * - Atomic start: state registered before job launch.
 * - Semaphore limits concurrent TDLib downloads.
 * - Duplicate start is idempotent.
 * - Cancel sends actual TDLib cancel via repository.
 * - clear() cancels all active transfers via repository.
 *
 * Terminal state retention:
 * - Terminal states (Completed, Failed, Cancelled, Unavailable) are retained for [TERMINAL_RETENTION_MS]
 *   bound to the specific transfer attempt token.
 */
class TransferCoordinator(
    private val repository: TelegramRepository,
    private val accountId: Long,
    private val databaseGeneration: Long,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val maxConcurrent: Int = 3,
    private val terminalRetentionMs: Long = TERMINAL_RETENTION_MS,
    private val activeGenerationProvider: () -> Long = { databaseGeneration },
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val semaphore = Semaphore(maxConcurrent)

    // Single source of truth: fileId → TransferState
    private val _transferStates = MutableStateFlow<Map<Int, TransferState>>(emptyMap())
    val transferStates: StateFlow<Map<Int, TransferState>> = _transferStates.asStateFlow()

    // Job map: fileId → active Job
    private val activeJobs = mutableMapOf<Int, Job>()
    // Attempt tracking: fileId → attemptId (prevents old retention timers from clearing retry state)
    private val attemptMap = mutableMapOf<Int, Long>()
    private val lock = Any()

    /**
     * Start a transfer for [fileId].
     *
     * Atomic: state registered (Queued) before job launched.
     * Generation check: verifies active generation matches coordinator generation.
     */
    fun startTransfer(fileId: Int, identity: TransferIdentity): Boolean {
        if (!isValidIdentity(identity) || !isCurrentGeneration()) {
            return false // Stale identity or invalidated generation
        }

        synchronized(lock) {
            val current = _transferStates.value[fileId]
            if (current != null && !current.isTerminal) {
                return true // Already active or queued
            }

            val currentAttempt = (attemptMap[fileId] ?: 0L) + 1L
            attemptMap[fileId] = currentAttempt

            _transferStates.update { it + (fileId to TransferState.Queued) }

            val job = scope.launch {
                semaphore.acquire()
                try {
                    if (!isCurrentGeneration()) {
                        updateState(fileId, TransferState.TransferCancelled)
                        return@launch
                    }
                    updateState(fileId, TransferState.InProgress(0))
                    val result = repository.downloadPagingItem(fileId)
                    if (result != ActionResult.ACCEPTED) {
                        updateState(fileId, TransferState.TransferFailed("Download rejected: $result"))
                        return@launch
                    }

                    repository.transferUpdates.collect { update ->
                        if (!isCurrentGeneration()) {
                            updateState(fileId, TransferState.TransferCancelled)
                            throw CancellationException("Generation invalidated")
                        }
                        if (update.identity.fileId == fileId && isValidIdentity(update.identity)) {
                            updateState(fileId, update.state)
                            if (update.state.isTerminal) {
                                throw CancellationException("Terminal state reached: ${update.state}")
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    val currentState = _transferStates.value[fileId]
                    if (currentState == null || !currentState.isTerminal) {
                        updateState(fileId, TransferState.TransferCancelled)
                    }
                    throw e
                } catch (e: Exception) {
                    updateState(fileId, TransferState.TransferFailed(e.message ?: "Transfer failed"))
                } finally {
                    semaphore.release()
                    synchronized(lock) { activeJobs.remove(fileId) }

                    // Gated retention cleanup: check attempt ID before removing
                    scope.launch {
                        kotlinx.coroutines.delay(terminalRetentionMs)
                        synchronized(lock) {
                            if (attemptMap[fileId] == currentAttempt) {
                                val state = _transferStates.value[fileId]
                                if (state != null && state.isTerminal) {
                                    _transferStates.update { it - fileId }
                                }
                            }
                        }
                    }
                }
            }
            activeJobs[fileId] = job
        }
        return true
    }

    fun cancelTransfer(fileId: Int) {
        synchronized(lock) {
            val state = _transferStates.value[fileId]
            if (state == null || state.isTerminal) return

            val job = activeJobs.remove(fileId)
            job?.cancel()

            if (state !is TransferState.Queued) {
                repository.cancelDownload(fileId)
            }
            updateState(fileId, TransferState.TransferCancelled)
        }
    }

    fun onProgressUpdate(identity: TransferIdentity, percent: Int) {
        if (!isValidIdentity(identity) || !isCurrentGeneration()) return
        updateState(identity.fileId, TransferState.InProgress(percent))
    }

    fun clear() {
        synchronized(lock) {
            activeJobs.values.forEach { it.cancel() }
            activeJobs.clear()

            val activeFileIds = _transferStates.value
                .filter { (_, state) -> !state.isTerminal }
                .keys
            activeFileIds.forEach { fileId ->
                runCatching { repository.cancelDownload(fileId) }
            }

            _transferStates.value = emptyMap()
            attemptMap.clear()
        }
    }

    fun close() {
        clear()
        scope.cancel()
    }

    private fun updateState(fileId: Int, state: TransferState) {
        _transferStates.update { it + (fileId to state) }
    }

    private fun isCurrentGeneration(): Boolean =
        activeGenerationProvider() == databaseGeneration

    private fun isValidIdentity(identity: TransferIdentity): Boolean =
        identity.accountId == accountId && identity.databaseGeneration == databaseGeneration

    companion object {
        const val TERMINAL_RETENTION_MS = 5_000L
    }
}

private fun DownloadState.toTransferState(): TransferState = when (this) {
    DownloadState.NotDownloaded -> TransferState.NotStarted
    is DownloadState.Downloading -> TransferState.InProgress(percent)
    DownloadState.Complete -> TransferState.Completed
    DownloadState.Canceled -> TransferState.TransferCancelled
    is DownloadState.Failed -> TransferState.TransferFailed(reason)
    DownloadState.Unavailable -> TransferState.Unavailable
}
