package com.nmtuong.telegramdrive.data.video

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class VideoStreamingDiagnosticsTest {
  @Before
  fun resetBefore() {
    VideoStreamingDiagnostics.resetForTests()
  }

  @After
  fun resetAfter() {
    VideoStreamingDiagnostics.resetForTests()
  }

  @Test
  fun rebufferWindowCountsOnceAndAccumulatesOnlyItsElapsedDuration() {
    VideoStreamingDiagnostics.rebufferStarted(1_000L)
    VideoStreamingDiagnostics.rebufferStarted(1_050L)
    VideoStreamingDiagnostics.rebufferEnded(1_240L)
    VideoStreamingDiagnostics.rebufferEnded(1_300L)

    val snapshot = VideoStreamingDiagnostics.snapshot()

    assertEquals(1, snapshot.rebufferCount)
    assertEquals(240L, snapshot.rebufferDurationMs)
  }

  @Test
  fun debugLogLineContainsOnlyFixedAggregateNumericFields() {
    VideoStreamingDiagnostics.playerCreated()
    VideoStreamingDiagnostics.coordinatorCreated()
    VideoStreamingDiagnostics.readerOpened()
    VideoStreamingDiagnostics.seekCommitted()
    VideoStreamingDiagnostics.seekStarted(100L)
    VideoStreamingDiagnostics.seekResumed(140L)
    VideoStreamingDiagnostics.rangeRequested(offset = 64L, length = 128L)
    VideoStreamingDiagnostics.bytesRead(32)
    VideoStreamingDiagnostics.firstFrameRendered(42L)
    VideoStreamingDiagnostics.rebufferStarted(100L)
    VideoStreamingDiagnostics.rebufferEnded(125L)
    VideoStreamingDiagnostics.positionWritten()

    val line = VideoStreamingDiagnostics.snapshot().toDebugLogLine()

    assertTrue(line.startsWith("schema=1 opaque_playback_session_id=1"))
    assertTrue(line.contains("range_offset=64"))
    assertTrue(line.contains("range_length=128"))
    assertTrue(line.contains("bytes_read=32"))
    assertTrue(line.contains("first_frame_elapsed_ms=42"))
    assertTrue(line.contains("last_seek_to_resume_elapsed_ms=40"))
    assertTrue(line.contains("rebuffer_duration_ms=25"))
    assertTrue(line.split(' ').all { it.matches(Regex("[a-z_]+=(none|[0-9]+)")) })
    assertFalse(line.contains("path"))
    assertFalse(line.contains("token"))
    assertFalse(line.contains("caption"))
  }
}
