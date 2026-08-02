package com.nmtuong.telegramdrive.data

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.room.withTransaction
import com.nmtuong.telegramdrive.data.local.CachedFileEntity
import com.nmtuong.telegramdrive.data.local.CachedFileState
import com.nmtuong.telegramdrive.data.local.CachedFileType
import com.nmtuong.telegramdrive.data.local.MediaDatabase
import com.nmtuong.telegramdrive.data.local.MediaSyncPhase
import com.nmtuong.telegramdrive.data.local.SavedMediaEntity
import com.nmtuong.telegramdrive.data.local.SavedMediaType
import com.nmtuong.telegramdrive.data.local.SyncStateEntity
import com.nmtuong.telegramdrive.domain.AccountSessionIdentity
import com.nmtuong.telegramdrive.domain.MediaItem
import com.nmtuong.telegramdrive.domain.MediaKind
import com.nmtuong.telegramdrive.domain.SavedMediaGateway
import com.nmtuong.telegramdrive.domain.SavedMessageUpdate
import java.io.Closeable
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class GalleryMediaFilter { ALL, IMAGE, VIDEO }

data class GalleryQuery(
  val search: String = "",
  val mediaFilter: GalleryMediaFilter = GalleryMediaFilter.ALL,
  val localOnly: Boolean = false,
  val newestFirst: Boolean = true,
)

sealed interface SavedMediaSyncResult {
  data object Completed : SavedMediaSyncResult
  data class Failed(val message: String, val retryable: Boolean = true) : SavedMediaSyncResult
}

/**
 * Room-backed Saved Messages index. TDLib remains the source of truth; Room is a
 * restart-safe derived index used by the gallery.
 */
class SavedMediaRepository(
  private val database: MediaDatabase,
  private val gateway: SavedMediaGateway,
  private val identityProvider: AccountSessionIdentityProvider,
  private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : Closeable {
  private val scope = CoroutineScope(SupervisorJob() + dispatcher)
  private val activeChatId = MutableStateFlow<Long?>(null)
  private val updateJob = MutableStateFlow<Job?>(null)
  private val syncMutex = Mutex()
  private val accountMutationMutex = Mutex()
  private val accountCancellationEpoch = AtomicLong(0L)
  private val activeSyncJob = AtomicReference<Job?>(null)
  private var reconciliationJob: Job? = null
  private val _syncResult = MutableStateFlow<SavedMediaSyncResult?>(null)
  val syncResult: Flow<SavedMediaSyncResult?> = _syncResult.asStateFlow()

  fun start() {
    if (updateJob.value?.isActive == true) return
    reconciliationJob = scope.launch {
      identityProvider.currentIdentity.collect { identity ->
        activeChatId.value = null
        if (identity != null) reconcileAccount(identity)
      }
    }
    updateJob.value = scope.launch {
      gateway.savedMessageUpdates.collect { update -> applyUpdate(update) }
    }
  }

  @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
  fun paging(query: GalleryQuery): Flow<PagingData<SavedMediaEntity>> =
    identityProvider.currentIdentity.flatMapLatest { identity ->
      if (identity == null) flowOf(PagingData.empty()) else pagingForIdentity(identity, query)
    }

  private fun pagingForIdentity(
    identity: AccountSessionIdentity,
    query: GalleryQuery,
  ): Flow<PagingData<SavedMediaEntity>> {
    val mediaType = when (query.mediaFilter) {
      GalleryMediaFilter.ALL -> ""
      GalleryMediaFilter.IMAGE -> SavedMediaType.IMAGE.name
      GalleryMediaFilter.VIDEO -> SavedMediaType.VIDEO.name
    }
    return Pager(
      config = PagingConfig(pageSize = 50, prefetchDistance = 100, enablePlaceholders = true),
      pagingSourceFactory = {
        database.savedMediaDao().pagingSource(
          accountId = identity.accountId,
          databaseGeneration = identity.databaseGeneration,
          search = query.search.trim(),
          mediaType = mediaType,
          localOnly = if (query.localOnly) 1 else 0,
          newestFirst = if (query.newestFirst) 1 else 0,
        )
      },
    ).flow
  }

  fun observeSyncState(chatId: Long): Flow<SyncStateEntity?> {
    val identity = identityProvider.currentIdentity.value
      ?: return kotlinx.coroutines.flow.flowOf(null)
    return database.syncStateDao().observe(identity.accountId, identity.databaseGeneration, chatId)
  }

  @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
  fun observeCurrentSyncState(): Flow<SyncStateEntity?> =
    identityProvider.currentIdentity.flatMapLatest { identity ->
      activeChatId.flatMapLatest { chatId ->
        if (identity == null || chatId == null) {
          flowOf(null)
        } else {
          database.syncStateDao().observe(identity.accountId, identity.databaseGeneration, chatId)
        }
      }
    }

  suspend fun currentChatId(): Long? = gateway.getSavedMessagesChatId()

  suspend fun syncSavedMessages(): SavedMediaSyncResult = syncMutex.withLock {
    val callerJob = currentCoroutineContext()[Job]
    activeSyncJob.set(callerJob)
    try {
      syncSavedMessagesInternal()
    } finally {
      activeSyncJob.compareAndSet(callerJob, null)
    }
  }

  /** Cancels the scanner before logout/reset so it cannot repopulate old-generation rows. */
  fun cancelCurrentAccountWork() {
    activeChatId.value = null
    accountCancellationEpoch.incrementAndGet()
    activeSyncJob.get()?.cancel()
  }

  private suspend fun syncSavedMessagesInternal(): SavedMediaSyncResult = withContext(dispatcher) {
    val identity = identityProvider.currentIdentity.value
      ?: return@withContext SavedMediaSyncResult.Failed("Telegram account is not ready", retryable = false)
    val chatId = gateway.getSavedMessagesChatId()
      ?: return@withContext SavedMediaSyncResult.Failed("Saved Messages is not available")
    activeChatId.value = chatId
    reconcileAccount(identity)

    val existing = database.syncStateDao().find(identity.accountId, identity.databaseGeneration, chatId)
    val canResume = existing != null && existing.headWatermark != null &&
      existing.phase in setOf(
        MediaSyncPhase.DISCOVERING_HEAD.name,
        MediaSyncPhase.BACKFILLING.name,
        MediaSyncPhase.CATCHING_UP.name,
    )
    val state = if (canResume) {
      checkNotNull(existing)
    } else {
      val head = gateway.getSavedMessagesHead(chatId)
      val initial = SyncStateEntity(
        accountId = identity.accountId,
        databaseGeneration = identity.databaseGeneration,
        chatId = chatId,
        phase = MediaSyncPhase.DISCOVERING_HEAD.name,
        backfillCursor = null,
        headWatermark = head,
        lastCheckpointAtEpochMillis = null,
        lastSuccessfulCatchUpHead = null,
        lastError = null,
        retryCount = 0,
        lastAttemptAtEpochMillis = System.currentTimeMillis(),
      )
      database.syncStateDao().upsert(initial)
      initial
    }

    try {
      if (state.headWatermark == null) {
        database.syncStateDao().upsert(state.copy(phase = MediaSyncPhase.COMPLETED.name, lastSuccessfulCatchUpHead = null))
        _syncResult.value = SavedMediaSyncResult.Completed
        return@withContext SavedMediaSyncResult.Completed
      }

      val backfillState = state.copy(phase = MediaSyncPhase.BACKFILLING.name, lastAttemptAtEpochMillis = System.currentTimeMillis())
      if (state.phase != MediaSyncPhase.CATCHING_UP.name) {
        database.syncStateDao().upsert(backfillState)
        runBackfill(identity, chatId, backfillState, state.backfillCursor ?: 0L)
      }

      val catchupCursor = if (state.phase == MediaSyncPhase.CATCHING_UP.name) {
        state.backfillCursor ?: 0L
      } else {
        0L
      }
      val catchupState = database.syncStateDao().find(identity.accountId, identity.databaseGeneration, chatId)
        ?.copy(phase = MediaSyncPhase.CATCHING_UP.name, backfillCursor = catchupCursor, lastError = null)
        ?: backfillState.copy(phase = MediaSyncPhase.CATCHING_UP.name, backfillCursor = catchupCursor)
      database.syncStateDao().upsert(catchupState)
      runCatchUp(identity, chatId, catchupState)

      _syncResult.value = SavedMediaSyncResult.Completed
      SavedMediaSyncResult.Completed
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (error: Exception) {
      val safeMessage = error.message?.takeIf { it.isNotBlank() } ?: "Saved Messages sync failed"
      val failed = database.syncStateDao().find(identity.accountId, identity.databaseGeneration, chatId)
        ?: state
      database.syncStateDao().upsert(
        failed.copy(
          phase = MediaSyncPhase.ERROR.name,
          lastError = safeMessage,
          retryCount = failed.retryCount + 1,
          lastAttemptAtEpochMillis = System.currentTimeMillis(),
        ),
      )
      val result = SavedMediaSyncResult.Failed(safeMessage)
      _syncResult.value = result
      result
    }
  }

  private suspend fun runBackfill(
    identity: AccountSessionIdentity,
    chatId: Long,
    state: SyncStateEntity,
    initialCursor: Long,
  ) {
    var cursor = initialCursor
    while (true) {
      ensureCurrentIdentity(identity)
      val page = gateway.loadHistoryPage(chatId, cursor, PAGE_SIZE)
      page.error?.let { throw IllegalStateException(it) }
      val nextCursor = page.rawLastMessageId
      val checkpoint = state.copy(
        phase = MediaSyncPhase.BACKFILLING.name,
        backfillCursor = nextCursor,
        lastCheckpointAtEpochMillis = System.currentTimeMillis(),
      )
      database.withTransaction {
        ensureCurrentIdentity(identity)
        upsertPage(identity, page.items)
        database.syncStateDao().upsert(checkpoint)
      }
      if (page.endOfHistory || nextCursor == null || nextCursor == cursor) return
      cursor = nextCursor
    }
  }

  private suspend fun runCatchUp(
    identity: AccountSessionIdentity,
    chatId: Long,
    state: SyncStateEntity,
  ) {
    val watermark = state.headWatermark ?: return
    var cursor = state.backfillCursor ?: 0L
    var pass = 0
    while (pass < MAX_CATCH_UP_PASSES) {
      while (true) {
        ensureCurrentIdentity(identity)
        val page = gateway.loadHistoryPage(chatId, cursor, PAGE_SIZE)
        page.error?.let { throw IllegalStateException(it) }
        val relevant = page.items.filter { it.id > watermark }
        val nextCursor = page.rawLastMessageId
        val checkpoint = state.copy(
          phase = MediaSyncPhase.CATCHING_UP.name,
          backfillCursor = nextCursor,
          lastCheckpointAtEpochMillis = System.currentTimeMillis(),
        )
        database.withTransaction {
          ensureCurrentIdentity(identity)
          upsertPage(identity, relevant)
          database.syncStateDao().upsert(checkpoint)
        }
        if (page.endOfHistory || nextCursor == null || nextCursor == cursor || nextCursor <= watermark) break
        cursor = nextCursor
      }

      val latestHead = gateway.getSavedMessagesHead(chatId)
      if (latestHead == null || latestHead <= watermark) {
        database.syncStateDao().upsert(
          state.copy(
            phase = MediaSyncPhase.COMPLETED.name,
            backfillCursor = cursor,
            lastSuccessfulCatchUpHead = latestHead ?: watermark,
            lastCheckpointAtEpochMillis = System.currentTimeMillis(),
            lastError = null,
          ),
        )
        return
      }
      // Keep the original watermark and repeat a bounded pass; do not create a new
      // watermark that could hide the unprocessed catch-up interval.
      cursor = 0L
      pass++
    }
    throw IllegalStateException("Saved Messages changed continuously during catch-up")
  }

  private suspend fun upsertPage(identity: AccountSessionIdentity, items: List<MediaItem>) {
    items.filter(::isIndexedMedia).forEach { upsertMedia(identity, it) }
  }

  private suspend fun applyUpdate(update: SavedMessageUpdate) {
    val identity = identityProvider.currentIdentity.value ?: return
    val chatId = activeChatId.value ?: return
    val updateChatId = when (update) {
      is SavedMessageUpdate.Upsert -> update.chatId
      is SavedMessageUpdate.Changed -> update.chatId
      is SavedMessageUpdate.Deleted -> update.chatId
    }
    if (updateChatId != chatId) return
    when (update) {
      is SavedMessageUpdate.Upsert -> if (isIndexedMedia(update.message)) {
        upsertMedia(identity, update.message)
      }
      is SavedMessageUpdate.Changed -> {
        if (update.message != null && isIndexedMedia(update.message)) {
          upsertMedia(identity, update.message)
        } else {
          markDeleted(identity, update.chatId, update.messageId)
        }
      }
      is SavedMessageUpdate.Deleted -> markDeleted(identity, update.chatId, update.messageId)
    }
  }

  private suspend fun upsertMedia(identity: AccountSessionIdentity, item: MediaItem) {
    accountMutationMutex.withLock {
      ensureCurrentIdentity(identity)
      val now = System.currentTimeMillis()
      val previous = database.savedMediaDao().find(identity.accountId, identity.databaseGeneration, item.sourceId, item.id)
      val previousPath = previous
        ?.takeIf { it.originalStableFileIdentity == item.stableFileIdentity }
        ?.localFilePath
      database.withTransaction {
        database.savedMediaDao().upsert(item.toEntity(identity, previousPath))
        val existingOriginal = database.cachedFileDao().find(
          identity.accountId,
          identity.databaseGeneration,
          item.stableFileIdentity,
        )
        database.cachedFileDao().upsert(item.toCachedFile(identity, now, existingOriginal))
        item.thumbnailStableFileIdentity?.let { thumbnailIdentity ->
          val existingThumbnail = database.cachedFileDao().find(
            identity.accountId,
            identity.databaseGeneration,
            thumbnailIdentity,
          )
          database.cachedFileDao().upsert(
            (existingThumbnail ?: CachedFileEntity(
              accountId = identity.accountId,
              databaseGeneration = identity.databaseGeneration,
              stableFileIdentity = thumbnailIdentity,
              tdlibFileId = item.thumbnailFileId ?: 0,
              localPath = null,
              fileType = CachedFileType.THUMBNAIL.name,
              observedSizeBytes = 0L,
              lastAccessedAtEpochMillis = now,
              observedState = CachedFileState.NONE.name,
            )).copy(
              tdlibFileId = item.thumbnailFileId ?: existingThumbnail?.tdlibFileId ?: 0,
              fileType = CachedFileType.THUMBNAIL.name,
              lastAccessedAtEpochMillis = now,
            ),
          )
        }
      }
      if (previous != null && previous.originalStableFileIdentity != item.stableFileIdentity) {
        cleanupOrphanCache(identity, previous.originalStableFileIdentity)
      }
      if (previous?.thumbnailStableFileIdentity != null && previous.thumbnailStableFileIdentity != item.thumbnailStableFileIdentity) {
        cleanupOrphanCache(identity, previous.thumbnailStableFileIdentity)
      }
    }
  }

  private suspend fun markDeleted(identity: AccountSessionIdentity, chatId: Long, messageId: Long) {
    accountMutationMutex.withLock {
      ensureCurrentIdentity(identity)
      val previous = database.savedMediaDao().find(identity.accountId, identity.databaseGeneration, chatId, messageId)
      database.savedMediaDao().markDeleted(
        identity.accountId,
        identity.databaseGeneration,
        chatId,
        messageId,
        System.currentTimeMillis(),
      )
      previous?.let { media ->
        cleanupOrphanCache(identity, media.originalStableFileIdentity)
        media.thumbnailStableFileIdentity?.let { cleanupOrphanCache(identity, it) }
      }
    }
  }

  private suspend fun cleanupOrphanCache(identity: AccountSessionIdentity, stableIdentity: String) {
    if (database.savedMediaDao().countActiveReferences(identity.accountId, identity.databaseGeneration, stableIdentity) != 0L) return
    database.cachedFileDao().find(identity.accountId, identity.databaseGeneration, stableIdentity)?.let { cached ->
      if (cached.localPath != null) gateway.deleteTemporaryFile(cached.tdlibFileId)
      database.cachedFileDao().delete(identity.accountId, identity.databaseGeneration, stableIdentity)
    }
  }

  suspend fun clearAccount(identity: AccountSessionIdentity) {
    if (identityProvider.currentIdentity.value == identity) cancelCurrentAccountWork()
    accountMutationMutex.withLock {
      database.cachedFileDao().list(identity.accountId, identity.databaseGeneration).forEach { cached ->
        if (cached.localPath != null) gateway.deleteTemporaryFile(cached.tdlibFileId)
      }
      database.withTransaction {
        database.savedMediaDao().deleteAccount(identity.accountId, identity.databaseGeneration)
        database.cachedFileDao().deleteAccount(identity.accountId, identity.databaseGeneration)
        database.syncStateDao().deleteAccount(identity.accountId, identity.databaseGeneration)
      }
    }
  }

  fun clearCurrentAccount() {
    cancelCurrentAccountWork()
    val identity = identityProvider.currentIdentity.value ?: return
    scope.launch { clearAccount(identity) }
  }

  override fun close() {
    updateJob.value?.cancel()
    reconciliationJob?.cancel()
    scope.cancel()
    database.close()
  }

  /** Reconciles persisted hints against TDLib state and readable filesystem bytes. */
  private suspend fun reconcileAccount(identity: AccountSessionIdentity) {
    val epoch = accountCancellationEpoch.get()
    database.cachedFileDao().list(identity.accountId, identity.databaseGeneration).forEach { cached ->
      if (identityProvider.currentIdentity.value != identity || accountCancellationEpoch.get() != epoch) return
      val snapshot = gateway.getFileSnapshot(cached.tdlibFileId)
      val path = snapshot?.localPath ?: cached.localPath
      val file = path?.let(::File)
      val actualSize = file?.takeIf { it.isFile && it.canRead() }?.length() ?: 0L
      val stableIdentityMatches = snapshot?.stableFileIdentity == null ||
        snapshot.stableFileIdentity == cached.stableFileIdentity
      val completeSizeMatches = snapshot?.let {
        !it.isDownloadingCompleted || it.expectedSizeBytes <= 0L || actualSize >= it.expectedSizeBytes
      } == true
      val readable = snapshot?.isReadable == true && stableIdentityMatches && completeSizeMatches && actualSize > 0L
      if (!readable) {
        accountMutationMutex.withLock {
          if (identityProvider.currentIdentity.value != identity || accountCancellationEpoch.get() != epoch) return
          database.withTransaction {
            database.cachedFileDao().upsert(
              cached.copy(
                localPath = null,
                observedSizeBytes = 0L,
                observedState = CachedFileState.NONE.name,
                lastAccessedAtEpochMillis = System.currentTimeMillis(),
              ),
            )
            database.savedMediaDao().clearLocalPathForStableFile(
              identity.accountId,
              identity.databaseGeneration,
              cached.stableFileIdentity,
              System.currentTimeMillis(),
            )
          }
        }
      } else {
        accountMutationMutex.withLock {
          if (identityProvider.currentIdentity.value != identity || accountCancellationEpoch.get() != epoch) return
          val state = if (snapshot.isDownloadingCompleted) CachedFileState.COMPLETE else CachedFileState.PARTIAL
          database.cachedFileDao().upsert(
            cached.copy(
              tdlibFileId = snapshot.fileId,
              localPath = snapshot.localPath,
              observedSizeBytes = actualSize,
              observedState = state.name,
              lastAccessedAtEpochMillis = System.currentTimeMillis(),
            ),
          )
        }
      }
    }
  }

  private fun isIndexedMedia(item: MediaItem): Boolean = item.kind == MediaKind.IMAGE || item.kind == MediaKind.VIDEO

  private fun MediaItem.toEntity(identity: AccountSessionIdentity, previousPath: String?): SavedMediaEntity =
    SavedMediaEntity(
      accountId = identity.accountId,
      databaseGeneration = identity.databaseGeneration,
      chatId = sourceId,
      messageId = id,
      mediaType = if (kind == MediaKind.IMAGE) SavedMediaType.IMAGE.name else SavedMediaType.VIDEO.name,
      messageDateEpochSeconds = dateEpochSeconds,
      caption = caption.orEmpty(),
      stableDisplayName = name,
      mimeType = mimeType,
      width = width,
      height = height,
      durationSeconds = durationSeconds,
      telegramFileId = fileId,
      originalStableFileIdentity = stableFileIdentity,
      thumbnailFileId = thumbnailFileId,
      thumbnailStableFileIdentity = thumbnailStableFileIdentity,
      minithumbnailData = minithumbnailData,
      minithumbnailWidth = minithumbnailWidth,
      minithumbnailHeight = minithumbnailHeight,
      localFilePath = localPath ?: previousPath,
      deleted = false,
      available = true,
      lastReconciledAtEpochMillis = System.currentTimeMillis(),
    )

  private fun MediaItem.toCachedFile(
    identity: AccountSessionIdentity,
    now: Long,
    existing: CachedFileEntity?,
  ): CachedFileEntity {
    val actualSize = localPath?.let(::File)?.takeIf { it.isFile && it.canRead() }?.length()
    return (existing ?: CachedFileEntity(
      accountId = identity.accountId,
      databaseGeneration = identity.databaseGeneration,
      stableFileIdentity = stableFileIdentity,
      tdlibFileId = fileId,
      localPath = localPath,
      fileType = if (kind == MediaKind.IMAGE) CachedFileType.IMAGE_ORIGINAL.name
      else if (localPath != null) CachedFileType.VIDEO_COMPLETE.name else CachedFileType.VIDEO_PARTIAL.name,
      observedSizeBytes = sizeBytes,
      lastAccessedAtEpochMillis = now,
      observedState = if (localPath != null) CachedFileState.COMPLETE.name else CachedFileState.NONE.name,
    )).copy(
      tdlibFileId = fileId.takeIf { it != 0 } ?: existing?.tdlibFileId ?: 0,
      localPath = localPath ?: existing?.localPath,
      fileType = when {
        existing?.localPath != null && localPath == null -> existing.fileType
        kind == MediaKind.IMAGE -> CachedFileType.IMAGE_ORIGINAL.name
        localPath != null -> CachedFileType.VIDEO_COMPLETE.name
        else -> CachedFileType.VIDEO_PARTIAL.name
      },
      observedSizeBytes = actualSize ?: existing?.observedSizeBytes ?: sizeBytes,
      lastAccessedAtEpochMillis = now,
      observedState = when {
        localPath != null -> CachedFileState.COMPLETE.name
        existing?.localPath != null -> existing.observedState
        else -> CachedFileState.NONE.name
      },
    )
  }

  private fun ensureCurrentIdentity(identity: AccountSessionIdentity) {
    if (identityProvider.currentIdentity.value != identity) {
      throw CancellationException("Telegram account session changed")
    }
  }

  private companion object {
    const val PAGE_SIZE = 100
    const val MAX_CATCH_UP_PASSES = 3
  }
}
