package com.nmtuong.telegramdrive.data

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nmtuong.telegramdrive.data.local.MediaDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaDatabaseMigrationTest {
  @Test
  fun migratesVersionOneWithoutDroppingSyncState() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val databaseFile = context.getDatabasePath("phase3-migration-test.db")
    databaseFile.delete()
    databaseFile.parentFile?.mkdirs()

    SQLiteDatabase.openOrCreateDatabase(databaseFile.absolutePath, null).use { database ->
      database.execSQL(
        """
        CREATE TABLE saved_media (
          accountId INTEGER NOT NULL,
          databaseGeneration INTEGER NOT NULL,
          chatId INTEGER NOT NULL,
          messageId INTEGER NOT NULL,
          mediaType TEXT NOT NULL,
          messageDateEpochSeconds INTEGER NOT NULL,
          caption TEXT NOT NULL,
          stableDisplayName TEXT NOT NULL,
          mimeType TEXT,
          width INTEGER NOT NULL,
          height INTEGER NOT NULL,
          durationSeconds INTEGER NOT NULL,
          telegramFileId INTEGER NOT NULL,
          originalStableFileIdentity TEXT NOT NULL,
          thumbnailFileId INTEGER,
          thumbnailStableFileIdentity TEXT,
          minithumbnailData TEXT,
          minithumbnailWidth INTEGER NOT NULL,
          minithumbnailHeight INTEGER NOT NULL,
          localFilePath TEXT,
          deleted INTEGER NOT NULL,
          available INTEGER NOT NULL,
          lastReconciledAtEpochMillis INTEGER NOT NULL,
          PRIMARY KEY(accountId, databaseGeneration, chatId, messageId)
        )
        """.trimIndent(),
      )
      database.execSQL("CREATE INDEX index_saved_media_accountId_databaseGeneration_messageDateEpochSeconds_messageId ON saved_media(accountId, databaseGeneration, messageDateEpochSeconds, messageId)")
      database.execSQL("CREATE INDEX index_saved_media_accountId_databaseGeneration_mediaType ON saved_media(accountId, databaseGeneration, mediaType)")
      database.execSQL("CREATE INDEX index_saved_media_accountId_databaseGeneration_originalStableFileIdentity ON saved_media(accountId, databaseGeneration, originalStableFileIdentity)")
      database.execSQL(
        """
        CREATE TABLE cached_file (
          accountId INTEGER NOT NULL,
          databaseGeneration INTEGER NOT NULL,
          stableFileIdentity TEXT NOT NULL,
          tdlibFileId INTEGER NOT NULL,
          localPath TEXT,
          fileType TEXT NOT NULL,
          observedSizeBytes INTEGER NOT NULL,
          lastAccessedAtEpochMillis INTEGER NOT NULL,
          observedState TEXT NOT NULL,
          PRIMARY KEY(accountId, databaseGeneration, stableFileIdentity)
        )
        """.trimIndent(),
      )
      database.execSQL("CREATE INDEX index_cached_file_accountId_databaseGeneration_lastAccessedAtEpochMillis ON cached_file(accountId, databaseGeneration, lastAccessedAtEpochMillis)")
      database.execSQL("CREATE INDEX index_cached_file_accountId_databaseGeneration_observedState ON cached_file(accountId, databaseGeneration, observedState)")
      database.execSQL(
        """
        CREATE TABLE sync_state (
          accountId INTEGER NOT NULL,
          databaseGeneration INTEGER NOT NULL,
          chatId INTEGER NOT NULL,
          phase TEXT NOT NULL,
          backfillCursor INTEGER,
          headWatermark INTEGER,
          lastCheckpointAtEpochMillis INTEGER,
          lastSuccessfulCatchUpHead INTEGER,
          lastError TEXT,
          retryCount INTEGER NOT NULL,
          lastAttemptAtEpochMillis INTEGER,
          PRIMARY KEY(accountId, databaseGeneration, chatId)
        )
        """.trimIndent(),
      )
      database.execSQL("CREATE TABLE room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
      database.execSQL("INSERT INTO room_master_table(id, identity_hash) VALUES(42, 'legacy-phase3-v1')")
      database.execSQL("INSERT INTO sync_state(accountId, databaseGeneration, chatId, phase, backfillCursor, headWatermark, lastCheckpointAtEpochMillis, lastSuccessfulCatchUpHead, lastError, retryCount, lastAttemptAtEpochMillis) VALUES(7, 3, 99, 'BACKFILLING', 9000, 10000, 1, NULL, NULL, 0, 1)")
      database.version = 1
    }

    val migrated = Room.databaseBuilder(context, MediaDatabase::class.java, databaseFile.absolutePath)
      .addMigrations(MediaDatabase.MIGRATION_1_2)
      .build()
    val supportDatabase = migrated.openHelper.writableDatabase

    assertEquals(2, supportDatabase.version)
    supportDatabase.query("SELECT accountId, phase, backfillCursor FROM sync_state").use { cursor ->
      assertTrue(cursor.moveToFirst())
      assertEquals(7L, cursor.getLong(0))
      assertEquals("BACKFILLING", cursor.getString(1))
      assertEquals(9000L, cursor.getLong(2))
    }
    supportDatabase.query("PRAGMA table_info(sync_state)").use { cursor ->
      var found = false
      while (cursor.moveToNext()) found = found || cursor.getString(1) == "lastSuccessfulCatchUpAtEpochMillis"
      assertTrue(found)
    }

    migrated.close()
    databaseFile.delete()
  }
}
