package com.nmtuong.telegramdrive.data.local

import androidx.room.Entity

@Entity(
  tableName = "playback_position",
  primaryKeys = ["accountId", "databaseGeneration", "stableFileIdentity"],
)
data class PlaybackPositionEntity(
  val accountId: Long,
  val databaseGeneration: Long,
  val stableFileIdentity: String,
  val positionMs: Long,
  val durationMs: Long,
  val updatedAtEpochMillis: Long,
)
