package com.nmtuong.telegramdrive.data.local

import androidx.room.Entity
import androidx.room.Index

enum class SavedMediaType { IMAGE, VIDEO }

enum class CachedFileType { THUMBNAIL, IMAGE_ORIGINAL, VIDEO_PARTIAL, VIDEO_COMPLETE }

enum class CachedFileState { NONE, PARTIAL, COMPLETE }

enum class MediaSyncPhase { IDLE, DISCOVERING_HEAD, BACKFILLING, CATCHING_UP, COMPLETED, ERROR }

@Entity(
  tableName = "saved_media",
  primaryKeys = ["accountId", "databaseGeneration", "chatId", "messageId"],
  indices = [
    Index(value = ["accountId", "databaseGeneration", "messageDateEpochSeconds", "messageId"]),
    Index(value = ["accountId", "databaseGeneration", "mediaType"]),
    Index(value = ["accountId", "databaseGeneration", "originalStableFileIdentity"]),
  ],
)
data class SavedMediaEntity(
  val accountId: Long,
  val databaseGeneration: Long,
  val chatId: Long,
  val messageId: Long,
  val mediaType: String,
  val messageDateEpochSeconds: Long,
  val caption: String,
  val stableDisplayName: String,
  val mimeType: String?,
  val width: Int,
  val height: Int,
  val durationSeconds: Int,
  val telegramFileId: Int,
  val originalStableFileIdentity: String,
  val thumbnailFileId: Int?,
  val thumbnailStableFileIdentity: String?,
  val minithumbnailData: String?,
  val minithumbnailWidth: Int,
  val minithumbnailHeight: Int,
  val localFilePath: String?,
  val deleted: Boolean = false,
  val available: Boolean = true,
  val lastReconciledAtEpochMillis: Long = 0L,
)

@Entity(
  tableName = "cached_file",
  primaryKeys = ["accountId", "databaseGeneration", "stableFileIdentity"],
  indices = [
    Index(value = ["accountId", "databaseGeneration", "lastAccessedAtEpochMillis"]),
    Index(value = ["accountId", "databaseGeneration", "observedState"]),
  ],
)
data class CachedFileEntity(
  val accountId: Long,
  val databaseGeneration: Long,
  val stableFileIdentity: String,
  val tdlibFileId: Int,
  val localPath: String?,
  val fileType: String,
  val observedSizeBytes: Long,
  val lastAccessedAtEpochMillis: Long,
  val observedState: String,
)

@Entity(tableName = "sync_state", primaryKeys = ["accountId", "databaseGeneration", "chatId"])
data class SyncStateEntity(
  val accountId: Long,
  val databaseGeneration: Long,
  val chatId: Long,
  val phase: String,
  val backfillCursor: Long?,
  val headWatermark: Long?,
  val lastCheckpointAtEpochMillis: Long?,
  val lastSuccessfulCatchUpHead: Long?,
  val lastError: String?,
  val retryCount: Int,
  val lastAttemptAtEpochMillis: Long?,
  val lastSuccessfulCatchUpAtEpochMillis: Long? = null,
)
