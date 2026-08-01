package com.nmtuong.telegramdrive.data

import com.nmtuong.telegramdrive.domain.ActionResult
import com.nmtuong.telegramdrive.domain.TransferIdentity
import com.nmtuong.telegramdrive.domain.TransferSnapshot
import com.nmtuong.telegramdrive.domain.TransferState
import java.io.Closeable
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
 * - [snapshots] is the primary transfer snapshot state map.
 * - UI/ViewModel observe [transferStates] or [snapshots].
 *
 * Concurrency:
 * - Atomic start: state registered (Queued) before job launch.
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
) : Closeable {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val semaphore = Semaphore(maxConcurrent)

    // Primary stateful store: TransferIdentity -> TransferSnapshot
    private val _snapshots = MutableStateFlow<Map<TransferIdentity, TransferSnapshot>>(emptyMap())
    val snapshots: StateFlow<Map<TransferIdentity, TransferSnapshot>> = _snapshots.asStateFlow()

    // Derived single source of truth for backward compatibility: fileId -> TransferState
    private val _transferStates = MutableStateFlow<Map<Int, TransferState>>(emptyMap())
    val transferStates: StateFlow<Map<Int, TransferState>> = _transferStates.asStateFlow()

    // Job map: fileId -> active Job
    private val activeJobs = mutableMapOf<Int, Job>()
    // Attempt tracking: fileId -> attemptId
    private val attemptMap = mutableMapOf<Int, Long>()
    private val lock = Any()

    fun getSnapshot(fileId: Int): TransferSnapshot? {
        val identity = TransferIdentity(accountId, databaseGeneration, fileId)
        return _snapshots.value[identity]
    }

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
            val currentSnapshot = _snapshots.value[identity]
            if (currentSnapshot != null && !currentSnapshot.isTerminal) {
                return true // Already active or queued
            }

            val currentAttempt = (attemptMap[fileId] ?: 0L) + 1L
            attemptMap[fileId] = currentAttempt

            val initialSnapshot = TransferSnapshot(
                identity = identity,
                state = TransferState.Queued,
                progress = 0,
                attemptId = currentAttempt,
            )
            updateSnapshot(initialSnapshot)

            val job = scope.launch {
                semaphore.acquire()
                try {
                    if (!isCurrentGeneration()) {
                        updateSnapshot(initialSnapshot.copy(state = TransferState.TransferCancelled))
                        return@launch
                    }
                    updateSnapshot(initialSnapshot.copy(state = TransferState.InProgress(0), progress = 0))

                    val result = repository.downloadPagingItem(fileId)
                    if (result != ActionResult.ACCEPTED) {
                        val failMsg = "Download rejected: $result"
                        updateSnapshot(
                            initialSnapshot.copy(
                                state = TransferState.TransferFailed(failMsg),
                                safeError = failMsg,
                            )
                        )
                        return@launch
                    }

                    repository.transferUpdates.collect { update ->
                        if (!isCurrentGeneration()) {
                            updateSnapshot(initialSnapshot.copy(state = TransferState.TransferCancelled))
                            throw CancellationException("Generation invalidated")
                        }
                        if (update.identity.fileId == fileId && isValidIdentity(update.identity)) {
                            val newSnapshot = TransferSnapshot(
                                identity = update.identity,
                                state = update.state,
                                progress = update.percent,
                                localPath = update.localPath ?: (update.state as? TransferState.Completed)?.localPath,
                                safeError = update.safeError ?: (update.state as? TransferState.TransferFailed)?.reason,
                                attemptId = currentAttempt,
                            )
                            updateSnapshot(newSnapshot)
                            if (update.state.isTerminal) {
                                throw CancellationException("Terminal state reached: ${update.state}")
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    val current = _snapshots.value[identity]
                    if (current == null || !current.isTerminal) {
                        updateSnapshot(initialSnapshot.copy(state = TransferState.TransferCancelled))
                    }
                    throw e
                } catch (e: Exception) {
                    val errMsg = e.message ?: "Transfer failed"
                    updateSnapshot(
                        initialSnapshot.copy(
                            state = TransferState.TransferFailed(errMsg),
                            safeError = errMsg,
                        )
                    )
                } finally {
                    semaphore.release()
                    synchronized(lock) { activeJobs.remove(fileId) }

                    // Gated retention cleanup: check attempt ID before removing
                    scope.launch {
                        kotlinx.coroutines.delay(terminalRetentionMs)
                        synchronized(lock) {
                            if (attemptMap[fileId] == currentAttempt) {
                                val snap = _snapshots.value[identity]
                                if (snap != null && snap.isTerminal) {
                                    _snapshots.update { it - identity }
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
        val identity = TransferIdentity(accountId, databaseGeneration, fileId)
        synchronized(lock) {
            val snapshot = _snapshots.value[identity]
            if (snapshot == null || snapshot.isTerminal) return

            val job = activeJobs.remove(fileId)
            job?.cancel()

            if (snapshot.state !is TransferState.Queued) {
                repository.cancelDownload(fileId)
            }
            updateSnapshot(snapshot.copy(state = TransferState.TransferCancelled))
        }
    }

    fun onProgressUpdate(identity: TransferIdentity, percent: Int) {
        if (!isValidIdentity(identity) || !isCurrentGeneration()) return
        val current = _snapshots.value[identity] ?: TransferSnapshot(identity, TransferState.InProgress(percent), percent)
        updateSnapshot(current.copy(state = TransferState.InProgress(percent), progress = percent))
    }

    fun clear() {
        synchronized(lock) {
            activeJobs.values.forEach { it.cancel() }
            activeJobs.clear()

            val activeFileIds = _snapshots.value
                .filter { (_, snap) -> !snap.isTerminal }
                .keys.map { it.fileId }
            activeFileIds.forEach { fileId ->
                runCatching { repository.cancelDownload(fileId) }
            }

            _snapshots.value = emptyMap()
            _transferStates.value = emptyMap()
            attemptMap.clear()
        }
    }

    override fun close() {
        clear()
        scope.cancel()
    }

    private fun updateSnapshot(snapshot: TransferSnapshot) {
        _snapshots.update { it + (snapshot.identity to snapshot) }
        _transferStates.update { it + (snapshot.identity.fileId to snapshot.state) }
    }

    private fun isCurrentGeneration(): Boolean =
        activeGenerationProvider() == databaseGeneration

    private fun isValidIdentity(identity: TransferIdentity): Boolean =
        identity.accountId == accountId && identity.databaseGeneration == databaseGeneration

    companion object {
        const val TERMINAL_RETENTION_MS = 5_000L
    }
}

