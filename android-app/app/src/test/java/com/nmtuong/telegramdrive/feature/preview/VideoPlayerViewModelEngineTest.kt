package com.nmtuong.telegramdrive.feature.preview

import android.content.Context
import androidx.media3.common.Player
import androidx.media3.datasource.DataSource
import com.nmtuong.telegramdrive.data.video.VideoStreamingDiagnostics
import com.nmtuong.telegramdrive.domain.AccountSessionIdentity
import com.nmtuong.telegramdrive.domain.VideoPlaybackRequest
import java.util.ArrayDeque
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VideoPlayerViewModelEngineTest {
  @After
  fun resetMainDispatcher() {
    Dispatchers.resetMain()
  }

  @Test
  fun initializeAutoplaysThenPauseReplayAndCloseOwnExactlyOneEngine() = playbackTest {
    VideoStreamingDiagnostics.resetForTests()
    val gateway = FakePlaybackGateway()
    val engine = FakeVideoPlayerEngine()
    val viewModel = VideoPlayerViewModel(request(), gateway, QueueEngineFactory(), FakePlaybackClock())

    try {
      viewModel.initializeForTests(engine)
      runCurrent()

      assertEquals("streaming", viewModel.uiState.value.sourceLabel)
      assertTrue(engine.playWhenReady)
      assertEquals(1, engine.prepareCount)
      engine.emitReady()
      assertEquals(VideoPlaybackPhase.Playing, viewModel.uiState.value.phase)

      viewModel.togglePlayPause()
      assertFalse(engine.isPlaying)
      assertEquals(VideoPlaybackPhase.Paused, viewModel.uiState.value.phase)
      viewModel.togglePlayPause()
      assertTrue(engine.isPlaying)
      assertEquals(VideoPlaybackPhase.Playing, viewModel.uiState.value.phase)

      engine.emitEnded()
      viewModel.replay()
      assertEquals(0L, engine.seekRequests.last())
      assertTrue(engine.isPlaying)
      assertEquals(VideoPlaybackPhase.Playing, viewModel.uiState.value.phase)

      viewModel.closePlayback()
      viewModel.closePlayback()
      runCurrent()

      assertEquals(1, engine.releaseCount)
      assertEquals(1, gateway.closeCount)
      assertEquals(0, VideoStreamingDiagnostics.snapshot().activePlayerCount)
      assertTrue(VideoStreamingDiagnostics.snapshot().positionWriteCount > 0)
      assertTrue(gateway.savedPositions.isNotEmpty())
    } finally {
      viewModel.closePlayback()
      runCurrent()
    }
  }

  @Test
  fun bufferedAndRemoteSeeksReachExpectedPhasesAndKeepTheLastTarget() = playbackTest {
    VideoStreamingDiagnostics.resetForTests()
    val gateway = FakePlaybackGateway()
    val engine = FakeVideoPlayerEngine(bufferedPosition = 90_000L)
    val clock = FakePlaybackClock()
    val viewModel = VideoPlayerViewModel(request(), gateway, QueueEngineFactory(), clock)

    try {
      viewModel.initializeForTests(engine)
      runCurrent()
      engine.emitReady()

      viewModel.seekTo(50_000L)
      assertEquals(VideoPlaybackPhase.Playing, viewModel.uiState.value.phase)

      engine.emitFirstFrame()
      engine.bufferedPosition = 55_000L
      viewModel.seekTo(80_000L)
      assertEquals(VideoPlaybackPhase.Seeking, viewModel.uiState.value.phase)
      clock.nowMs = 1_000L
      engine.emitPlaybackState(Player.STATE_BUFFERING)
      engine.emitPlaybackState(Player.STATE_BUFFERING)
      assertEquals(VideoPlaybackPhase.Rebuffering, viewModel.uiState.value.phase)
      clock.nowMs = 1_250L
      engine.emitReady()
      assertEquals(VideoPlaybackPhase.Playing, viewModel.uiState.value.phase)
      assertEquals(1, VideoStreamingDiagnostics.snapshot().rebufferCount)
      assertEquals(250L, VideoStreamingDiagnostics.snapshot().rebufferDurationMs)
      assertEquals(2, VideoStreamingDiagnostics.snapshot().committedSeekCount)
      assertEquals(1_250L, VideoStreamingDiagnostics.snapshot().lastSeekToResumeElapsedMs)

      engine.pause()
      viewModel.seekTo(35_000L)
      assertEquals(VideoPlaybackPhase.Paused, viewModel.uiState.value.phase)

      engine.play()
      engine.bufferedPosition = 120_000L
      viewModel.seekTo(10_000L)
      viewModel.seekTo(20_000L)
      viewModel.seekTo(30_000L)
      assertEquals(30_000L, engine.seekRequests.last())
      assertEquals(30_000L, viewModel.uiState.value.positionMs)

      viewModel.closePlayback()
      runCurrent()
    } finally {
      viewModel.closePlayback()
      runCurrent()
    }
  }

  @Test
  fun retryReleasesOldEngineOnceAndIgnoresItsLateCallback() = playbackTest {
    val gateway = FakePlaybackGateway()
    val first = FakeVideoPlayerEngine()
    val replacement = FakeVideoPlayerEngine()
    val factory = QueueEngineFactory(replacement)
    val viewModel = VideoPlayerViewModel(request(), gateway, factory, FakePlaybackClock())

    try {
      viewModel.initializeForTests(first)
      runCurrent()
      first.emitReady()
      first.emitError(
        VideoPlayerEngineError(
          errorCode = androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
          details = "offline",
          cause = null,
        ),
      )
      assertEquals(VideoPlaybackPhase.RecoverableError, viewModel.uiState.value.phase)

      viewModel.retryForTests()
      viewModel.retryForTests()
      runCurrent()

      assertEquals(1, first.releaseCount)
      assertEquals(1, factory.createCount)
      assertEquals(1, replacement.prepareCount)
      replacement.emitReady()
      val phaseAfterReplacementReady = viewModel.uiState.value.phase
      first.emitLateEnded()
      assertEquals(phaseAfterReplacementReady, viewModel.uiState.value.phase)

      viewModel.closePlayback()
      assertEquals(1, replacement.releaseCount)
      assertEquals(2, gateway.closeCount)
      runCurrent()
    } finally {
      viewModel.closePlayback()
      runCurrent()
    }
  }

  private fun playbackTest(block: suspend TestScope.() -> Unit) = runTest {
    Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
    block()
  }

  private fun request() = VideoPlaybackRequest(
    accountIdentity = AccountSessionIdentity(accountId = 7L, databaseGeneration = 3L),
    stableFileIdentity = "test-video",
    telegramFileId = 17,
    chatId = 70L,
    messageId = 71L,
    displayName = "test.mp4",
    durationSeconds = 120L,
    mimeType = "video/mp4",
    expectedSizeBytes = 1_000L,
    localPath = null,
    thumbnailPath = null,
    minithumbnailData = null,
  )

  private class FakePlaybackGateway : VideoPlaybackGateway {
    val savedPositions = mutableListOf<PlaybackPositionSnapshot>()
    var closeCount = 0

    override suspend fun loadPosition(request: VideoPlaybackRequest): Long? = null
    override suspend fun resolveLocalPath(request: VideoPlaybackRequest): String? = null
    override fun streamingDataSourceFactory(request: VideoPlaybackRequest): DataSource.Factory =
      DataSource.Factory { throw UnsupportedOperationException("Fake engine never opens a DataSource") }

    override suspend fun savePosition(request: VideoPlaybackRequest, positionMs: Long, durationMs: Long) {
      savedPositions += PlaybackPositionSnapshot(positionMs, durationMs)
    }

    override fun closePlayback(request: VideoPlaybackRequest) {
      closeCount++
    }
  }

  private class QueueEngineFactory(vararg engines: FakeVideoPlayerEngine) : VideoPlayerEngineFactory {
    private val queued = ArrayDeque(engines.toList())
    var createCount = 0

    override fun create(context: Context?): VideoPlayerEngine {
      createCount++
      return check(!queued.isEmpty()) { "No fake engine was queued" }.let { queued.removeFirst() }
    }
  }

  private class FakePlaybackClock(var nowMs: Long = 0L) : VideoPlaybackClock {
    override fun elapsedRealtime(): Long = nowMs
  }

  private class FakeVideoPlayerEngine(
    override var currentPosition: Long = 12_000L,
    override var duration: Long = 120_000L,
    override var bufferedPosition: Long = 120_000L,
    override var isPlaying: Boolean = true,
  ) : VideoPlayerEngine {
    override val player: Player? = null
    override var playWhenReady: Boolean = false
    var prepareCount = 0
    var releaseCount = 0
    val seekRequests = mutableListOf<Long>()
    private var listener: VideoPlayerEngineListener? = null
    private var removedListener: VideoPlayerEngineListener? = null

    override fun addListener(listener: VideoPlayerEngineListener) {
      this.listener = listener
    }

    override fun removeListener(listener: VideoPlayerEngineListener) {
      if (this.listener === listener) {
        removedListener = listener
        this.listener = null
      }
    }

    override fun setLocalSource(path: String) = Unit
    override fun setStreamingSource(uri: String, mimeType: String?, dataSourceFactory: DataSource.Factory) = Unit

    override fun seekTo(positionMs: Long) {
      currentPosition = positionMs
      seekRequests += positionMs
      listener?.onPositionDiscontinuity(positionMs, Player.DISCONTINUITY_REASON_SEEK)
    }

    override fun prepare() {
      prepareCount++
    }

    override fun play() {
      isPlaying = true
      listener?.onIsPlayingChanged(true)
    }

    override fun pause() {
      isPlaying = false
      listener?.onIsPlayingChanged(false)
    }

    override fun stop() = Unit
    override fun release() {
      releaseCount++
    }

    fun emitReady() {
      isPlaying = true
      listener?.onPlaybackStateChanged(Player.STATE_READY)
      listener?.onIsPlayingChanged(true)
    }

    fun emitEnded() {
      isPlaying = false
      listener?.onPlaybackStateChanged(Player.STATE_ENDED)
    }

    fun emitPlaybackState(state: Int) {
      listener?.onPlaybackStateChanged(state)
    }

    fun emitFirstFrame() {
      listener?.onRenderedFirstFrame()
    }

    fun emitError(error: VideoPlayerEngineError) {
      listener?.onPlayerError(error)
    }

    fun emitLateEnded() {
      removedListener?.onPlaybackStateChanged(Player.STATE_ENDED)
    }
  }
}
