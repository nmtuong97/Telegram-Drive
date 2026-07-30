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
import java.util.concurrent.atomic.AtomicReference

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
 * - Repository-level DownloadState (in LibraryState) is updated from here, not vice versa.
 *
 * Concurrency:
 * - Atomic start: state registered before job launch (no containsKey → launch → putIfAbsent race).
 * - Semaphore limits concurrent TDLib downloads.
 * - Duplicate start is idempotent.
 * - Cancel sends actual TDLib cancel via repository.
 * - clear() cancels all active transfers via repository.
 *
 * Terminal state retention:
 * - Terminal states (Completed, Failed, Cancelled, Unavailable) are retained for [TERMINAL_RETENTION_MS]
 *   so observers can see the final state before cleanup.
 */
class TransferCoordinator(
    private val repository: TelegramRepository,
    private val accountId: Long,
    private val databaseGeneration: Long,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val maxConcurrent: Int = 3,
    private val terminalRetentionMs: Long = TERMINAL_RETENTION_MS,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val semaphore = Semaphore(maxConcurrent)

    // Single source of truth: fileId → TransferState
    private val _transferStates = MutableStateFlow<Map<Int, TransferState>>(emptyMap())
    val transferStates: StateFlow<Map<Int, TransferState>> = _transferStates.asStateFlow()

    // Job map: fileId → active Job (only for non-terminal states)
    private val activeJobs = mutableMapOf<Int, Job>()
    private val lock = Any()

    /**
     * Start a transfer for [fileId].
     *
     * Atomic: state is registered (Queued) before the job is launched.
     * If [fileId] already has an active transfer, returns immediately (idempotent).
     * Generation check: if coordinator's generation doesn't match, transfer is rejected.
     *
     * @param fileId TDLib file ID
     * @param identity Full transfer identity (must match this coordinator's accountId/generation)
     */
    fun startTransfer(fileId: Int, identity: TransferIdentity): Boolean {
        if (identity.accountId != accountId || identity.databaseGeneration != databaseGeneration) {
            return false // Stale identity
        }

        synchronized(lock) {
            val current = _transferStates.value[fileId]
            if (current != null && !current.isTerminal) {
                return true // Already active or queued
            }
            // Atomically register Queued state before launching
            _transferStates.update { it + (fileId to TransferState.Queued) }

            val job = scope.launch {
                semaphore.acquire()
                try {
                    updateState(fileId, TransferState.InProgress(0))
                    val result = repository.download(fileId)
                    if (result != ActionResult.ACCEPTED) {
                        updateState(fileId, TransferState.TransferFailed("Download rejected: $result"))
                        return@launch
                    }
                    // Observe library state for progress/completion
                    repository.library.collect { libraryState ->
                        if (!isCurrentGeneration()) {
                            updateState(fileId, TransferState.TransferCancelled)
                            throw CancellationException("Generation invalidated")
                        }
                        if (libraryState is LibraryState.Content) {
                            val item = libraryState.items.firstOrNull { it.fileId == fileId }
                            if (item != null) {
                                val transferState = item.downloadState.toTransferState()
                                updateState(fileId, transferState)
                                if (transferState.isTerminal) {
                                    throw CancellationException("Terminal state reached: $transferState")
                                }
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    val current = _transferStates.value[fileId]
                    if (current == null || !current.isTerminal) {
                        updateState(fileId, TransferState.TransferCancelled)
                    }
                    throw e
                } catch (e: Exception) {
                    updateState(fileId, TransferState.TransferFailed(e.message ?: "Transfer failed"))
                } finally {
                    semaphore.release()
                    synchronized(lock) { activeJobs.remove(fileId) }
                    // Schedule terminal state retention cleanup
                    scope.launch {
                        kotlinx.coroutines.delay(terminalRetentionMs)
                        synchronized(lock) {
                            val state = _transferStates.value[fileId]
                            if (state != null && state.isTerminal) {
                                _transferStates.update { it - fileId }
                            }
                        }
                    }
                }
            }
            activeJobs[fileId] = job
        }
        return true
    }

    /**
     * Cancel a transfer.
     *
     * - If queued: cancels the job before semaphore is acquired.
     * - If active: sends actual TDLib cancel via repository, then cancels monitoring job.
     * - If terminal: no-op.
     */
    fun cancelTransfer(fileId: Int) {
        synchronized(lock) {
            val state = _transferStates.value[fileId]
            if (state == null || state.isTerminal) return

            val job = activeJobs.remove(fileId)
            job?.cancel()

            if (state !is TransferState.Queued) {
                // Active download — send actual TDLib cancel
                repository.cancelDownload(fileId)
            }
            updateState(fileId, TransferState.TransferCancelled)
        }
    }

    /**
     * Observe progress updates for a specific file.
     * Called from the repository/gateway layer when TDLib sends progress.
     * Generation-aware: updates from stale generations are silently ignored.
     */
    fun onProgressUpdate(identity: TransferIdentity, percent: Int) {
        if (!isValidIdentity(identity)) return
        updateState(identity.fileId, TransferState.InProgress(percent))
    }

    /**
     * Reset all transfers — called on account reset or database generation change.
     * Cancels all active TDLib downloads and clears all state.
     */
    fun clear() {
        synchronized(lock) {
            // Cancel all active jobs
            activeJobs.values.forEach { it.cancel() }
            activeJobs.clear()

            // Cancel all active TDLib downloads
            val activeFileIds = _transferStates.value
                .filter { (_, state) -> !state.isTerminal }
                .keys
            activeFileIds.forEach { fileId ->
                runCatching { repository.cancelDownload(fileId) }
            }

            _transferStates.value = emptyMap()
        }
    }

    fun close() {
        clear()
        scope.cancel()
    }

    private fun updateState(fileId: Int, state: TransferState) {
        _transferStates.update { it + (fileId to state) }
    }

    private fun isCurrentGeneration(): Boolean = true // Generation check in identity validation

    private fun isValidIdentity(identity: TransferIdentity): Boolean =
        identity.accountId == accountId && identity.databaseGeneration == databaseGeneration

    companion object {
        /**
         * Duration terminal states are retained before being removed from the map.
         * Allows observers to see the final state before cleanup.
         */
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
