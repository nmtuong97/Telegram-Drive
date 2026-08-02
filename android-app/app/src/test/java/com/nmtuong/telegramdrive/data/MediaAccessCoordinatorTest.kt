package com.nmtuong.telegramdrive.data

import com.nmtuong.telegramdrive.data.local.CachedFileEntity
import com.nmtuong.telegramdrive.data.local.CachedFileState
import com.nmtuong.telegramdrive.data.local.CachedFileType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaAccessCoordinatorTest {
  @Test
  fun thumbnailEvictionRemovesOldestEntriesAndKeepsNewestEntries() {
    val cached = listOf(
      thumbnail("oldest", 10L),
      thumbnail("middle", 20L),
      thumbnail("newest", 30L),
    )

    assertEquals(
      listOf("oldest"),
      thumbnailEvictionCandidates(cached, maxEntries = 2).map { it.stableFileIdentity },
    )
  }

  @Test
  fun thumbnailEvictionDoesNothingWhenCacheIsWithinLimit() {
    val cached = listOf(thumbnail("only", 10L))

    assertTrue(thumbnailEvictionCandidates(cached, maxEntries = 1).isEmpty())
  }

  private fun thumbnail(identity: String, lastAccessedAt: Long) = CachedFileEntity(
    accountId = 1L,
    databaseGeneration = 1L,
    stableFileIdentity = identity,
    tdlibFileId = identity.hashCode(),
    localPath = "/tmp/$identity",
    fileType = CachedFileType.THUMBNAIL.name,
    observedSizeBytes = 1L,
    lastAccessedAtEpochMillis = lastAccessedAt,
    observedState = CachedFileState.COMPLETE.name,
  )
}
