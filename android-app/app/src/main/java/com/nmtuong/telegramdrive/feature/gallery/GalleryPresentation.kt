package com.nmtuong.telegramdrive.feature.gallery

import com.nmtuong.telegramdrive.data.local.SavedMediaEntity
import com.nmtuong.telegramdrive.domain.AccountSessionIdentity
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

internal enum class GalleryItemMediaType { IMAGE, VIDEO }

internal enum class GalleryFileAvailability {
  LOCAL_COMPLETE,
  REMOTE_STREAMABLE,
  PARTIAL,
  DOWNLOADING,
  UNAVAILABLE,
}

internal data class GalleryLocalFileObservation(
  val exists: Boolean,
  val readable: Boolean,
  val sizeBytes: Long,
)

internal data class GalleryItemUiModel(
  val stableKey: String,
  val source: SavedMediaEntity,
  val mediaType: GalleryItemMediaType,
  val displayName: String,
  val usesFallbackName: Boolean,
  val durationText: String?,
  val fileSizeText: String?,
  val resolutionText: String?,
  val dateText: String?,
  val availability: GalleryFileAvailability,
  val thumbnailStableIdentity: String?,
  val thumbnailPath: String?,
  val minithumbnailData: String?,
) {
  val metadataText: String?
    get() = listOfNotNull(fileSizeText, resolutionText).takeIf { it.isNotEmpty() }?.joinToString(" • ")
}

internal const val GALLERY_LANDSCAPE_BREAKPOINT_DP = 600
internal const val GALLERY_TABLET_BREAKPOINT_DP = 840

internal fun galleryColumnCount(widthDp: Int): Int = when {
  widthDp >= GALLERY_TABLET_BREAKPOINT_DP -> 4
  widthDp >= GALLERY_LANDSCAPE_BREAKPOINT_DP -> 3
  else -> 2
}

internal fun galleryItemUiModel(
  entity: SavedMediaEntity,
  accountIdentity: AccountSessionIdentity?,
  locale: Locale = Locale.getDefault(),
  localFileProbe: (String) -> GalleryLocalFileObservation = ::probeGalleryLocalFile,
): GalleryItemUiModel {
  val mediaType = if (entity.mediaType == "VIDEO") GalleryItemMediaType.VIDEO else GalleryItemMediaType.IMAGE
  val customName = entity.stableDisplayName.trim()
  val usesFallbackName = customName.isEmpty()
  val displayName = customName.ifEmpty { mediaType.name }
  val expectedSize = entity.expectedSizeBytes.takeIf { it > 0L }
  val thumbnailIdentity = entity.thumbnailStableFileIdentity
    ?: entity.thumbnailFileId?.let { "tdlib-file:$it" }

  return GalleryItemUiModel(
    stableKey = "media:${entity.accountId}:${entity.databaseGeneration}:${entity.chatId}:${entity.messageId}",
    source = entity,
    mediaType = mediaType,
    displayName = displayName,
    usesFallbackName = usesFallbackName,
    durationText = entity.durationSeconds.takeIf { mediaType == GalleryItemMediaType.VIDEO && it > 0 }
      ?.let(::formatGalleryDuration),
    fileSizeText = expectedSize?.let(::formatGalleryFileSize),
    resolutionText = if (entity.width > 0 && entity.height > 0) "${entity.width}×${entity.height}" else null,
    dateText = formatGalleryDate(entity.messageDateEpochSeconds, locale),
    availability = galleryFileAvailability(entity, accountIdentity, localFileProbe),
    thumbnailStableIdentity = thumbnailIdentity,
    thumbnailPath = null,
    minithumbnailData = entity.minithumbnailData,
  )
}

internal fun galleryFileAvailability(
  entity: SavedMediaEntity,
  accountIdentity: AccountSessionIdentity?,
  localFileProbe: (String) -> GalleryLocalFileObservation = ::probeGalleryLocalFile,
): GalleryFileAvailability {
  if (
    accountIdentity == null ||
      entity.accountId != accountIdentity.accountId ||
      entity.databaseGeneration != accountIdentity.databaseGeneration ||
      entity.deleted ||
      !entity.available ||
      entity.telegramFileId <= 0 ||
      entity.originalStableFileIdentity.isBlank()
  ) {
    return GalleryFileAvailability.UNAVAILABLE
  }

  val path = entity.localFilePath?.trim()?.takeIf { it.isNotEmpty() }
  val observation = path?.let(localFileProbe)
  if (observation?.exists == true && observation.readable) {
    val expectedSize = entity.expectedSizeBytes
    if (expectedSize > 0L && observation.sizeBytes >= expectedSize) {
      return GalleryFileAvailability.LOCAL_COMPLETE
    }
    return GalleryFileAvailability.PARTIAL
  }

  return GalleryFileAvailability.REMOTE_STREAMABLE
}

internal fun formatGalleryFileSize(bytes: Long): String? {
  if (bytes <= 0L) return null
  val units = arrayOf("B", "KB", "MB", "GB", "TB")
  var value = bytes.toDouble()
  var unitIndex = 0
  while (value >= 1024.0 && unitIndex < units.lastIndex) {
    value /= 1024.0
    unitIndex++
  }
  return if (unitIndex == 0) {
    "$bytes ${units[unitIndex]}"
  } else if (value % 1.0 == 0.0) {
    String.format(Locale.US, "%.0f %s", value, units[unitIndex])
  } else if (value >= 10.0) {
    String.format(Locale.US, "%.0f %s", value, units[unitIndex])
  } else {
    String.format(Locale.US, "%.1f %s", value, units[unitIndex])
  }
}

internal fun formatGalleryDuration(seconds: Int): String {
  val totalSeconds = seconds.coerceAtLeast(0)
  val hours = totalSeconds / 3_600
  val minutes = (totalSeconds % 3_600) / 60
  val remainingSeconds = totalSeconds % 60
  return if (hours > 0) {
    String.format(Locale.US, "%d:%02d:%02d", hours, minutes, remainingSeconds)
  } else {
    String.format(Locale.US, "%d:%02d", minutes, remainingSeconds)
  }
}

internal fun formatGalleryDate(epochSeconds: Long, locale: Locale): String? {
  if (epochSeconds <= 0L) return null
  return DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT)
    .withLocale(locale)
    .withZone(ZoneId.systemDefault())
    .format(Instant.ofEpochSecond(epochSeconds))
}

private fun probeGalleryLocalFile(path: String): GalleryLocalFileObservation {
  val file = File(path)
  return GalleryLocalFileObservation(
    exists = file.isFile,
    readable = file.isFile && file.canRead(),
    sizeBytes = file.takeIf { it.isFile && it.canRead() }?.length() ?: 0L,
  )
}
