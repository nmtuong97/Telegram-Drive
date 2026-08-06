package com.nmtuong.telegramdrive.feature.gallery

import com.nmtuong.telegramdrive.data.local.SavedMediaEntity
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

sealed interface GalleryGridItem {
  val stableKey: String

  data class MonthHeader(val month: String) : GalleryGridItem {
    override val stableKey: String = "month:$month"
  }

  data class Media(val entity: SavedMediaEntity) : GalleryGridItem {
    override val stableKey: String = "media:${entity.chatId}:${entity.messageId}"
  }
}

internal fun galleryMonthKey(entity: SavedMediaEntity): String {
  if (entity.messageDateEpochSeconds <= 0L) return "Unknown date"
  return Instant.ofEpochSecond(entity.messageDateEpochSeconds)
    .atZone(ZoneId.systemDefault())
    .format(galleryMonthFormatter)
}

internal fun monthHeaderFor(
  before: GalleryGridItem.Media?,
  after: GalleryGridItem.Media?,
): GalleryGridItem.MonthHeader? {
  after ?: return null
  val month = galleryMonthKey(after.entity)
  return if (before == null || galleryMonthKey(before.entity) != month) {
    GalleryGridItem.MonthHeader(month)
  } else {
    null
  }
}

private val galleryMonthFormatter = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault())
