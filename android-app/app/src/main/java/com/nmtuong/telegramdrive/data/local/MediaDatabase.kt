package com.nmtuong.telegramdrive.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.migration.Migration
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
  entities = [SavedMediaEntity::class, CachedFileEntity::class, SyncStateEntity::class, PlaybackPositionEntity::class],
  version = 4,
  exportSchema = true,
)
abstract class MediaDatabase : RoomDatabase() {
  abstract fun savedMediaDao(): SavedMediaDao
  abstract fun cachedFileDao(): CachedFileDao
  abstract fun syncStateDao(): SyncStateDao
  abstract fun playbackPositionDao(): PlaybackPositionDao

  companion object {
    fun create(context: Context, name: String = "phase3-media.db"): MediaDatabase {
      val noBackupPath = context.noBackupFilesDir.resolve(name).absolutePath
      return Room.databaseBuilder(context.applicationContext, MediaDatabase::class.java, noBackupPath)
        .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
        .addMigrations(MIGRATION_3_4)
        .build()
    }

    val MIGRATION_1_2: Migration = object : Migration(1, 2) {
      override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
          "ALTER TABLE sync_state ADD COLUMN lastSuccessfulCatchUpAtEpochMillis INTEGER",
        )
      }
    }

    val MIGRATION_2_3: Migration = object : Migration(2, 3) {
      override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
          """
          CREATE TABLE IF NOT EXISTS playback_position (
            accountId INTEGER NOT NULL,
            databaseGeneration INTEGER NOT NULL,
            stableFileIdentity TEXT NOT NULL,
            positionMs INTEGER NOT NULL,
            durationMs INTEGER NOT NULL,
            updatedAtEpochMillis INTEGER NOT NULL,
            PRIMARY KEY(accountId, databaseGeneration, stableFileIdentity)
          )
          """.trimIndent(),
        )
      }
    }

    val MIGRATION_3_4: Migration = object : Migration(3, 4) {
      override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
          "ALTER TABLE saved_media ADD COLUMN expectedSizeBytes INTEGER NOT NULL DEFAULT 0",
        )
      }
    }
  }
}
