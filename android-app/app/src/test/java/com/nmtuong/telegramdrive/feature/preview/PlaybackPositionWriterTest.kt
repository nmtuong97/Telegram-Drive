package com.nmtuong.telegramdrive.feature.preview

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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

  @Test
  fun failedWriteDoesNotPreventALaterFinalSnapshotFromDraining() = runTest {
    val writes = mutableListOf<PlaybackPositionSnapshot>()
    val writer = PlaybackPositionWriter(this) { snapshot ->
      if (snapshot.positionMs == 1_000L) error("database unavailable")
      writes += snapshot
    }

    writer.enqueue(PlaybackPositionSnapshot(1_000L, 60_000L), force = true)
    writer.enqueue(PlaybackPositionSnapshot(2_000L, 60_000L), force = true)
    writer.close()
    advanceUntilIdle()

    assertEquals(listOf(PlaybackPositionSnapshot(2_000L, 60_000L)), writes)
  }

  @Test
  fun scopeCancellationPreventsAnIncompleteWriteFromCompletingLater() = runTest {
    val completed = mutableListOf<PlaybackPositionSnapshot>()
    val writeStarted = CompletableDeferred<Unit>()
    val neverCompletes = CompletableDeferred<Unit>()
    val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
    val writer = PlaybackPositionWriter(scope) { snapshot ->
      writeStarted.complete(Unit)
      neverCompletes.await()
      completed += snapshot
    }

    writer.enqueue(PlaybackPositionSnapshot(1_000L, 60_000L), force = true)
    runCurrent()
    writeStarted.await()
    scope.cancel()
    advanceUntilIdle()

    assertEquals(emptyList<PlaybackPositionSnapshot>(), completed)
  }
}
