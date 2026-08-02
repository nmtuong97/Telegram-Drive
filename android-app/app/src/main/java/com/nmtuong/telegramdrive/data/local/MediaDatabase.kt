package com.nmtuong.telegramdrive.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
  entities = [SavedMediaEntity::class, CachedFileEntity::class, SyncStateEntity::class],
  version = 1,
  exportSchema = false,
)
abstract class MediaDatabase : RoomDatabase() {
  abstract fun savedMediaDao(): SavedMediaDao
  abstract fun cachedFileDao(): CachedFileDao
  abstract fun syncStateDao(): SyncStateDao

  companion object {
    fun create(context: Context, name: String = "phase3-media.db"): MediaDatabase {
      val noBackupPath = context.noBackupFilesDir.resolve(name).absolutePath
      return Room.databaseBuilder(context.applicationContext, MediaDatabase::class.java, noBackupPath)
        .build()
    }
  }
}
