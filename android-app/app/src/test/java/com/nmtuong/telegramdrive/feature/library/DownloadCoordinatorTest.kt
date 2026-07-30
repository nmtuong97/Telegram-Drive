package com.nmtuong.telegramdrive.feature.library

import com.nmtuong.telegramdrive.data.TelegramRepository
import com.nmtuong.telegramdrive.domain.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadCoordinatorTest {

    private class FakeRepo : TelegramRepository {
        val libraryState = MutableStateFlow<LibraryState>(LibraryState.Idle)
        override val library = libraryState
        override val diagnostics = MutableStateFlow(DiagnosticsState(dataSource = DataSourceMode.FAKE, authorizationState = AuthorizationState.Ready))
        override val authorization = MutableStateFlow(AuthorizationSession(AuthorizationState.Ready))
        
        var startedDownloads = mutableListOf<Int>()
        var canceledDownloads = mutableListOf<Int>()

        override fun start() {}
        override fun submit(action: AuthorizationAction) = ActionResult.ACCEPTED
        override fun loadSavedMessages(limit: Int) = ActionResult.ACCEPTED
        override suspend fun getSavedMessagesChatId(): Long? = 1L
        override fun getChatHistoryPagingSource(chatId: Long): androidx.paging.PagingSource<Long, MediaItem> = error("Not implemented")
        
        override fun download(fileId: Int): ActionResult {
            startedDownloads.add(fileId)
            return ActionResult.ACCEPTED
        }
        
        override fun cancelDownload(fileId: Int): ActionResult {
            canceledDownloads.add(fileId)
            return ActionResult.ACCEPTED
        }
        
        override fun preview(itemId: Long): PreviewTarget? = null
        override fun close() {}
    }

    @Test
    fun testConcurrencyLimitAndDedup() = runTest {
        val repo = FakeRepo()
        val coordinator = DownloadCoordinator(repo, this)
        
        // Start 5 downloads
        for (i in 1..5) {
            coordinator.startDownload(i)
        }
        
        // Dedup check: Start same download again, should be ignored
        coordinator.startDownload(1)
        
        // Wait a bit for coroutines to launch
        advanceTimeBy(100)
        
        // Only 3 should have actually called download() due to semaphore limit of 3
        assertEquals(3, repo.startedDownloads.size)
        assertTrue(repo.startedDownloads.containsAll(listOf(1, 2, 3)))
        
        // Update library to finish download 1
        val item1 = MediaItem(id = 1L, sourceId = 1L, name = "test1", kind = MediaKind.DOCUMENT, downloadState = DownloadState.Complete, fileId = 1)
        repo.libraryState.value = LibraryState.Content(listOf(item1))
        
        // Let flow collect
        advanceTimeBy(100)
        
        // Now download 4 should start
        assertEquals(4, repo.startedDownloads.size)
        assertTrue(repo.startedDownloads.contains(4))
        
        // Cancel download 2
        coordinator.cancelDownload(2)
        advanceTimeBy(100)
        
        // Now download 5 should start
        assertEquals(5, repo.startedDownloads.size)
        assertTrue(repo.startedDownloads.contains(5))
        
        // Clear all
        coordinator.clear()
        assertTrue(coordinator.activeDownloads.value.isEmpty())
    }
}
