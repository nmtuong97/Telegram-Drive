package com.nmtuong.telegramdrive.data.local

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SavedMediaDao {
  @Query(
    """
    SELECT * FROM saved_media
    WHERE accountId = :accountId
      AND databaseGeneration = :databaseGeneration
      AND deleted = 0
      AND available = 1
      AND (:search = '' OR lower(stableDisplayName) LIKE '%' || lower(:search) || '%' OR lower(caption) LIKE '%' || lower(:search) || '%')
      AND (:mediaType = '' OR mediaType = :mediaType)
      AND (:localOnly = 0 OR localFilePath IS NOT NULL)
    ORDER BY
      CASE WHEN :newestFirst = 1 THEN messageDateEpochSeconds END DESC,
      CASE WHEN :newestFirst = 0 THEN messageDateEpochSeconds END ASC,
      CASE WHEN :newestFirst = 1 THEN messageId END DESC,
      CASE WHEN :newestFirst = 0 THEN messageId END ASC
    """,
  )
  fun pagingSource(
    accountId: Long,
    databaseGeneration: Long,
    search: String,
    mediaType: String,
    localOnly: Int,
    newestFirst: Int,
  ): PagingSource<Int, SavedMediaEntity>

  @Query(
    "SELECT * FROM saved_media WHERE accountId = :accountId AND databaseGeneration = :databaseGeneration AND chatId = :chatId AND messageId = :messageId LIMIT 1",
  )
  suspend fun find(accountId: Long, databaseGeneration: Long, chatId: Long, messageId: Long): SavedMediaEntity?

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsert(entity: SavedMediaEntity)

  @Query(
    "UPDATE saved_media SET deleted = 1, available = 0, localFilePath = NULL, lastReconciledAtEpochMillis = :now WHERE accountId = :accountId AND databaseGeneration = :databaseGeneration AND chatId = :chatId AND messageId = :messageId",
  )
  suspend fun markDeleted(accountId: Long, databaseGeneration: Long, chatId: Long, messageId: Long, now: Long)

  @Query(
    "UPDATE saved_media SET localFilePath = :path, available = :available, lastReconciledAtEpochMillis = :now WHERE accountId = :accountId AND databaseGeneration = :databaseGeneration AND chatId = :chatId AND messageId = :messageId",
  )
  suspend fun updateLocalState(accountId: Long, databaseGeneration: Long, chatId: Long, messageId: Long, path: String?, available: Boolean, now: Long)

  @Query("DELETE FROM saved_media WHERE accountId = :accountId AND databaseGeneration = :databaseGeneration")
  suspend fun deleteAccount(accountId: Long, databaseGeneration: Long)

  @Query(
    "UPDATE saved_media SET localFilePath = NULL, available = 1, lastReconciledAtEpochMillis = :now WHERE accountId = :accountId AND databaseGeneration = :databaseGeneration AND originalStableFileIdentity = :stableIdentity",
  )
  suspend fun clearLocalPathForStableFile(accountId: Long, databaseGeneration: Long, stableIdentity: String, now: Long)
}

@Dao
interface CachedFileDao {
  @Query("SELECT * FROM cached_file WHERE accountId = :accountId AND databaseGeneration = :databaseGeneration AND stableFileIdentity = :identity LIMIT 1")
  suspend fun find(accountId: Long, databaseGeneration: Long, identity: String): CachedFileEntity?

  @Query("SELECT * FROM cached_file WHERE accountId = :accountId AND databaseGeneration = :databaseGeneration")
  suspend fun list(accountId: Long, databaseGeneration: Long): List<CachedFileEntity>

  @Query("SELECT * FROM cached_file WHERE accountId = :accountId AND databaseGeneration = :databaseGeneration AND fileType = :fileType AND localPath IS NOT NULL ORDER BY lastAccessedAtEpochMillis ASC")
  suspend fun listWithLocalPathByType(accountId: Long, databaseGeneration: Long, fileType: String): List<CachedFileEntity>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsert(entity: CachedFileEntity)

  @Query("DELETE FROM cached_file WHERE accountId = :accountId AND databaseGeneration = :databaseGeneration")
  suspend fun deleteAccount(accountId: Long, databaseGeneration: Long)
}

@Dao
interface SyncStateDao {
  @Query("SELECT * FROM sync_state WHERE accountId = :accountId AND databaseGeneration = :databaseGeneration AND chatId = :chatId LIMIT 1")
  suspend fun find(accountId: Long, databaseGeneration: Long, chatId: Long): SyncStateEntity?

  @Query("SELECT * FROM sync_state WHERE accountId = :accountId AND databaseGeneration = :databaseGeneration AND chatId = :chatId LIMIT 1")
  fun observe(accountId: Long, databaseGeneration: Long, chatId: Long): Flow<SyncStateEntity?>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsert(entity: SyncStateEntity)

  @Query("DELETE FROM sync_state WHERE accountId = :accountId AND databaseGeneration = :databaseGeneration")
  suspend fun deleteAccount(accountId: Long, databaseGeneration: Long)
}
