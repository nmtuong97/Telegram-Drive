package com.nmtuong.telegramdrive.feature.gallery

import com.nmtuong.telegramdrive.data.SavedMediaSyncResult
import com.nmtuong.telegramdrive.data.local.SavedMediaEntity
import com.nmtuong.telegramdrive.domain.AccountSessionIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GalleryPresentationTest {
  @Test
  fun retryableSyncFailureEnablesRetry() {
    val presentation = syncPresentation(
      state = null,
      result = SavedMediaSyncResult.Failed("network", retryable = true),
    )

    assertTrue(presentation.showRetry)
    assertTrue(presentation.syncEnabled)
  }

  @Test
  fun nonRetryableSyncFailureDoesNotOfferRetryOrSync() {
    val presentation = syncPresentation(
      state = null,
      result = SavedMediaSyncResult.Failed("account not ready", retryable = false),
    )

    assertFalse(presentation.showRetry)
    assertFalse(presentation.syncEnabled)
  }

  @Test
  fun staleSyncResultCannotBePublishedForAnotherAccount() {
    val first = AccountSessionIdentity(accountId = 10L, databaseGeneration = 1L)
    val second = AccountSessionIdentity(accountId = 20L, databaseGeneration = 1L)

    assertTrue(shouldPublishSyncResult(first, first))
    assertFalse(shouldPublishSyncResult(first, second))
    assertFalse(shouldPublishSyncResult(first, null))
  }

  @Test
  fun monthHeadersStartSectionsAndStayOutOfMediaCells() {
    val august = GalleryGridItem.Media(media(1L, 1_725_000_000L))
    val july = GalleryGridItem.Media(media(2L, 1_722_000_000L))

    assertNotNull(monthHeaderFor(null, august))
    assertNull(monthHeaderFor(august, GalleryGridItem.Media(media(3L, 1_725_100_000L))))
    assertEquals(galleryMonthKey(july.entity), monthHeaderFor(august, july)?.month)
  }

  private fun media(messageId: Long, dateEpochSeconds: Long) = SavedMediaEntity(
    accountId = 1L,
    databaseGeneration = 1L,
    chatId = 1L,
    messageId = messageId,
    mediaType = "IMAGE",
    messageDateEpochSeconds = dateEpochSeconds,
    caption = "",
    stableDisplayName = "preview-$messageId.jpg",
    mimeType = "image/jpeg",
    width = 100,
    height = 100,
    durationSeconds = 0,
    telegramFileId = messageId.toInt(),
    originalStableFileIdentity = "test:$messageId",
    thumbnailFileId = null,
    thumbnailStableFileIdentity = null,
    minithumbnailData = null,
    minithumbnailWidth = 0,
    minithumbnailHeight = 0,
    localFilePath = null,
  )
}
