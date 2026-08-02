package com.nmtuong.telegramdrive.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nmtuong.telegramdrive.data.local.MediaDatabase
import com.nmtuong.telegramdrive.data.local.MediaSyncPhase
import com.nmtuong.telegramdrive.domain.DownloadState
import com.nmtuong.telegramdrive.domain.HistoryPage
import com.nmtuong.telegramdrive.domain.MediaItem
import com.nmtuong.telegramdrive.domain.MediaKind
import com.nmtuong.telegramdrive.domain.SavedMediaGateway
import com.nmtuong.telegramdrive.domain.SavedMessageUpdate
import com.nmtuong.telegramdrive.domain.TdLibFileSnapshot
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SavedMediaRepositorySyncTest {
  private lateinit var database: MediaDatabase

  @Before
  fun setUp() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    database = Room.inMemoryDatabaseBuilder(context, MediaDatabase::class.java)
      .allowMainThreadQueries()
      .build()
  }

  @After
  fun tearDown() {
    database.close()
  }

  @Test
  fun crashAfterCheckpointResumesAndIncrementalUpdatesRemainIdempotent() = runBlocking {
    val identityProvider = AccountSessionIdentityProvider().also { it.initializeFake(42L) }
    val gateway = ScriptedSavedMediaGateway(
      chatId = 900L,
      messages = (0 until 250).map { index ->
        val id = 1_000L - index
        MediaItem(
          id = id,
          sourceId = 900L,
          name = "media-$id.jpg",
          kind = if (index % 4 == 0) MediaKind.VIDEO else MediaKind.IMAGE,
          downloadState = DownloadState.NotDownloaded,
          fileId = 700 + index,
          dateEpochSeconds = 1_700_000_000L - index,
          stableFileIdentity = "remote-$id",
        )
      },
    )
    val repository = SavedMediaRepository(database, gateway, identityProvider)
    repository.start()

    gateway.cancelOnHistoryCall = 3
    try {
      repository.syncSavedMessages()
      error("expected a simulated process interruption")
    } catch (_: CancellationException) {
      // The committed BACKFILLING checkpoint is the crash-resume contract.
    }
    val checkpoint = database.syncStateDao().find(42L, 1L, 900L)
    assertEquals(MediaSyncPhase.BACKFILLING.name, checkpoint?.phase)
    assertEquals(801L, checkpoint?.backfillCursor)

    gateway.cancelOnHistoryCall = null
    gateway.historyCursors.clear()
    assertEquals(SavedMediaSyncResult.Completed, repository.syncSavedMessages())
    assertEquals(801L, gateway.historyCursors.first())
    assertEquals(250, database.savedMediaDao().pagingSource(42L, 1L, "", "", 0, 1)
      .load(androidx.paging.PagingSource.LoadParams.Refresh(null, 300, false))
      .let { result -> (result as androidx.paging.PagingSource.LoadResult.Page).data.size })

    gateway.emit(SavedMessageUpdate.Upsert(900L, gateway.media(1_100L)))
    gateway.emit(SavedMessageUpdate.Changed(900L, 999L, gateway.media(999L).copy(name = "edited.jpg")))
    gateway.emit(SavedMessageUpdate.Deleted(900L, 998L))
    withTimeout(2_000L) {
      while (database.savedMediaDao().find(42L, 1L, 900L, 999L)?.stableDisplayName != "edited.jpg") delay(10)
    }
    withTimeout(2_000L) {
      while (database.savedMediaDao().find(42L, 1L, 900L, 998L)?.deleted != true) delay(10)
    }
    assertTrue(database.savedMediaDao().find(42L, 1L, 900L, 1_100L) != null)
    repository.close()
  }
}

private class ScriptedSavedMediaGateway(
  private val chatId: Long,
  messages: List<MediaItem>,
) : SavedMediaGateway {
  private val messages = CopyOnWriteArrayList(messages)
  private val updates = MutableSharedFlow<SavedMessageUpdate>(extraBufferCapacity = 64)
  val historyCursors = CopyOnWriteArrayList<Long>()
  var cancelOnHistoryCall: Int? = null
  private var historyCalls = 0

  override val savedMessageUpdates: Flow<SavedMessageUpdate> = updates
  override val fileUpdates: Flow<TdLibFileSnapshot> = emptyFlow()
  override suspend fun getSavedMessagesChatId(): Long = chatId
  override suspend fun getSavedMessagesHead(chatId: Long): Long? = messages.maxOfOrNull(MediaItem::id)

  override suspend fun loadHistoryPage(chatId: Long, fromMessageId: Long, limit: Int): HistoryPage {
    historyCalls++
    historyCursors += fromMessageId
    if (cancelOnHistoryCall == historyCalls) throw CancellationException("simulated process interruption")
    val page = messages.filter { fromMessageId == 0L || it.id < fromMessageId }
      .sortedByDescending { it.id }
      .take(limit)
    if (page.isEmpty()) return HistoryPage.empty()
    return HistoryPage(
      items = page,
      rawLastMessageId = page.last().id,
      endOfHistory = page.size < limit,
    )
  }

  fun media(id: Long): MediaItem = messages.firstOrNull { it.id == id } ?: MediaItem(
    id = id,
    sourceId = chatId,
    name = "media-$id.jpg",
    kind = MediaKind.IMAGE,
    downloadState = DownloadState.NotDownloaded,
    fileId = id.toInt(),
    dateEpochSeconds = 1_700_000_000L,
    stableFileIdentity = "remote-$id",
  )

  fun emit(update: SavedMessageUpdate) {
    when (update) {
      is SavedMessageUpdate.Upsert -> messages.addIfAbsent(update.message)
      is SavedMessageUpdate.Changed -> {
        messages.removeIf { it.id == update.messageId }
        update.message?.let(messages::addIfAbsent)
      }
      is SavedMessageUpdate.Deleted -> messages.removeIf { it.id == update.messageId }
    }
    updates.tryEmit(update)
  }
}
