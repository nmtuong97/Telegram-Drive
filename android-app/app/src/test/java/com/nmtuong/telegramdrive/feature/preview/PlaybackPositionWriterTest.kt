package com.nmtuong.telegramdrive.feature.preview

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackPositionWriterTest {
  @Test
  fun coalescesPendingSnapshotsToTheLatestPosition() = runTest {
    val writes = mutableListOf<PlaybackPositionSnapshot>()
    val writer = PlaybackPositionWriter(this) { writes += it }

    writer.enqueue(PlaybackPositionSnapshot(1_000L, 60_000L), force = false)
    writer.enqueue(PlaybackPositionSnapshot(2_000L, 60_000L), force = false)
    advanceUntilIdle()

    assertEquals(listOf(PlaybackPositionSnapshot(2_000L, 60_000L)), writes)
  }

  @Test
  fun serializesWritesSoAnOlderWriteCannotFinishAfterANewerWrite() = runTest {
    val writes = mutableListOf<PlaybackPositionSnapshot>()
    val firstStarted = CompletableDeferred<Unit>()
    val releaseFirst = CompletableDeferred<Unit>()
    val writer = PlaybackPositionWriter(this) { snapshot ->
      writes += snapshot
      if (snapshot.positionMs == 1_000L) {
        firstStarted.complete(Unit)
        releaseFirst.await()
      }
    }

    writer.enqueue(PlaybackPositionSnapshot(1_000L, 60_000L), force = true)
    runCurrent()
    firstStarted.await()
    writer.enqueue(PlaybackPositionSnapshot(2_000L, 60_000L), force = true)
    releaseFirst.complete(Unit)
    advanceUntilIdle()

    assertEquals(
      listOf(
        PlaybackPositionSnapshot(1_000L, 60_000L),
        PlaybackPositionSnapshot(2_000L, 60_000L),
      ),
      writes,
    )
  }

  @Test
  fun closeDrainsAnAlreadyQueuedImmediateFlush() = runTest {
    val writes = mutableListOf<PlaybackPositionSnapshot>()
    val writer = PlaybackPositionWriter(this) { writes += it }

    writer.enqueue(PlaybackPositionSnapshot(9_000L, 60_000L), force = true)
    writer.close()
    advanceUntilIdle()

    assertEquals(listOf(PlaybackPositionSnapshot(9_000L, 60_000L)), writes)
  }
}
