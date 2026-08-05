package com.nmtuong.telegramdrive.domain

/**
 * Stable, account-scoped identity and metadata for one video playback session.
 * Local paths are candidates only; source selection must validate them again.
 */
data class VideoPlaybackRequest(
  val accountIdentity: AccountSessionIdentity,
  val stableFileIdentity: String,
  val telegramFileId: Int,
  val chatId: Long,
  val messageId: Long,
  val displayName: String,
  val durationSeconds: Long,
  val mimeType: String?,
  val expectedSizeBytes: Long?,
  val localPath: String?,
  val thumbnailPath: String?,
  val minithumbnailData: String?,
) {
  val playbackKey: String
    get() = "${accountIdentity.accountId}:${accountIdentity.databaseGeneration}:$stableFileIdentity"
}
