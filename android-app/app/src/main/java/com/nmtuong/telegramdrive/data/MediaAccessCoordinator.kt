package com.nmtuong.telegramdrive.data

import androidx.room.withTransaction
import com.nmtuong.telegramdrive.data.local.CachedFileEntity
import com.nmtuong.telegramdrive.data.local.CachedFileState
import com.nmtuong.telegramdrive.data.local.CachedFileType
import com.nmtuong.telegramdrive.data.local.MediaDatabase
import com.nmtuong.telegramdrive.data.local.SavedMediaEntity
import com.nmtuong.telegramdrive.domain.AccountSessionIdentity
import com.nmtuong.telegramdrive.domain.ActionResult
import com.nmtuong.telegramdrive.domain.TdLibFileSnapshot
import com.nmtuong.telegramdrive.data.video.TdLibVideoDataSource
import com.nmtuong.telegramdrive.data.video.VideoStreamingCoordinator
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.common.util.UnstableApi
import androidx.annotation.OptIn
import java.io.Closeable
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

internal fun thumbnailEvictionCandidates(
  cached: List<CachedFileEntity>,
  maxEntries: Int,
): List<CachedFileEntity> {
  val evictionCount = (cached.size - maxEntries).coerceAtLeast(0)
  return if (evictionCount == 0) emptyList() else cached.take(evictionCount)
}

sealed interface MediaOpenResult {
  data class Opened(val path: String) : MediaOpenResult
  data class Failed(val message: String) : MediaOpenResult
}

/**
 * Bounded, deduplicated access to thumbnails and original images/videos.
 * Room cache state is only a hint; every open reconciles TDLib and the filesystem.
 */
@OptIn(UnstableApi::class)
class MediaAccessCoordinator(
  private val database: MediaDatabase,
  private val gateway: com.nmtuong.telegramdrive.domain.SavedMediaGateway,
  private val identityProvider: AccountSessionIdentityProvider,
  dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : Closeable {
  private val scope = CoroutineScope(SupervisorJob() + dispatcher)
  private val thumbnailSemaphore = Semaphore(MAX_THUMBNAIL_CONCURRENCY)
  private val inFlight = ConcurrentHashMap<AccessKey, kotlinx.coroutines.Deferred<String?>>()
  private val activeVideoTransfers = mutableMapOf<AccessKey, ActiveVideoTransfer>()

  suspend fun ensureThumbnail(entity: SavedMediaEntity): String? {
    val fileId = entity.thumbnailFileId ?: return null
    val identity = identityProvider.currentIdentity.value ?: return null
    if (!entity.belongsTo(identity)) return null
    val stableIdentity = entity.thumbnailStableFileIdentity ?: "tdlib-file:$fileId"
    val key = AccessKey(identity, stableIdentity)
    return deduplicated(key) {
      thumbnailSemaphore.withPermit {
        val cached = database.cachedFileDao().find(identity.accountId, identity.databaseGeneration, stableIdentity)
        cached?.localPath?.let(::File)?.takeIf { it.isFile && it.canRead() }?.absolutePath?.let {
          touchCached(cached.copy(lastAccessedAtEpochMillis = System.currentTimeMillis()))
          evictThumbnailCache(identity)
          return@withPermit it
        }
        val snapshot = waitForCompleteFile(identity, fileId, stableIdentity, CachedFileType.THUMBNAIL)
        snapshot?.localPath?.takeIf { File(it).isFile && File(it).canRead() }?.also {
          evictThumbnailCache(identity)
        }
      }
    }?.takeIf { identityProvider.currentIdentity.value == identity }
  }

  suspend fun openOriginal(entity: SavedMediaEntity): MediaOpenResult {
    val identity = identityProvider.currentIdentity.value
      ?: return MediaOpenResult.Failed("Telegram account is not ready")
    if (!entity.belongsTo(identity)) return MediaOpenResult.Failed("Media belongs to another Telegram account")
    val stableIdentity = entity.originalStableFileIdentity
    return try {
      if (entity.mediaType == "VIDEO") return prepareVideo(entity, identity)
      deduplicated(AccessKey(identity, stableIdentity)) {
        val currentSnapshot = gateway.getFileSnapshot(entity.telegramFileId)
        val currentPath = currentSnapshot
          ?.takeIf { isCompleteReadable(it, stableIdentity) }
          ?.localPath
          ?.let(::File)
          ?.takeIf { it.isFile && it.canRead() }
          ?.absolutePath
        if (currentPath != null) return@deduplicated currentPath
        val snapshot = waitForCompleteFile(
          identity = identity,
          fileId = entity.telegramFileId,
          stableIdentity = stableIdentity,
          type = if (entity.mediaType == "IMAGE") CachedFileType.IMAGE_ORIGINAL else CachedFileType.VIDEO_COMPLETE,
        )
        snapshot?.localPath?.takeIf { File(it).isFile && File(it).canRead() }
      }?.takeIf { identityProvider.currentIdentity.value == identity }?.let { path ->
        database.withTransaction {
          database.savedMediaDao().updateLocalState(
            identity.accountId,
            identity.databaseGeneration,
            entity.chatId,
            entity.messageId,
            path,
            available = true,
            now = System.currentTimeMillis(),
          )
        }
        MediaOpenResult.Opened(path)
      } ?: MediaOpenResult.Failed("Telegram file is not readable after download")
    } catch (error: Exception) {
      MediaOpenResult.Failed(error.message?.takeIf { it.isNotBlank() } ?: "Media could not be opened")
    }
  }

  private suspend fun prepareVideo(entity: SavedMediaEntity, identity: AccountSessionIdentity): MediaOpenResult {
    if (identityProvider.currentIdentity.value != identity) return MediaOpenResult.Failed("Telegram account is not ready")
    val stableIdentity = entity.originalStableFileIdentity
    return try {
      val existing = gateway.getFileSnapshot(entity.telegramFileId)
      val partial = existing?.takeIf {
        isSnapshotForStableIdentity(it, stableIdentity) &&
          it.isReadable &&
          it.localPath != null &&
          it.downloadedPrefixSizeBytes >= INITIAL_VIDEO_BUFFER_BYTES
      }
        ?: run {
          val request = gateway.requestFileRange(entity.telegramFileId, 0L, INITIAL_VIDEO_BUFFER_BYTES, priority = 32)
          if (request != ActionResult.ACCEPTED) return MediaOpenResult.Failed("TDLib rejected the initial video buffer")
          gateway.getFileSnapshot(entity.telegramFileId)?.let { snapshot ->
            if (isSnapshotForStableIdentity(snapshot, stableIdentity) && snapshot.isReadable && snapshot.localPath != null &&
              (snapshot.isDownloadingCompleted || snapshot.downloadedPrefixSizeBytes >= INITIAL_VIDEO_BUFFER_BYTES)
            ) return@run snapshot
          }
          withTimeout(FILE_WAIT_TIMEOUT_MS) {
            gateway.fileUpdates
              .filter { snapshot ->
                snapshot.fileId == entity.telegramFileId &&
                  isSnapshotForStableIdentity(snapshot, stableIdentity) &&
                  snapshot.isReadable &&
                  snapshot.localPath != null &&
                  (snapshot.isDownloadingCompleted || snapshot.downloadedPrefixSizeBytes >= INITIAL_VIDEO_BUFFER_BYTES)
              }
              .first()
          }
        }
      if (identityProvider.currentIdentity.value != identity) return MediaOpenResult.Failed("Telegram account changed while preparing media")
      persistSnapshot(identity, partial, stableIdentity, CachedFileType.VIDEO_PARTIAL, CachedFileState.PARTIAL)
      MediaOpenResult.Opened(partial.localPath!!)
    } catch (error: Exception) {
      MediaOpenResult.Failed(error.message?.takeIf { it.isNotBlank() } ?: "Video initial buffer failed")
    }
  }

  fun videoDataSourceFactory(entity: SavedMediaEntity): DataSource.Factory = TdLibVideoDataSource.Factory(
    coordinatorFactory = { _: DataSpec ->
      val ownerIdentity = AccountSessionIdentity(entity.accountId, entity.databaseGeneration)
      check(identityProvider.currentIdentity.value == ownerIdentity) { "Telegram account changed while opening video" }
      val key = AccessKey(ownerIdentity, entity.originalStableFileIdentity)
      synchronized(activeVideoTransfers) {
        activeVideoTransfers.getOrPut(key) {
          ActiveVideoTransfer(
            coordinator = VideoStreamingCoordinator(
              gateway = gateway,
              fileId = entity.telegramFileId,
              stableFileIdentity = entity.originalStableFileIdentity,
              onClosed = { scope.launch { clearVideoCache(entity, ownerIdentity) } },
            ),
          )
        }.also { it.references++ }.coordinator
      }
    },
    releaseFactory = { _: DataSpec, _: VideoStreamingCoordinator ->
      { releaseVideoTransfer(AccessKey(entity.accountId, entity.databaseGeneration, entity.originalStableFileIdentity)) }
    },
  )

  private fun releaseVideoTransfer(key: AccessKey) {
    val transfer = synchronized(activeVideoTransfers) {
      val current = activeVideoTransfers[key] ?: return
      current.references--
      if (current.references <= 0) activeVideoTransfers.remove(key) else null
    }
    transfer?.coordinator?.close()
  }

  private suspend fun clearVideoCache(entity: SavedMediaEntity, identity: AccountSessionIdentity) {
    if (identityProvider.currentIdentity.value != identity) return
    database.withTransaction {
      database.savedMediaDao().clearLocalPathForStableFile(
        identity.accountId,
        identity.databaseGeneration,
        entity.originalStableFileIdentity,
        System.currentTimeMillis(),
      )
      database.cachedFileDao().find(identity.accountId, identity.databaseGeneration, entity.originalStableFileIdentity)?.let {
        database.cachedFileDao().upsert(it.copy(localPath = null, observedState = CachedFileState.NONE.name, fileType = CachedFileType.VIDEO_PARTIAL.name))
      }
    }
  }

  private suspend fun waitForCompleteFile(
    identity: AccountSessionIdentity,
    fileId: Int,
    stableIdentity: String,
    type: CachedFileType,
  ): TdLibFileSnapshot? {
    gateway.getFileSnapshot(fileId)?.let { snapshot ->
      if (isCompleteReadable(snapshot, stableIdentity)) {
        persistSnapshot(identity, snapshot, stableIdentity, type, CachedFileState.COMPLETE)
        return snapshot
      }
    }
    val requestResult = gateway.requestFileRange(fileId, offsetBytes = 0L, limitBytes = 0L, priority = 24)
    if (requestResult != ActionResult.ACCEPTED) return null
    gateway.getFileSnapshot(fileId)?.let { snapshot ->
      if (isCompleteReadable(snapshot, stableIdentity)) {
        persistSnapshot(identity, snapshot, stableIdentity, type, CachedFileState.COMPLETE)
        return snapshot
      }
    }
    val completed = withTimeout(FILE_WAIT_TIMEOUT_MS) {
      gateway.fileUpdates
        .filter { it.fileId == fileId && isCompleteReadable(it, stableIdentity) }
        .first()
    }
    persistSnapshot(identity, completed, stableIdentity, type, CachedFileState.COMPLETE)
    return completed
  }

  private fun isCompleteReadable(snapshot: TdLibFileSnapshot, stableIdentity: String): Boolean =
    snapshot.isDownloadingCompleted &&
      snapshot.isReadable &&
      (snapshot.stableFileIdentity == null || snapshot.stableFileIdentity == stableIdentity) &&
      snapshot.localPath?.let { path ->
        File(path).let { file ->
          file.isFile && file.canRead() &&
            file.length() > 0L &&
            (snapshot.expectedSizeBytes <= 0L || file.length() >= snapshot.expectedSizeBytes)
        }
      } == true

  private fun isSnapshotForStableIdentity(snapshot: TdLibFileSnapshot, stableIdentity: String): Boolean =
    snapshot.stableFileIdentity == null || snapshot.stableFileIdentity == stableIdentity

  private suspend fun persistSnapshot(
    identity: AccountSessionIdentity,
    snapshot: TdLibFileSnapshot,
    stableIdentity: String,
    type: CachedFileType,
    state: CachedFileState,
  ) {
    if (identityProvider.currentIdentity.value != identity) return
    database.cachedFileDao().upsert(
      CachedFileEntity(
        accountId = identity.accountId,
        databaseGeneration = identity.databaseGeneration,
        stableFileIdentity = stableIdentity,
        tdlibFileId = snapshot.fileId,
        localPath = snapshot.localPath,
        fileType = type.name,
        observedSizeBytes = snapshot.localPath?.let(::File)?.takeIf { it.isFile }?.length()
          ?: snapshot.downloadedSizeBytes,
        lastAccessedAtEpochMillis = System.currentTimeMillis(),
        observedState = state.name,
      ),
    )
  }

  private suspend fun touchCached(entity: CachedFileEntity) {
    database.cachedFileDao().upsert(entity)
  }

  private suspend fun evictThumbnailCache(identity: AccountSessionIdentity) {
    val cached = database.cachedFileDao().listWithLocalPathByType(
      identity.accountId,
      identity.databaseGeneration,
      CachedFileType.THUMBNAIL.name,
    )
    thumbnailEvictionCandidates(cached, MAX_THUMBNAIL_CACHE_ENTRIES).forEach { stale ->
      gateway.deleteTemporaryFile(stale.tdlibFileId)
      database.cachedFileDao().upsert(
        stale.copy(
          localPath = null,
          observedSizeBytes = 0L,
          observedState = CachedFileState.NONE.name,
        ),
      )
    }
  }

  /**
   * Stops account-scoped media work before logout/reset without destroying the
   * coordinator itself; the same container may be reused after a new login.
   */
  fun cancelForAccount() {
    scope.coroutineContext.cancelChildren()
    inFlight.values.forEach { it.cancel() }
    inFlight.clear()
    val transfers = synchronized(activeVideoTransfers) {
      val values = activeVideoTransfers.values.map { it.coordinator }
      activeVideoTransfers.clear()
      values
    }
    transfers.forEach(VideoStreamingCoordinator::close)
  }

  private suspend fun deduplicated(key: AccessKey, block: suspend () -> String?): String? {
    val deferred = synchronized(inFlight) {
      inFlight[key] ?: scope.async { block() }.also { inFlight[key] = it }
    }
    return try {
      deferred.await()
    } finally {
      if (deferred.isCompleted) inFlight.remove(key, deferred)
    }
  }

  fun cancel(fileId: Int): ActionResult = gateway.cancelFileRange(fileId)

  override fun close() {
    cancelForAccount()
    scope.cancel()
  }

  private data class ActiveVideoTransfer(
    val coordinator: VideoStreamingCoordinator,
    var references: Int = 0,
  )

  private data class AccessKey(
    val identity: AccountSessionIdentity,
    val stableFileIdentity: String,
  ) {
    constructor(accountId: Long, databaseGeneration: Long, stableFileIdentity: String) : this(
      AccountSessionIdentity(accountId, databaseGeneration),
      stableFileIdentity,
    )
  }

  private fun SavedMediaEntity.belongsTo(identity: AccountSessionIdentity): Boolean =
    accountId == identity.accountId && databaseGeneration == identity.databaseGeneration

  private companion object {
    const val MAX_THUMBNAIL_CONCURRENCY = 2
    const val MAX_THUMBNAIL_CACHE_ENTRIES = 200
    const val FILE_WAIT_TIMEOUT_MS = 30_000L
    const val INITIAL_VIDEO_BUFFER_BYTES = 512L * 1024L
  }
}
