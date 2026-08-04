package com.nmtuong.telegramdrive.data.video

import com.nmtuong.telegramdrive.BuildConfig
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Debug/test-only aggregate metrics. It intentionally contains no media or account identifiers. */
internal data class VideoStreamingDiagnosticsSnapshot(
  val opaquePlaybackSessionId: Long,
  val playerCreateCount: Int,
  val playerReleaseCount: Int,
  val activePlayerCount: Int,
  val coordinatorCreateCount: Int,
  val coordinatorCloseCount: Int,
  val activeCoordinatorCount: Int,
  val readerOpenCount: Int,
  val readerCloseCount: Int,
  val activeReaderCount: Int,
  val committedSeekCount: Int,
  val lastSeekToResumeElapsedMs: Long?,
  val rangeRequestCount: Int,
  val rangeOffset: Long,
  val rangeLength: Long,
  val bytesRead: Long,
  val firstFrameElapsedMs: Long?,
  val rebufferCount: Int,
  val rebufferDurationMs: Long,
  val positionWriteCount: Int,
)

/** A fixed-schema, aggregate-only payload for the debug ADB receiver. */
internal fun VideoStreamingDiagnosticsSnapshot.toDebugLogLine(): String = buildString {
  append("schema=1")
  append(" opaque_playback_session_id=").append(opaquePlaybackSessionId)
  append(" player_create_count=").append(playerCreateCount)
  append(" player_release_count=").append(playerReleaseCount)
  append(" active_player_count=").append(activePlayerCount)
  append(" coordinator_create_count=").append(coordinatorCreateCount)
  append(" coordinator_close_count=").append(coordinatorCloseCount)
  append(" active_coordinator_count=").append(activeCoordinatorCount)
  append(" reader_open_count=").append(readerOpenCount)
  append(" reader_close_count=").append(readerCloseCount)
  append(" active_reader_count=").append(activeReaderCount)
  append(" committed_seek_count=").append(committedSeekCount)
  append(" last_seek_to_resume_elapsed_ms=").append(lastSeekToResumeElapsedMs ?: "none")
  append(" range_request_count=").append(rangeRequestCount)
  append(" range_offset=").append(rangeOffset)
  append(" range_length=").append(rangeLength)
  append(" bytes_read=").append(bytesRead)
  append(" first_frame_elapsed_ms=").append(firstFrameElapsedMs ?: "none")
  append(" rebuffer_count=").append(rebufferCount)
  append(" rebuffer_duration_ms=").append(rebufferDurationMs)
  append(" position_write_count=").append(positionWriteCount)
}

internal object VideoStreamingDiagnostics {
  private const val NoRebufferStartElapsedMs = -1L
  private const val NoPendingSeekStartElapsedMs = -1L
  private val playbackSessionId = AtomicLong(0L)
  private val playerCreateCount = AtomicInteger(0)
  private val playerReleaseCount = AtomicInteger(0)
  private val activePlayerCount = AtomicInteger(0)
  private val coordinatorCreateCount = AtomicInteger(0)
  private val coordinatorCloseCount = AtomicInteger(0)
  private val activeCoordinatorCount = AtomicInteger(0)
  private val readerOpenCount = AtomicInteger(0)
  private val readerCloseCount = AtomicInteger(0)
  private val activeReaderCount = AtomicInteger(0)
  private val committedSeekCount = AtomicInteger(0)
  private val pendingSeekStartElapsedMs = AtomicLong(NoPendingSeekStartElapsedMs)
  private val lastSeekToResumeElapsedMs = AtomicLong(-1L)
  private val rangeRequestCount = AtomicInteger(0)
  private val rebufferCount = AtomicInteger(0)
  private val positionWriteCount = AtomicInteger(0)
  private val rangeOffset = AtomicLong(0L)
  private val rangeLength = AtomicLong(0L)
  private val bytesRead = AtomicLong(0L)
  private val firstFrameElapsedMs = AtomicLong(-1L)
  private val rebufferStartElapsedMs = AtomicLong(NoRebufferStartElapsedMs)
  private val rebufferDurationMs = AtomicLong(0L)

  private inline fun record(block: () -> Unit) {
    if (BuildConfig.DEBUG) block()
  }

  fun playerCreated() = record {
    playbackSessionId.incrementAndGet()
    playerCreateCount.incrementAndGet()
    activePlayerCount.incrementAndGet()
  }

  fun playerReleased() = record {
    playerReleaseCount.incrementAndGet()
    activePlayerCount.updateAndGet { (it - 1).coerceAtLeast(0) }
  }

  fun coordinatorCreated() = record {
    coordinatorCreateCount.incrementAndGet()
    activeCoordinatorCount.incrementAndGet()
  }

  fun coordinatorClosed() = record {
    coordinatorCloseCount.incrementAndGet()
    activeCoordinatorCount.updateAndGet { (it - 1).coerceAtLeast(0) }
  }

  fun readerOpened() = record {
    readerOpenCount.incrementAndGet()
    activeReaderCount.incrementAndGet()
  }

  fun readerClosed() = record {
    readerCloseCount.incrementAndGet()
    activeReaderCount.updateAndGet { (it - 1).coerceAtLeast(0) }
  }

  fun seekCommitted() = record { committedSeekCount.incrementAndGet() }

  fun seekStarted(elapsedMs: Long) = record {
    pendingSeekStartElapsedMs.set(elapsedMs.coerceAtLeast(0L))
  }

  fun seekResumed(elapsedMs: Long) = record {
    val startedAt = pendingSeekStartElapsedMs.getAndSet(NoPendingSeekStartElapsedMs)
    if (startedAt != NoPendingSeekStartElapsedMs) {
      lastSeekToResumeElapsedMs.set((elapsedMs - startedAt).coerceAtLeast(0L))
    }
  }

  fun seekAbandoned() = record {
    pendingSeekStartElapsedMs.set(NoPendingSeekStartElapsedMs)
  }

  fun rangeRequested(offset: Long, length: Long) = record {
    rangeRequestCount.incrementAndGet()
    rangeOffset.set(offset.coerceAtLeast(0L))
    rangeLength.set(length.coerceAtLeast(0L))
  }

  fun bytesRead(count: Int) = record { bytesRead.addAndGet(count.coerceAtLeast(0).toLong()) }

  fun firstFrameRendered(elapsedMs: Long) = record {
    firstFrameElapsedMs.compareAndSet(-1L, elapsedMs.coerceAtLeast(0L))
  }

  fun rebufferStarted(elapsedMs: Long) = record {
    val startedAt = elapsedMs.coerceAtLeast(0L)
    if (rebufferStartElapsedMs.compareAndSet(NoRebufferStartElapsedMs, startedAt)) {
      rebufferCount.incrementAndGet()
    }
  }

  fun rebufferEnded(elapsedMs: Long) = record {
    while (true) {
      val startedAt = rebufferStartElapsedMs.get()
      if (startedAt == NoRebufferStartElapsedMs) break
      if (rebufferStartElapsedMs.compareAndSet(startedAt, NoRebufferStartElapsedMs)) {
        rebufferDurationMs.addAndGet((elapsedMs - startedAt).coerceAtLeast(0L))
        break
      }
    }
  }

  fun positionWritten() = record { positionWriteCount.incrementAndGet() }

  fun snapshot(): VideoStreamingDiagnosticsSnapshot = VideoStreamingDiagnosticsSnapshot(
    opaquePlaybackSessionId = playbackSessionId.get(),
    playerCreateCount = playerCreateCount.get(),
    playerReleaseCount = playerReleaseCount.get(),
    activePlayerCount = activePlayerCount.get(),
    coordinatorCreateCount = coordinatorCreateCount.get(),
    coordinatorCloseCount = coordinatorCloseCount.get(),
    activeCoordinatorCount = activeCoordinatorCount.get(),
    readerOpenCount = readerOpenCount.get(),
    readerCloseCount = readerCloseCount.get(),
    activeReaderCount = activeReaderCount.get(),
    committedSeekCount = committedSeekCount.get(),
    lastSeekToResumeElapsedMs = lastSeekToResumeElapsedMs.get().takeIf { it >= 0L },
    rangeRequestCount = rangeRequestCount.get(),
    rangeOffset = rangeOffset.get(),
    rangeLength = rangeLength.get(),
    bytesRead = bytesRead.get(),
    firstFrameElapsedMs = firstFrameElapsedMs.get().takeIf { it >= 0L },
    rebufferCount = rebufferCount.get(),
    rebufferDurationMs = rebufferDurationMs.get(),
    positionWriteCount = positionWriteCount.get(),
  )

  fun resetForTests() = reset()

  fun resetForDebugScenario() = reset()

  private fun reset() {
    playbackSessionId.set(0L)
    playerCreateCount.set(0)
    playerReleaseCount.set(0)
    activePlayerCount.set(0)
    coordinatorCreateCount.set(0)
    coordinatorCloseCount.set(0)
    activeCoordinatorCount.set(0)
    readerOpenCount.set(0)
    readerCloseCount.set(0)
    activeReaderCount.set(0)
    committedSeekCount.set(0)
    pendingSeekStartElapsedMs.set(NoPendingSeekStartElapsedMs)
    lastSeekToResumeElapsedMs.set(-1L)
    rangeRequestCount.set(0)
    rangeOffset.set(0L)
    rangeLength.set(0L)
    bytesRead.set(0L)
    firstFrameElapsedMs.set(-1L)
    rebufferCount.set(0)
    rebufferStartElapsedMs.set(NoRebufferStartElapsedMs)
    rebufferDurationMs.set(0L)
    positionWriteCount.set(0)
  }
}
