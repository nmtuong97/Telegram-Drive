package com.nmtuong.telegramdrive.feature.gallery

import com.nmtuong.telegramdrive.data.SavedMediaSyncResult
import com.nmtuong.telegramdrive.data.local.SavedMediaEntity
import com.nmtuong.telegramdrive.data.local.MediaSyncPhase
import com.nmtuong.telegramdrive.data.local.SyncStateEntity
import com.nmtuong.telegramdrive.domain.AccountSessionIdentity
import java.nio.file.Files
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GalleryPresentationTest {
  private val identity = AccountSessionIdentity(accountId = 10L, databaseGeneration = 2L)

  @Test
  fun videoPresentationIncludesAllAvailableMetadata() {
    val path = "/complete-video.mp4"
    val item = galleryItemUiModel(
      media(1L).copy(
        mediaType = "VIDEO",
        stableDisplayName = "holiday-video.mp4",
        durationSeconds = 342,
        expectedSizeBytes = 125L * 1024 * 1024,
        width = 1_920,
        height = 1_080,
        localFilePath = path,
      ),
      identity,
      Locale.US,
      localFileProbe = { GalleryLocalFileObservation(exists = true, readable = true, sizeBytes = 125L * 1024 * 1024) },
    )

    assertEquals("holiday-video.mp4", item.displayName)
    assertEquals("5:42", item.durationText)
    assertNotNull(item.fileSizeText)
    assertEquals("1920×1080", item.resolutionText)
    assertNotNull(item.dateText)
    assertEquals(GalleryFileAvailability.LOCAL_COMPLETE, item.availability)
  }

  @Test
  fun missingSizeResolutionAndDurationAreOmitted() {
    val item = galleryItemUiModel(
      media(2L).copy(mediaType = "VIDEO", durationSeconds = 0, expectedSizeBytes = 0L, width = 0, height = 0),
      identity,
      Locale.US,
    )

    assertNull(item.durationText)
    assertNull(item.fileSizeText)
    assertNull(item.resolutionText)
    assertNull(item.metadataText)
  }

  @Test
  fun imageNeverGetsVideoDuration() {
    val item = galleryItemUiModel(media(3L).copy(mediaType = "IMAGE", durationSeconds = 99), identity, Locale.US)

    assertNull(item.durationText)
  }

  @Test
  fun longAndUnicodeFilenamesRemainUnchanged() {
    val name = "旅行写真_مرحبا_очень-длинное-название-final.jpg"
    val item = galleryItemUiModel(media(4L).copy(stableDisplayName = name), identity, Locale.US)

    assertEquals(name, item.displayName)
    assertFalse(item.usesFallbackName)
  }

  @Test
  fun formattersDoNotEmitZeroMetadataOrExtraSeparators() {
    assertNull(formatGalleryFileSize(0L))
    assertEquals("1 KB", formatGalleryFileSize(1_024L))
    assertEquals("1.5 MB", formatGalleryFileSize(1_572_864L))
    assertEquals("1:01:01", formatGalleryDuration(3_661))
    assertEquals("5:42", formatGalleryDuration(342))
    assertEquals("125 MB", galleryItemUiModel(media(5L).copy(expectedSizeBytes = 125L * 1024 * 1024, width = 0, height = 0), identity, Locale.US).metadataText)
  }

  @Test
  fun dateFormattingUsesRequestedLocale() {
    val entity = media(6L).copy(messageDateEpochSeconds = 1_751_320_000L)

    val english = galleryItemUiModel(entity, identity, Locale.US).dateText
    val vietnamese = galleryItemUiModel(entity, identity, Locale("vi", "VN")).dateText

    assertNotNull(english)
    assertNotNull(vietnamese)
    assertNotEquals(english, vietnamese)
  }

  @Test
  fun availabilityRequiresIdentityAndVerifiedExpectedSize() {
    val complete = media(7L).copy(localFilePath = "/complete", expectedSizeBytes = 100L)
    val partial = media(8L).copy(localFilePath = "/partial", expectedSizeBytes = 100L)
    val missingExpected = media(9L).copy(localFilePath = "/complete", expectedSizeBytes = 0L)
    val invalidPath = media(10L).copy(localFilePath = "/stale", expectedSizeBytes = 100L)

    val probe = mapOf(
      "/complete" to GalleryLocalFileObservation(exists = true, readable = true, sizeBytes = 100L),
      "/partial" to GalleryLocalFileObservation(exists = true, readable = true, sizeBytes = 40L),
      "/stale" to GalleryLocalFileObservation(exists = false, readable = false, sizeBytes = 0L),
    )
    val fileProbe: (String) -> GalleryLocalFileObservation = { checkNotNull(probe[it]) }

    assertEquals(GalleryFileAvailability.LOCAL_COMPLETE, galleryFileAvailability(complete, identity, fileProbe))
    assertEquals(GalleryFileAvailability.PARTIAL, galleryFileAvailability(partial, identity, fileProbe))
    assertEquals(GalleryFileAvailability.PARTIAL, galleryFileAvailability(missingExpected, identity, fileProbe))
    assertEquals(GalleryFileAvailability.REMOTE_STREAMABLE, galleryFileAvailability(invalidPath, identity, fileProbe))
    assertEquals(GalleryFileAvailability.REMOTE_STREAMABLE, galleryFileAvailability(media(11L), identity, fileProbe))
    assertEquals(GalleryFileAvailability.UNAVAILABLE, galleryFileAvailability(media(12L).copy(available = false), identity, fileProbe))
    assertEquals(GalleryFileAvailability.UNAVAILABLE, galleryFileAvailability(complete, null, fileProbe))
  }

  @Test
  fun existingFileWithoutExpectedSizeIsNotLocalComplete() {
    val file = Files.createTempFile("gallery-partial-", ".bin").toFile().also { it.writeBytes(ByteArray(8)) }
    val entity = media(13L).copy(localFilePath = file.absolutePath, expectedSizeBytes = 0L)

    assertEquals(GalleryFileAvailability.PARTIAL, galleryItemUiModel(entity, identity, Locale.US).availability)
    file.delete()
  }

  @Test
  fun gridBreakpointsAreDeterministic() {
    assertEquals(2, galleryColumnCount(599))
    assertEquals(3, galleryColumnCount(600))
    assertEquals(3, galleryColumnCount(839))
    assertEquals(4, galleryColumnCount(840))
  }

  @Test
  fun activeSyncIsCompactAndIdleSyncHasNoStatus() {
    val active = syncPresentation(syncState(MediaSyncPhase.BACKFILLING), null)
    val completed = syncPresentation(syncState(MediaSyncPhase.COMPLETED), SavedMediaSyncResult.Completed)

    assertTrue(active.isActive)
    assertTrue(active.showCompactStatus)
    assertFalse(completed.showCompactStatus)
  }

  @Test
  fun retryableAndNonRetryableSyncFailuresStayDistinct() {
    val retryable = syncPresentation(null, SavedMediaSyncResult.Failed("network", retryable = true))
    val fatal = syncPresentation(null, SavedMediaSyncResult.Failed("account", retryable = false))

    assertTrue(retryable.showRetry)
    assertTrue(retryable.syncEnabled)
    assertFalse(fatal.showRetry)
    assertFalse(fatal.syncEnabled)
    assertEquals(SyncPresentationState.UNAVAILABLE, fatal.state)
  }

  @Test
  fun staleSyncResultCannotBePublishedForAnotherAccount() {
    val first = AccountSessionIdentity(accountId = 10L, databaseGeneration = 1L)
    val second = AccountSessionIdentity(accountId = 20L, databaseGeneration = 1L)

    assertTrue(shouldPublishSyncResult(first, first))
    assertFalse(shouldPublishSyncResult(first, second))
    assertFalse(shouldPublishSyncResult(first, null))
  }

  private fun syncState(phase: MediaSyncPhase) = SyncStateEntity(
    accountId = identity.accountId,
    databaseGeneration = identity.databaseGeneration,
    chatId = 1L,
    phase = phase.name,
    backfillCursor = null,
    headWatermark = null,
    lastCheckpointAtEpochMillis = null,
    lastSuccessfulCatchUpHead = null,
    lastError = null,
    retryCount = 0,
    lastAttemptAtEpochMillis = null,
  )

  private fun media(messageId: Long) = SavedMediaEntity(
    accountId = identity.accountId,
    databaseGeneration = identity.databaseGeneration,
    chatId = 1L,
    messageId = messageId,
    mediaType = "IMAGE",
    messageDateEpochSeconds = 1_725_000_000L,
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
