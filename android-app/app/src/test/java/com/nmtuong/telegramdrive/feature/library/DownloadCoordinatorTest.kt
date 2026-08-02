package com.nmtuong.telegramdrive.feature.library

import com.nmtuong.telegramdrive.data.TelegramRepository
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

class DownloadCoordinatorTest {

    private class FakeRepo : TelegramRepository {
        val libraryState = MutableStateFlow<LibraryState>(LibraryState.Idle)
        override val library: StateFlow<LibraryState> = libraryState
        override val resetProgress: StateFlow<ResetProgress> = MutableStateFlow(ResetProgress.Idle)
        val _transferUpdates = MutableSharedFlow<TransferUpdate>(extraBufferCapacity = 64)
        override val transferUpdates: Flow<TransferUpdate> = _transferUpdates.asSharedFlow()
        override val diagnostics = MutableStateFlow(DiagnosticsState(dataSource = DataSourceMode.FAKE, authorizationState = AuthorizationState.Ready))
        override val authorization = MutableStateFlow(AuthorizationSession(AuthorizationState.Ready))

        var startedDownloads = mutableListOf<Int>()
        var canceledDownloads = mutableListOf<Int>()
        var downloadResult: ActionResult = ActionResult.ACCEPTED
        var autoEmitTerminal: Boolean = true
        var defaultTerminalState: TransferState = TransferState.Completed("/tmp/fake_path")

        override fun start() {}
        override fun submit(action: AuthorizationAction) = ActionResult.ACCEPTED
        override suspend fun logoutAndReset(): AccountResetResult = AccountResetResult.Completed
        override fun loadSavedMessages(limit: Int) = ActionResult.ACCEPTED
        override suspend fun getSavedMessagesChatId(): Long? = 1L
        override suspend fun getAvailableSources(): List<FileSource> = listOf(FileSource(1L, "Saved Messages", true))
        override suspend fun loadHistoryPage(chatId: Long, fromMessageId: Long, limit: Int) =
            com.nmtuong.telegramdrive.domain.HistoryPage.empty()
        override fun getChatHistoryPagingSource(chatId: Long): androidx.paging.PagingSource<Long, MediaItem> =
            error("Not implemented")

        override fun download(request: TransferRequest): ActionResult {
            startedDownloads.add(request.fileId)
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
            startedDownloads.add(fileId)
            return downloadResult
        }

        override fun downloadPagingItem(fileId: Int): ActionResult {
            startedDownloads.add(fileId)
            return downloadResult
        }

        override fun cancel(identity: TransferIdentity): ActionResult {
            canceledDownloads.add(identity.fileId)
            return ActionResult.ACCEPTED
        }

        override fun cancelDownload(fileId: Int): ActionResult {
            canceledDownloads.add(fileId)
            return ActionResult.ACCEPTED
        }

        override fun preview(itemId: Long): PreviewTarget? = null
        override fun previewPagingItem(itemId: Long, mediaKind: com.nmtuong.telegramdrive.domain.MediaKind, localPath: String): PreviewTarget? = null
        override fun close() {}
    }

    @Test
    fun `startDownload is idempotent for same fileId`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repo = FakeRepo()
        val coordinator = DownloadCoordinator(repo, accountId = 1L, databaseGeneration = 1L, dispatcher = dispatcher)
        try {
            coordinator.startDownload(1)
            coordinator.startDownload(1) // Duplicate

            advanceTimeBy(100)

            // Should only start one download
            assertEquals(1, repo.startedDownloads.count { it == 1 })
        } finally {
            coordinator.close()
        }
    }

    @Test
    fun `cancelDownload cancels an active download`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repo = FakeRepo()
        repo.autoEmitTerminal = false
        val coordinator = DownloadCoordinator(repo, accountId = 1L, databaseGeneration = 1L, dispatcher = dispatcher)
        try {
            coordinator.startDownload(5)
            advanceTimeBy(50)

            coordinator.cancelDownload(5)
            advanceTimeBy(50)

            // Should have requested cancel from repo
            assertTrue(repo.canceledDownloads.contains(5))
        } finally {
            coordinator.close()
        }
    }

    @Test
    fun `clear empties all state`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repo = FakeRepo()
        val coordinator = DownloadCoordinator(repo, accountId = 1L, databaseGeneration = 1L, dispatcher = dispatcher)
        try {
            coordinator.startDownload(1)
            coordinator.startDownload(2)
            advanceTimeBy(100)

            coordinator.clear()

            assertTrue(coordinator.transferStates.value.isEmpty())
        } finally {
            coordinator.close()
        }
    }
}
