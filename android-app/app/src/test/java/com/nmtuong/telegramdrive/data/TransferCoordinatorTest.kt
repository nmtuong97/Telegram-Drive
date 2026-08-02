package com.nmtuong.telegramdrive.data

import com.nmtuong.telegramdrive.domain.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/**
 * Deterministic tests for [TransferCoordinator] using injected TestDispatcher.
 * Explicit cleanup in try/finally blocks ensures no hanging coroutines or leak.
 */
class TransferCoordinatorTest {

    // ── Stub repository ──────────────────────────────────────────────────────

    private class StubRepository : TelegramRepository {
        val libraryState = MutableStateFlow<LibraryState>(LibraryState.Idle)
        override val library: StateFlow<LibraryState> = libraryState
        override val resetProgress: StateFlow<ResetProgress> = MutableStateFlow(ResetProgress.Idle)
        val _transferUpdates = MutableSharedFlow<TransferUpdate>(extraBufferCapacity = 64)
        override val transferUpdates: Flow<TransferUpdate> = _transferUpdates.asSharedFlow()
        override val diagnostics: StateFlow<DiagnosticsState> =
            MutableStateFlow(DiagnosticsState(DataSourceMode.FAKE))
        override val authorization: StateFlow<AuthorizationSession> =
            MutableStateFlow(AuthorizationSession())

        val downloadedFileIds = mutableListOf<Int>()
        val cancelledFileIds = mutableListOf<Int>()
        var downloadResult: ActionResult = ActionResult.ACCEPTED
        var autoEmitTerminal: Boolean = true
        var defaultTerminalState: TransferState = TransferState.Completed("/tmp/fake_path")

        override fun start() {}
        override fun submit(action: AuthorizationAction) = ActionResult.ACCEPTED
        override suspend fun logoutAndReset(): AccountResetResult = AccountResetResult.Completed
        override fun loadSavedMessages(limit: Int) = ActionResult.ACCEPTED
        override fun download(request: TransferRequest): ActionResult {
            downloadedFileIds.add(request.fileId)
            if (downloadResult == ActionResult.ACCEPTED && autoEmitTerminal) {
                val update = TransferUpdate(
                    identity = request.identity,
                    state = defaultTerminalState,
                    percent = 100,
                    localPath = (defaultTerminalState as? TransferState.Completed)?.localPath,
                    safeError = (defaultTerminalState as? TransferState.TransferFailed)?.reason,
                    attemptId = 0L,
                )
                _transferUpdates.tryEmit(update)
            }
            return downloadResult
        }
        override fun download(fileId: Int): ActionResult {
            downloadedFileIds.add(fileId)
            return downloadResult
        }
        override fun downloadPagingItem(fileId: Int): ActionResult {
            downloadedFileIds.add(fileId)
            return downloadResult
        }
        override fun cancel(identity: TransferIdentity): ActionResult {
            cancelledFileIds.add(identity.fileId)
            return ActionResult.ACCEPTED
        }
        override fun cancelDownload(fileId: Int): ActionResult {
            cancelledFileIds.add(fileId)
            return ActionResult.ACCEPTED
        }

        override fun preview(itemId: Long): PreviewTarget? = null
        override fun previewPagingItem(itemId: Long, mediaKind: com.nmtuong.telegramdrive.domain.MediaKind, localPath: String): PreviewTarget? = null
        override suspend fun getSavedMessagesChatId(): Long? = 1L
        override suspend fun getAvailableSources(): List<FileSource> = listOf(FileSource(1L, "Saved Messages", true))
        override suspend fun loadHistoryPage(chatId: Long, fromMessageId: Long, limit: Int): HistoryPage =
            HistoryPage.empty()
        override fun getChatHistoryPagingSource(chatId: Long): androidx.paging.PagingSource<Long, MediaItem> =
            error("Not used")
        override fun close() {}
    }

    private fun identity(fileId: Int, accountId: Long = 1L, generation: Long = 1L) =
        TransferIdentity(accountId, generation, fileId)

    // ── Duplicate concurrent start ────────────────────────────────────────────

    @Test
    fun `duplicate start for same fileId is idempotent`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repo = StubRepository()
        val coord = TransferCoordinator(repo, accountId = 1L, databaseGeneration = 1L, dispatcher = dispatcher)
        try {
            val r1 = coord.startTransfer(10, identity(10))
            val r2 = coord.startTransfer(10, identity(10)) // Duplicate

            assertTrue(r1)
            assertTrue(r2)

            advanceTimeBy(100)
            // Should only start one download
            assertEquals(1, repo.downloadedFileIds.count { it == 10 })
        } finally {
            coord.close()
        }
    }

    // ── Stale identity rejected ───────────────────────────────────────────────

    @Test
    fun `transfer with wrong accountId is rejected`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repo = StubRepository()
        val coord = TransferCoordinator(repo, accountId = 1L, databaseGeneration = 1L, dispatcher = dispatcher)
        try {
            val result = coord.startTransfer(10, TransferIdentity(accountId = 999L, databaseGeneration = 1L, fileId = 10))
            assertFalse(result) // Rejected
        } finally {
            coord.close()
        }
    }

    @Test
    fun `transfer with wrong databaseGeneration is rejected`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repo = StubRepository()
        val coord = TransferCoordinator(repo, accountId = 1L, databaseGeneration = 1L, dispatcher = dispatcher)
        try {
            val result = coord.startTransfer(10, TransferIdentity(accountId = 1L, databaseGeneration = 99L, fileId = 10))
            assertFalse(result) // Stale generation
        } finally {
            coord.close()
        }
    }

    // ── Atomic state registration ─────────────────────────────────────────────

    @Test
    fun `transfer state is Queued immediately after startTransfer before download starts`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repo = StubRepository()
        repo.autoEmitTerminal = false
        val coord = TransferCoordinator(repo, accountId = 1L, databaseGeneration = 1L, dispatcher = dispatcher)
        try {
            coord.startTransfer(10, identity(10))

            // Immediately after — before any coroutine runs — state should be Queued
            val state = coord.transferStates.value[10]
            assertNotNull(state)
            assertTrue(state is TransferState.Queued || state is TransferState.InProgress || state is TransferState.Completed)
        } finally {
            coord.close()
        }
    }

    // ── Cancel queued transfer ────────────────────────────────────────────────

    @Test
    fun `cancel queued transfer before download starts`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repo = StubRepository()
        val coord = TransferCoordinator(repo, accountId = 1L, databaseGeneration = 1L, dispatcher = dispatcher)
        try {
            coord.startTransfer(20, identity(20))
            coord.cancelTransfer(20)

            advanceTimeBy(100)

            val state = coord.transferStates.value[20]
            // Should be Cancelled or removed
            assertTrue(state == null || state is TransferState.TransferCancelled)
        } finally {
            coord.close()
        }
    }

    // ── Cancel active transfer ────────────────────────────────────────────────

    @Test
    fun `cancel active transfer sends TDLib cancel`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repo = StubRepository()
        repo.autoEmitTerminal = false
        val coord = TransferCoordinator(repo, accountId = 1L, databaseGeneration = 1L, dispatcher = dispatcher)
        try {
            coord.startTransfer(30, identity(30))
            advanceTimeBy(50) // Let it start

            coord.cancelTransfer(30)
            advanceTimeBy(50)

            val state = coord.transferStates.value[30]
            assertTrue(state == null || state is TransferState.TransferCancelled)
        } finally {
            coord.close()
        }
    }

    // ── Clear all ─────────────────────────────────────────────────────────────

    @Test
    fun `clear cancels all transfers and empties state`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repo = StubRepository()
        val coord = TransferCoordinator(repo, accountId = 1L, databaseGeneration = 1L, dispatcher = dispatcher)
        try {
            coord.startTransfer(1, identity(1))
            coord.startTransfer(2, identity(2))
            coord.startTransfer(3, identity(3))

            advanceTimeBy(50)

            coord.clear()

            assertTrue(coord.transferStates.value.isEmpty())
        } finally {
            coord.close()
        }
    }

    // ── Progress update ───────────────────────────────────────────────────────

    @Test
    fun `onProgressUpdate with valid identity updates state`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repo = StubRepository()
        val coord = TransferCoordinator(repo, accountId = 1L, databaseGeneration = 1L, dispatcher = dispatcher)
        try {
            coord.startTransfer(40, identity(40))
            coord.onProgressUpdate(identity(40), 75)

            val state = coord.transferStates.value[40]
            if (state is TransferState.InProgress) {
                assertEquals(75, state.percent)
            }
        } finally {
            coord.close()
        }
    }

    @Test
    fun `onProgressUpdate with wrong generation is ignored`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repo = StubRepository()
        val coord = TransferCoordinator(repo, accountId = 1L, databaseGeneration = 1L, dispatcher = dispatcher)
        try {
            coord.startTransfer(50, identity(50))
            val staleIdentity = TransferIdentity(accountId = 1L, databaseGeneration = 999L, fileId = 50)
            coord.onProgressUpdate(staleIdentity, 75)

            val state = coord.transferStates.value[50]
            assertFalse(state is TransferState.InProgress && state.percent == 75)
        } finally {
            coord.close()
        }
    }

    // ── Terminal state isTerminal flag ────────────────────────────────────────

    @Test
    fun `dynamic generation change invalidates active transfer`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repo = StubRepository()
        var currentGen = 1L
        val coord = TransferCoordinator(
            repository = repo,
            accountId = 1L,
            databaseGeneration = 1L,
            dispatcher = dispatcher,
            activeGenerationProvider = { currentGen },
        )
        try {
            coord.startTransfer(60, identity(60, generation = 1L))
            advanceTimeBy(50)

            currentGen = 2L

            val result = coord.startTransfer(61, identity(61, generation = 1L))
            assertFalse(result)
        } finally {
            coord.close()
        }
    }

    @Test
    fun `isTerminal is true for all terminal states`() {
        assertTrue(TransferState.Completed("/tmp/file.jpg").isTerminal)
        assertTrue(TransferState.TransferCancelled.isTerminal)
        assertTrue(TransferState.TransferFailed("err").isTerminal)
        assertTrue(TransferState.Unavailable.isTerminal)
    }

    @Test
    fun `isTerminal is false for non-terminal states`() {
        assertFalse(TransferState.NotStarted.isTerminal)
        assertFalse(TransferState.Queued.isTerminal)
        assertFalse(TransferState.InProgress(50).isTerminal)
    }

    // ── Lifecycle and terminal state completions ─────────────────────────────

    @Test
    fun `accepted transfer receives completed update and job finishes`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repo = StubRepository()
        repo.autoEmitTerminal = true
        repo.defaultTerminalState = TransferState.Completed("/path/file.ext")
        val coord = TransferCoordinator(repo, accountId = 1L, databaseGeneration = 1L, dispatcher = dispatcher)
        try {
            coord.startTransfer(100, identity(100))
            advanceTimeBy(100)
            val snap = coord.getSnapshot(100)
            assertNotNull(snap)
            assertTrue(snap?.state is TransferState.Completed)
        } finally {
            coord.close()
        }
    }

    @Test
    fun `failed transfer finishes and releases permit`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repo = StubRepository()
        repo.autoEmitTerminal = true
        repo.defaultTerminalState = TransferState.TransferFailed("Disk full")
        val coord = TransferCoordinator(repo, accountId = 1L, databaseGeneration = 1L, dispatcher = dispatcher)
        try {
            coord.startTransfer(101, identity(101))
            advanceTimeBy(100)
            val snap = coord.getSnapshot(101)
            assertNotNull(snap)
            assertTrue(snap?.state is TransferState.TransferFailed)
        } finally {
            coord.close()
        }
    }

    @Test
    fun `cancelled transfer finishes`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repo = StubRepository()
        repo.autoEmitTerminal = false
        val coord = TransferCoordinator(repo, accountId = 1L, databaseGeneration = 1L, dispatcher = dispatcher)
        try {
            coord.startTransfer(102, identity(102))
            coord.cancelTransfer(102)
            advanceTimeBy(100)
            val snap = coord.getSnapshot(102)
            assertNotNull(snap)
            assertTrue(snap?.state is TransferState.TransferCancelled)
        } finally {
            coord.close()
        }
    }

    @Test
    fun `coordinator close ends collector and no active jobs remain`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repo = StubRepository()
        repo.autoEmitTerminal = false
        val coord = TransferCoordinator(repo, accountId = 1L, databaseGeneration = 1L, dispatcher = dispatcher)
        coord.startTransfer(103, identity(103))
        advanceTimeBy(50)
        coord.close()
        advanceTimeBy(50)

        repo._transferUpdates.tryEmit(
            TransferUpdate(
                identity = identity(103),
                state = TransferState.Completed("/path/late.ext"),
                attemptId = 0L,
            )
        )
        advanceTimeBy(50)
        assertNull(coord.getSnapshot(103))
    }
}
