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
  val rangeRequestCount: Int,
  val rangeOffset: Long,
  val rangeLength: Long,
  val bytesRead: Long,
  val firstFrameElapsedMs: Long?,
  val rebufferCount: Int,
)

internal object VideoStreamingDiagnostics {
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
  private val rangeRequestCount = AtomicInteger(0)
  private val rebufferCount = AtomicInteger(0)
  private val rangeOffset = AtomicLong(0L)
  private val rangeLength = AtomicLong(0L)
  private val bytesRead = AtomicLong(0L)
  private val firstFrameElapsedMs = AtomicLong(-1L)

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

  fun rangeRequested(offset: Long, length: Long) = record {
    rangeRequestCount.incrementAndGet()
    rangeOffset.set(offset.coerceAtLeast(0L))
    rangeLength.set(length.coerceAtLeast(0L))
  }

  fun bytesRead(count: Int) = record { bytesRead.addAndGet(count.coerceAtLeast(0).toLong()) }

  fun firstFrameRendered(elapsedMs: Long) = record {
    firstFrameElapsedMs.compareAndSet(-1L, elapsedMs.coerceAtLeast(0L))
  }

  fun rebuffered() = record { rebufferCount.incrementAndGet() }

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
    rangeRequestCount = rangeRequestCount.get(),
    rangeOffset = rangeOffset.get(),
    rangeLength = rangeLength.get(),
    bytesRead = bytesRead.get(),
    firstFrameElapsedMs = firstFrameElapsedMs.get().takeIf { it >= 0L },
    rebufferCount = rebufferCount.get(),
  )

  fun resetForTests() {
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
    rangeRequestCount.set(0)
    rangeOffset.set(0L)
    rangeLength.set(0L)
    bytesRead.set(0L)
    firstFrameElapsedMs.set(-1L)
    rebufferCount.set(0)
  }
}
