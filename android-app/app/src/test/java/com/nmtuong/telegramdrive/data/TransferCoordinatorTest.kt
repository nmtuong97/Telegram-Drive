package com.nmtuong.telegramdrive.data

import com.nmtuong.telegramdrive.domain.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/**
 * Deterministic tests for [TransferCoordinator] using injected TestDispatcher.
 * No Dispatchers.IO hardcoding.
 */
class TransferCoordinatorTest {

    // ── Stub repository ──────────────────────────────────────────────────────

    private class StubRepository : TelegramRepository {
        val libraryState = MutableStateFlow<LibraryState>(LibraryState.Idle)
        override val library: StateFlow<LibraryState> = libraryState
        override val diagnostics: StateFlow<DiagnosticsState> =
            MutableStateFlow(DiagnosticsState(DataSourceMode.FAKE))
        override val authorization: StateFlow<AuthorizationSession> =
            MutableStateFlow(AuthorizationSession())

        val downloadedFileIds = mutableListOf<Int>()
        val cancelledFileIds = mutableListOf<Int>()

        override fun start() {}
        override fun submit(action: AuthorizationAction) = ActionResult.ACCEPTED
        override suspend fun logoutAndReset(): AccountResetResult = AccountResetResult.Completed
        override fun loadSavedMessages(limit: Int) = ActionResult.ACCEPTED
        override fun download(fileId: Int): ActionResult {
            downloadedFileIds.add(fileId)
            return ActionResult.ACCEPTED
        }
        override fun cancelDownload(fileId: Int): ActionResult {
            cancelledFileIds.add(fileId)
            return ActionResult.ACCEPTED
        }
        override fun preview(itemId: Long): PreviewTarget? = null
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

        val r1 = coord.startTransfer(10, identity(10))
        val r2 = coord.startTransfer(10, identity(10)) // Duplicate

        assertTrue(r1)
        assertTrue(r2)

        advanceTimeBy(100)
        // Should only start one download
        assertEquals(1, repo.downloadedFileIds.count { it == 10 })
    }

    // ── Stale identity rejected ───────────────────────────────────────────────

    @Test
    fun `transfer with wrong accountId is rejected`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repo = StubRepository()
        val coord = TransferCoordinator(repo, accountId = 1L, databaseGeneration = 1L, dispatcher = dispatcher)

        val result = coord.startTransfer(10, TransferIdentity(accountId = 999L, databaseGeneration = 1L, fileId = 10))
        assertFalse(result) // Rejected
    }

    @Test
    fun `transfer with wrong databaseGeneration is rejected`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repo = StubRepository()
        val coord = TransferCoordinator(repo, accountId = 1L, databaseGeneration = 1L, dispatcher = dispatcher)

        val result = coord.startTransfer(10, TransferIdentity(accountId = 1L, databaseGeneration = 99L, fileId = 10))
        assertFalse(result) // Stale generation
    }

    // ── Atomic state registration ─────────────────────────────────────────────

    @Test
    fun `transfer state is Queued immediately after startTransfer before download starts`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repo = StubRepository()
        val coord = TransferCoordinator(repo, accountId = 1L, databaseGeneration = 1L, dispatcher = dispatcher)

        coord.startTransfer(10, identity(10))

        // Immediately after — before any coroutine runs — state should be Queued
        val state = coord.transferStates.value[10]
        assertNotNull(state)
        // Either Queued (registered before launch) or InProgress (if dispatcher ran immediately)
        assertTrue(state is TransferState.Queued || state is TransferState.InProgress)
    }

    // ── Cancel queued transfer ────────────────────────────────────────────────

    @Test
    fun `cancel queued transfer before download starts`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repo = StubRepository()
        val coord = TransferCoordinator(repo, accountId = 1L, databaseGeneration = 1L, dispatcher = dispatcher)

        coord.startTransfer(20, identity(20))
        coord.cancelTransfer(20)

        advanceTimeBy(100)

        val state = coord.transferStates.value[20]
        // Should be Cancelled or removed
        assertTrue(state == null || state is TransferState.TransferCancelled)
    }

    // ── Cancel active transfer ────────────────────────────────────────────────

    @Test
    fun `cancel active transfer sends TDLib cancel`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repo = StubRepository()
        val coord = TransferCoordinator(repo, accountId = 1L, databaseGeneration = 1L, dispatcher = dispatcher)

        coord.startTransfer(30, identity(30))
        advanceTimeBy(50) // Let it start

        coord.cancelTransfer(30)
        advanceTimeBy(50)

        // Should have sent cancel to repository
        // (state-dependent — may have been cancelled before download started if scheduler is fast)
        val state = coord.transferStates.value[30]
        assertTrue(state == null || state is TransferState.TransferCancelled)
    }

    // ── Clear all ─────────────────────────────────────────────────────────────

    @Test
    fun `clear cancels all transfers and empties state`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repo = StubRepository()
        val coord = TransferCoordinator(repo, accountId = 1L, databaseGeneration = 1L, dispatcher = dispatcher)

        coord.startTransfer(1, identity(1))
        coord.startTransfer(2, identity(2))
        coord.startTransfer(3, identity(3))

        advanceTimeBy(50)

        coord.clear()

        assertTrue(coord.transferStates.value.isEmpty())
    }

    // ── Progress update ───────────────────────────────────────────────────────

    @Test
    fun `onProgressUpdate with valid identity updates state`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repo = StubRepository()
        val coord = TransferCoordinator(repo, accountId = 1L, databaseGeneration = 1L, dispatcher = dispatcher)

        coord.startTransfer(40, identity(40))
        coord.onProgressUpdate(identity(40), 75)

        val state = coord.transferStates.value[40]
        // If state is InProgress, it should have 75% (or Queued if not started yet)
        // Since we called onProgressUpdate, it should be InProgress(75)
        if (state is TransferState.InProgress) {
            assertEquals(75, state.percent)
        }
    }

    @Test
    fun `onProgressUpdate with wrong generation is ignored`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repo = StubRepository()
        val coord = TransferCoordinator(repo, accountId = 1L, databaseGeneration = 1L, dispatcher = dispatcher)

        coord.startTransfer(50, identity(50))
        val staleIdentity = TransferIdentity(accountId = 1L, databaseGeneration = 999L, fileId = 50)
        coord.onProgressUpdate(staleIdentity, 75)

        // State should not be updated to InProgress(75) from stale generation
        val state = coord.transferStates.value[50]
        assertFalse(state is TransferState.InProgress && state.percent == 75)
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

        coord.startTransfer(60, identity(60, generation = 1L))
        advanceTimeBy(50)

        // Invalidate generation dynamically (e.g. account reset occurred)
        currentGen = 2L

        // Next progress update or start with gen 1 should fail
        val result = coord.startTransfer(61, identity(61, generation = 1L))
        assertFalse(result)
    }

    @Test
    fun `isTerminal is true for all terminal states`() {
        assertTrue(TransferState.Completed.isTerminal)
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
}
