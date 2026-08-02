package com.nmtuong.telegramdrive.data

import com.nmtuong.telegramdrive.domain.ActionResult
import com.nmtuong.telegramdrive.domain.MediaKind
import com.nmtuong.telegramdrive.domain.TransferIdentity
import com.nmtuong.telegramdrive.domain.TransferRequest
import com.nmtuong.telegramdrive.domain.TransferSnapshot
import com.nmtuong.telegramdrive.domain.TransferState
import java.io.Closeable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore

/**
 * Coordinates file transfers with a single source of truth.
 *
 * Checkpoint 1 Architecture: Session-scoped collector
 * - Session collector observes [repository.transferUpdates] continuously.
 * - Single source of truth is [_snapshots] map keyed by [TransferIdentity].
 * - Prevents event loss on fast/cached downloads.
 *
 * Checkpoint 2:
 * - Consumes [TransferRequest] containing accountId, databaseGeneration, fileId, messageId, etc.
 *
 * Checkpoint 3:
 * - Terminal snapshots (especially Completed) remain retained for session duration (no 5s deletion).
 *
 * Checkpoint 5:
 * - Atomic LAZY job launch prevents registration race conditions.
 */
class TransferCoordinator(
    private val repository: TelegramRepository,
    private val accountId: Long,
    private val databaseGeneration: Long,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val maxConcurrent: Int = 3,
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

    @Volatile
    private var closed = false

    init {
        // CP1+CP2: Collector started UNDISPATCHED so subscription is established immediately
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            repository.transferUpdates.collect { update ->
                if (closed) return@collect
                if (!isCurrentGeneration()) return@collect
                if (isValidIdentity(update.identity)) {
                    val currentAttempt = synchronized(lock) { attemptMap[update.identity.fileId] ?: 0L }
                    if (update.attemptId != 0L && update.attemptId != currentAttempt) return@collect
                    val currentSnapshot = _snapshots.value[update.identity]
                    if (currentSnapshot?.attemptId == currentAttempt && currentSnapshot.isTerminal) return@collect
                    val newSnapshot = TransferSnapshot(
                        identity = update.identity,
                        state = update.state,
                        progress = update.percent,
                        localPath = update.localPath ?: (update.state as? TransferState.Completed)?.localPath,
                        safeError = update.safeError ?: (update.state as? TransferState.TransferFailed)?.reason,
                        attemptId = currentAttempt,
                    )
                    updateSnapshot(newSnapshot)
                }
            }
        }
    }

    fun getSnapshot(fileId: Int): TransferSnapshot? {
        val identity = TransferIdentity(accountId, databaseGeneration, fileId)
        return _snapshots.value[identity]
    }

    fun getSnapshot(identity: TransferIdentity): TransferSnapshot? {
        return _snapshots.value[identity]
    }

    /** Overload for legacy/adapter compatibility. */
    fun startTransfer(fileId: Int, identity: TransferIdentity): Boolean {
        val request = TransferRequest(
            identity = identity,
            messageId = fileId.toLong(),
            sourceId = 0L,
            fileId = fileId,
            mediaKind = MediaKind.DOCUMENT,
        )
        return startTransfer(request)
    }

    /**
     * Start a transfer with a full [TransferRequest].
     *
     * CP5 Atomic: Job created with CoroutineStart.LAZY, registered in activeJobs inside lock, then started.
     */
    fun startTransfer(request: TransferRequest): Boolean {
        val identity = request.identity
        val fileId = request.fileId
        if (closed || !isValidIdentity(identity) || !isCurrentGeneration()) {
            return false // Closed, stale identity, or invalidated generation
        }

        val existingPath = request.knownLocalPath?.takeIf { isValidLocalFile(it, request.expectedSizeBytes) }
        if (existingPath != null) {
            synchronized(lock) {
                val attemptId = attemptMap[fileId] ?: 0L
                updateSnapshot(
                    TransferSnapshot(
                        identity = identity,
                        state = TransferState.Completed(existingPath),
                        progress = 100,
                        localPath = existingPath,
                        attemptId = attemptId,
                    )
                )
            }
            return true
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

            val job = scope.launch(start = CoroutineStart.LAZY) {
                semaphore.acquire()
                try {
                    if (closed || !isCurrentGeneration()) {
                        updateSnapshot(initialSnapshot.copy(state = TransferState.TransferCancelled))
                        return@launch
                    }
                    updateSnapshot(initialSnapshot.copy(state = TransferState.InProgress(0), progress = 0))

                    val result = repository.download(request)
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

                    // Await until session collector updates _snapshots to a terminal state for this request identity
                    _snapshots.first { snapMap ->
                        val snap = snapMap[identity]
                        snap != null && snap.isTerminal && snap.attemptId == currentAttempt
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
                }
            }
            activeJobs[fileId] = job
            job.start()
        }
        return true
    }

    fun cancelTransfer(fileId: Int) {
        val identity = TransferIdentity(accountId, databaseGeneration, fileId)
        cancelTransfer(identity)
    }

    fun cancelTransfer(identity: TransferIdentity) {
        val fileId = identity.fileId
        var jobToCancel: Job? = null
        var shouldCancelRepo = false

        synchronized(lock) {
            if (closed) return
            val snapshot = _snapshots.value[identity]
            if (snapshot == null || snapshot.isTerminal) return

            jobToCancel = activeJobs.remove(fileId)
            shouldCancelRepo = snapshot.state !is TransferState.Queued
            updateSnapshot(snapshot.copy(state = TransferState.TransferCancelled))
        }

        jobToCancel?.cancel()
        if (shouldCancelRepo) {
            repository.cancel(identity)
        }
    }

    fun onProgressUpdate(identity: TransferIdentity, percent: Int) {
        if (closed || !isValidIdentity(identity) || !isCurrentGeneration()) return
        val current = _snapshots.value[identity] ?: TransferSnapshot(identity, TransferState.InProgress(percent), percent)
        updateSnapshot(current.copy(state = TransferState.InProgress(percent), progress = percent))
    }

    fun clear() {
        val jobsToCancel: List<Job>
        val identitiesToCancel: List<TransferIdentity>

        synchronized(lock) {
            jobsToCancel = activeJobs.values.toList()
            activeJobs.clear()

            identitiesToCancel = _snapshots.value
                .filter { (_, snap) -> !snap.isTerminal }
                .keys
                .toList()

            _snapshots.value = emptyMap()
            _transferStates.value = emptyMap()
            attemptMap.clear()
        }

        jobsToCancel.forEach { it.cancel() }
        identitiesToCancel.forEach { identity ->
            runCatching { repository.cancel(identity) }
        }
    }

    override fun close() {
        closed = true
        clear()
        scope.cancel()
    }

    private fun updateSnapshot(snapshot: TransferSnapshot) {
        if (closed) return
        _snapshots.update { it + (snapshot.identity to snapshot) }
        _transferStates.update { it + (snapshot.identity.fileId to snapshot.state) }
    }

    private fun isCurrentGeneration(): Boolean =
        activeGenerationProvider() == databaseGeneration

    private fun isValidIdentity(identity: TransferIdentity): Boolean =
        identity.accountId == accountId && identity.databaseGeneration == databaseGeneration

    private fun isValidLocalFile(path: String, expectedSizeBytes: Long): Boolean {
        val file = java.io.File(path)
        return file.isFile && (expectedSizeBytes <= 0L || file.length() >= expectedSizeBytes)
    }
}
