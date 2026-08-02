package com.nmtuong.telegramdrive.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.migration.Migration
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
  entities = [SavedMediaEntity::class, CachedFileEntity::class, SyncStateEntity::class],
  version = 2,
  exportSchema = true,
)
abstract class MediaDatabase : RoomDatabase() {
  abstract fun savedMediaDao(): SavedMediaDao
  abstract fun cachedFileDao(): CachedFileDao
  abstract fun syncStateDao(): SyncStateDao

  companion object {
    fun create(context: Context, name: String = "phase3-media.db"): MediaDatabase {
      val noBackupPath = context.noBackupFilesDir.resolve(name).absolutePath
      return Room.databaseBuilder(context.applicationContext, MediaDatabase::class.java, noBackupPath)
        .addMigrations(MIGRATION_1_2)
        .build()
    }

    val MIGRATION_1_2: Migration = object : Migration(1, 2) {
      override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
          "ALTER TABLE sync_state ADD COLUMN lastSuccessfulCatchUpAtEpochMillis INTEGER",
        )
      }
    }
  }
}
