package com.nmtuong.telegramdrive.data.fake

import com.nmtuong.telegramdrive.domain.Account
import com.nmtuong.telegramdrive.domain.DownloadState
import com.nmtuong.telegramdrive.domain.FileSource
import com.nmtuong.telegramdrive.domain.MediaItem
import com.nmtuong.telegramdrive.domain.MediaKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeSavedMediaGatewayTest {
  @Test
  fun pagesLargeMultiYearDatasetWithDuplicateFilesAndVideoDocuments() = runBlocking {
    val rawMessages = (0 until 3_000).map { index ->
      val id = 20_000L - index
      val kind = if (index % 4 == 0) MediaKind.VIDEO else MediaKind.IMAGE
      FakeRawMessage(
        id = id,
        sourceId = 10L,
        mediaItem = MediaItem(
          id = id,
          sourceId = 10L,
          name = if (kind == MediaKind.VIDEO) "clip-$index.mkv" else "photo-$index.jpg",
          kind = kind,
          downloadState = DownloadState.NotDownloaded,
          fileId = 700 + (index % 3),
          mimeType = if (kind == MediaKind.VIDEO) "video/x-matroska" else "image/jpeg",
          dateEpochSeconds = 1_500_000_000L - (index * 86_400L),
          caption = "caption-$index",
        ),
      )
    }
    val catalog = FakeTelegramCatalog(
      account = Account(22L, "Large fake"),
      sources = listOf(FileSource(10L, "Saved Messages", savedMessages = true)),
      media = rawMessages.mapNotNull { it.mediaItem },
      rawMessages = rawMessages,
    )
    val gateway = FakeSavedMediaGateway(catalog)
    var cursor = 0L
    var pages = 0
    var indexed = 0
    while (pages < 100) {
      val page = gateway.loadHistoryPage(10L, cursor, 100)
      indexed += page.items.size
      pages++
      if (page.endOfHistory || page.rawLastMessageId == null || page.rawLastMessageId == cursor) break
      cursor = page.rawLastMessageId
    }
    assertEquals(3_000, indexed)
    assertEquals(20_000L, gateway.getSavedMessagesHead(10L))
    assertTrue(catalog.media.count { it.kind == MediaKind.VIDEO } > 700)
    assertEquals(3, catalog.media.map { it.stableFileIdentity }.distinct().size)
  }
}
