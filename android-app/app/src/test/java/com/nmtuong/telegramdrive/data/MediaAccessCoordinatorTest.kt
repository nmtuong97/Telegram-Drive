package com.nmtuong.telegramdrive.data

import com.nmtuong.telegramdrive.data.local.CachedFileEntity
import com.nmtuong.telegramdrive.data.local.CachedFileState
import com.nmtuong.telegramdrive.data.local.CachedFileType
import java.nio.file.Files
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

  @Test
  fun partialLocalVideoIsRejectedWhenItIsShorterThanExpected() {
    val root = Files.createTempDirectory("video-source-rules-").toFile()
    val path = root.resolve("video.partial").also { it.writeBytes(ByteArray(4)) }

    assertEquals(null, verifiedCompleteLocalVideoPath(path.absolutePath, expectedSizeBytes = 8L))

    root.deleteRecursively()
  }

  @Test
  fun completeLocalVideoIsSelectedWhenItMeetsExpectedSize() {
    val root = Files.createTempDirectory("video-source-rules-").toFile()
    val path = root.resolve("video.complete").also { it.writeBytes(ByteArray(8)) }

    assertEquals(path.absolutePath, verifiedCompleteLocalVideoPath(path.absolutePath, expectedSizeBytes = 8L))

    root.deleteRecursively()
  }

  @Test
  fun staleMissingAndUnreadableLocalCandidatesFallBackToStreaming() {
    val root = Files.createTempDirectory("video-source-invalid-").toFile()
    val missing = root.resolve("missing.video")
    val directory = root.resolve("not-a-file").also { it.mkdirs() }

    assertEquals(null, verifiedCompleteLocalVideoPath(missing.absolutePath, expectedSizeBytes = 8L))
    assertEquals(null, verifiedCompleteLocalVideoPath(directory.absolutePath, expectedSizeBytes = 8L))
    assertEquals(null, verifiedCompleteLocalVideoPath(missing.absolutePath, expectedSizeBytes = null))
    assertEquals(null, verifiedCompleteLocalVideoPath(missing.absolutePath, expectedSizeBytes = 0L))

    root.deleteRecursively()
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
