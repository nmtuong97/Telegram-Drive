package com.nmtuong.telegramdrive.data

import android.content.Context
import androidx.paging.PagingSource
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nmtuong.telegramdrive.data.local.CachedFileEntity
import com.nmtuong.telegramdrive.data.local.CachedFileState
import com.nmtuong.telegramdrive.data.local.CachedFileType
import com.nmtuong.telegramdrive.data.local.MediaDatabase
import com.nmtuong.telegramdrive.data.local.SavedMediaEntity
import com.nmtuong.telegramdrive.data.local.SavedMediaType
import com.nmtuong.telegramdrive.data.local.SyncStateEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SavedMediaRoomPagingTest {
  private lateinit var database: MediaDatabase

  @Before
  fun setUp() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    database = Room.inMemoryDatabaseBuilder(context, MediaDatabase::class.java)
      .allowMainThreadQueries()
      .build()
  }

  @After
  fun tearDown() = database.close()

  @Test
  fun thousandsOfRowsPageFromRoomAndSharedFileRemainsDeduplicated() = runBlocking {
    val rows = (0 until 2_000).map { index ->
      SavedMediaEntity(
        accountId = 7L,
        databaseGeneration = 3L,
        chatId = 99L,
        messageId = 10_000L - index,
        mediaType = if (index % 3 == 0) SavedMediaType.VIDEO.name else SavedMediaType.IMAGE.name,
        messageDateEpochSeconds = 1_700_000_000L - index,
        caption = if (index % 5 == 0) "caption-$index" else "",
        stableDisplayName = "media-$index.jpg",
        mimeType = "image/jpeg",
        width = 1200,
        height = 800,
        durationSeconds = 0,
        telegramFileId = 500 + (index % 2),
        originalStableFileIdentity = "remote-file-${index % 2}",
        thumbnailFileId = null,
        thumbnailStableFileIdentity = null,
        minithumbnailData = null,
        minithumbnailWidth = 0,
        minithumbnailHeight = 0,
        localFilePath = null,
      )
    }
    for (row in rows) database.savedMediaDao().upsert(row)

    val firstPage = database.savedMediaDao().pagingSource(7L, 3L, "", "", 0, 1)
      .load(PagingSource.LoadParams.Refresh(null, 50, false)) as PagingSource.LoadResult.Page
    assertEquals(50, firstPage.data.size)
    assertEquals(rows.first().messageId, firstPage.data.first().messageId)

    val filtered = database.savedMediaDao().pagingSource(7L, 3L, "caption-100", "", 0, 1)
      .load(PagingSource.LoadParams.Refresh(null, 50, false)) as PagingSource.LoadResult.Page
    assertTrue(filtered.data.all { it.caption.contains("caption-100") })

    database.cachedFileDao().upsert(
      CachedFileEntity(7L, 3L, "remote-file-0", 500, "/tmp/shared", CachedFileType.IMAGE_ORIGINAL.name, 128L, 1L, CachedFileState.COMPLETE.name),
    )
    assertEquals(1, database.cachedFileDao().list(7L, 3L).size)
    database.syncStateDao().upsert(SyncStateEntity(7L, 3L, 99L, "BACKFILLING", 9_000L, 10_000L, 1L, null, null, 0, 1L))
    assertEquals("BACKFILLING", database.syncStateDao().find(7L, 3L, 99L)?.phase)
  }
}
