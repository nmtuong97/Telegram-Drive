package com.nmtuong.telegramdrive.feature.preview

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import com.nmtuong.telegramdrive.data.MediaAccessCoordinator
import com.nmtuong.telegramdrive.data.video.VideoStreamingDiagnostics
import com.nmtuong.telegramdrive.domain.VideoPlaybackRequest
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class VideoPlaybackPhase {
  Opening,
  PreparingSource,
  InitialBuffering,
  Playing,
  Paused,
  Seeking,
  Rebuffering,
  Ended,
  RecoverableError,
  FatalError,
  Closed,
}

enum class VideoPlaybackErrorKind {
  Offline,
  TimeoutOrSlowNetwork,
  TelegramSessionChanged,
  RemoteFileUnavailable,
  CorruptOrIncompleteFile,
  UnsupportedFormatOrDecoder,
  SourceIdentityMismatch,
  UnknownPlaybackFailure,
}

data class VideoPlayerUiState(
  val phase: VideoPlaybackPhase = VideoPlaybackPhase.Opening,
  val positionMs: Long = 0L,
  val durationMs: Long = 0L,
  val controlsVisible: Boolean = true,
  val firstFrameRendered: Boolean = false,
  val sourceLabel: String? = null,
  val error: VideoPlaybackErrorKind? = null,
  val resumePositionMs: Long = 0L,
  val pendingSeekPositionMs: Long? = null,
)

internal fun allowsPlaybackGestures(phase: VideoPlaybackPhase): Boolean =
  phase in setOf(
    VideoPlaybackPhase.Playing,
    VideoPlaybackPhase.Paused,
    VideoPlaybackPhase.Rebuffering,
    VideoPlaybackPhase.Ended,
  )

internal fun allowsSeekGestures(phase: VideoPlaybackPhase): Boolean =
  phase in setOf(
    VideoPlaybackPhase.Playing,
    VideoPlaybackPhase.Paused,
    VideoPlaybackPhase.Rebuffering,
  )

internal fun canRetryPlayback(phase: VideoPlaybackPhase): Boolean =
  phase == VideoPlaybackPhase.RecoverableError

internal fun shouldAutoHideControls(phase: VideoPlaybackPhase, controlsVisible: Boolean): Boolean =
  phase == VideoPlaybackPhase.Playing && controlsVisible

internal data class VideoPlayerDiagnosticsSnapshot(
  val playerCreateCount: Int,
  val playerReleaseCount: Int,
  val activePlayerCount: Int,
)

internal object VideoPlayerDiagnostics {
  fun playerCreated() = VideoStreamingDiagnostics.playerCreated()

  fun playerReleased() = VideoStreamingDiagnostics.playerReleased()

  fun snapshot(): VideoPlayerDiagnosticsSnapshot = VideoPlayerDiagnosticsSnapshot(
    playerCreateCount = VideoStreamingDiagnostics.snapshot().playerCreateCount,
    playerReleaseCount = VideoStreamingDiagnostics.snapshot().playerReleaseCount,
    activePlayerCount = VideoStreamingDiagnostics.snapshot().activePlayerCount,
  )

  fun resetForTests() = VideoStreamingDiagnostics.resetForTests()
}

fun resumePositionMs(savedPositionMs: Long?, durationMs: Long): Long {
  val saved = savedPositionMs ?: return 0L
  if (saved < 30_000L) return 0L
  if (durationMs <= 0L) return saved
  return if (saved < (durationMs * 95L) / 100L) saved else 0L
}

internal fun classifyVideoPlaybackFailure(
  errorCode: Int,
  details: String,
  cause: Throwable? = null,
): VideoPlaybackErrorKind {
  val normalized = buildString {
    append(details)
    var current = cause
    repeat(8) {
      if (current == null) return@repeat
      append(' ').append(current::class.java.simpleName)
      append(' ').append(current.message.orEmpty())
      current = current.cause
    }
  }.lowercase()
  return when {
    errorCode == PlaybackException.ERROR_CODE_AUTHENTICATION_EXPIRED ||
      "session" in normalized || "account changed" in normalized -> VideoPlaybackErrorKind.TelegramSessionChanged
    "identity" in normalized -> VideoPlaybackErrorKind.SourceIdentityMismatch
    errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
      errorCode == PlaybackException.ERROR_CODE_TIMEOUT || "timeout" in normalized || "timed out" in normalized ->
      VideoPlaybackErrorKind.TimeoutOrSlowNetwork
    errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED || "offline" in normalized || "network" in normalized ->
      VideoPlaybackErrorKind.Offline
    errorCode == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ||
      ("file" in normalized && ("not found" in normalized || "no longer" in normalized)) ->
      VideoPlaybackErrorKind.RemoteFileUnavailable
    errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED ||
      errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED ||
      "corrupt" in normalized || "malformed" in normalized -> VideoPlaybackErrorKind.CorruptOrIncompleteFile
    errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ||
      errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED ||
      errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ||
      errorCode == PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED ||
      errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED ||
      errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES ||
      errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ||
      "decoder" in normalized || "format" in normalized || "codec" in normalized ->
      VideoPlaybackErrorKind.UnsupportedFormatOrDecoder
    else -> VideoPlaybackErrorKind.UnknownPlaybackFailure
  }
}

internal fun isRetryableVideoPlaybackError(kind: VideoPlaybackErrorKind?): Boolean =
  kind == null || kind in setOf(
    VideoPlaybackErrorKind.Offline,
    VideoPlaybackErrorKind.TimeoutOrSlowNetwork,
    VideoPlaybackErrorKind.RemoteFileUnavailable,
    VideoPlaybackErrorKind.CorruptOrIncompleteFile,
    VideoPlaybackErrorKind.UnknownPlaybackFailure,
  )

internal fun isExpectedVideoPlaybackCancellation(error: PlaybackException): Boolean {
  return isExpectedVideoPlaybackCancellation(error.cause, error.message.orEmpty())
}

internal fun isExpectedVideoPlaybackCancellation(cause: Throwable?, details: String = ""): Boolean {
  val normalized = buildString {
    append(details)
    var current = cause
    repeat(8) {
      if (current == null) return@repeat
      append(' ').append(current::class.java.simpleName)
      append(' ').append(current.message.orEmpty())
      current = current.cause
    }
  }.lowercase()
  var current = cause
  repeat(8) {
    if (current == null) return@repeat
    if (current is CancellationException) return true
    current = current.cause
  }
  return "video range superseded" in normalized ||
    "video reader closed" in normalized ||
    "navigation cancellation" in normalized
}

class VideoPlayerViewModel internal constructor(
  val request: VideoPlaybackRequest,
  private val playbackGateway: VideoPlaybackGateway,
  private val engineFactory: VideoPlayerEngineFactory = Media3VideoPlayerEngineFactory,
  private val clock: VideoPlaybackClock = AndroidVideoPlaybackClock,
) : ViewModel() {
  constructor(
    request: VideoPlaybackRequest,
    mediaAccess: MediaAccessCoordinator,
  ) : this(request, MediaAccessVideoPlaybackGateway(mediaAccess))

  private val _player = MutableStateFlow<Player?>(null)
  val player: StateFlow<Player?> = _player.asStateFlow()
  private val _uiState = MutableStateFlow(VideoPlayerUiState())
  val uiState: StateFlow<VideoPlayerUiState> = _uiState.asStateFlow()
  private val generation = AtomicLong(0L)
  private var engine: VideoPlayerEngine? = null
  private var currentListener: VideoPlayerEngineListener? = null
  private var prepareJob: Job? = null
  private var positionJob: Job? = null
  private var resumeOnForeground = false
  private var closed = false
  private var playerStartElapsedMs = 0L
  private val positionWriter = PlaybackPositionWriter(
    scope = viewModelScope,
    persist = { snapshot ->
      playbackGateway.savePosition(request, snapshot.positionMs, snapshot.durationMs)
    },
    onPersisted = VideoStreamingDiagnostics::positionWritten,
  )

  private fun createListener(
    owner: VideoPlayerEngine,
    ownerGeneration: Long,
  ): VideoPlayerEngineListener = object : VideoPlayerEngineListener {
    private fun isCurrentOwner(): Boolean =
      engine === owner && generation.get() == ownerGeneration && !closed

    override fun onPlaybackStateChanged(playbackState: Int) {
      if (!isCurrentOwner()) return
      val elapsedMs = clock.elapsedRealtime()
      when (playbackState) {
        Player.STATE_BUFFERING -> if (_uiState.value.firstFrameRendered) {
          VideoStreamingDiagnostics.rebufferStarted(elapsedMs)
        }
        Player.STATE_READY,
        Player.STATE_ENDED,
        Player.STATE_IDLE,
        -> {
          VideoStreamingDiagnostics.rebufferEnded(elapsedMs)
          if (playbackState == Player.STATE_READY && owner.isPlaying) {
            VideoStreamingDiagnostics.seekResumed(elapsedMs)
          }
        }
      }
      _uiState.update { state ->
        state.copy(
          phase = when (playbackState) {
            Player.STATE_BUFFERING -> if (state.firstFrameRendered) {
              VideoPlaybackPhase.Rebuffering
            } else VideoPlaybackPhase.InitialBuffering
            Player.STATE_READY -> if (owner.isPlaying) VideoPlaybackPhase.Playing else VideoPlaybackPhase.Paused
            Player.STATE_ENDED -> VideoPlaybackPhase.Ended
            else -> state.phase
          },
          positionMs = owner.currentPosition.coerceAtLeast(0L),
          durationMs = owner.duration.takeIf { it > 0L } ?: request.durationSeconds * 1_000L,
          pendingSeekPositionMs = if (playbackState == Player.STATE_READY) null else state.pendingSeekPositionMs,
        )
      }
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
      if (!isCurrentOwner()) return
      _uiState.update { state ->
        state.copy(
          phase = when {
            state.phase == VideoPlaybackPhase.Ended -> state.phase
            isPlaying -> VideoPlaybackPhase.Playing
            state.phase == VideoPlaybackPhase.InitialBuffering || state.phase == VideoPlaybackPhase.Rebuffering -> state.phase
            state.phase == VideoPlaybackPhase.Seeking && state.pendingSeekPositionMs != null -> VideoPlaybackPhase.Rebuffering
            else -> VideoPlaybackPhase.Paused
          },
          pendingSeekPositionMs = if (isPlaying) null else state.pendingSeekPositionMs,
        )
      }
    }

    override fun onRenderedFirstFrame() {
      if (!isCurrentOwner()) return
      VideoStreamingDiagnostics.firstFrameRendered(clock.elapsedRealtime() - playerStartElapsedMs)
      _uiState.update { it.copy(firstFrameRendered = true) }
    }

    override fun onPositionDiscontinuity(positionMs: Long, reason: Int) {
      if (!isCurrentOwner()) return
      if (reason == Player.DISCONTINUITY_REASON_SEEK) {
        _uiState.update { it.copy(positionMs = positionMs.coerceAtLeast(0L)) }
      }
    }

    override fun onPlayerError(error: VideoPlayerEngineError) {
      if (!isCurrentOwner()) return
      VideoStreamingDiagnostics.rebufferEnded(clock.elapsedRealtime())
      VideoStreamingDiagnostics.seekAbandoned()
      if (isExpectedVideoPlaybackCancellation(error.cause, error.details)) {
        _uiState.update { state ->
          state.copy(
            phase = if (owner.isPlaying) VideoPlaybackPhase.Playing else VideoPlaybackPhase.Paused,
            error = null,
            pendingSeekPositionMs = null,
          )
        }
        return
      }
      val kind = classify(error)
      _uiState.update {
        it.copy(
          phase = if (isRetryableVideoPlaybackError(kind)) {
            VideoPlaybackPhase.RecoverableError
          } else {
            VideoPlaybackPhase.FatalError
          },
          error = kind,
        )
      }
    }
  }

  fun initialize(context: Context) {
    initializeEngine(engineFactory.create(context.applicationContext))
  }

  internal fun initializeForTests(created: VideoPlayerEngine) {
    initializeEngine(created)
  }

  private fun initializeEngine(created: VideoPlayerEngine) {
    if (closed || engine != null) return
    val currentGeneration = generation.incrementAndGet()
    playerStartElapsedMs = clock.elapsedRealtime()
    VideoPlayerDiagnostics.playerCreated()
    val listener = createListener(created, currentGeneration)
    currentListener = listener
    engine = created
    created.addListener(listener)
    _player.value = created.player
    _uiState.value = VideoPlayerUiState(
      phase = VideoPlaybackPhase.Opening,
      durationMs = request.durationSeconds * 1_000L,
      controlsVisible = true,
    )
    prepareJob?.cancel()
    prepareJob = viewModelScope.launch {
      try {
        _uiState.update { it.copy(phase = VideoPlaybackPhase.PreparingSource) }
        val savedPosition = playbackGateway.loadPosition(request)
        val resume = resumePositionMs(savedPosition, request.durationSeconds * 1_000L)
        _uiState.update { it.copy(resumePositionMs = resume) }
        val localPath = playbackGateway.resolveLocalPath(request)
        if (currentGeneration != generation.get() || closed || engine !== created) return@launch
        if (localPath != null) {
          created.setLocalSource(localPath)
          _uiState.update { it.copy(sourceLabel = "local") }
        } else {
          created.setStreamingSource(
            uri = "tdlib://${request.playbackKey}",
            mimeType = request.mimeType,
            dataSourceFactory = playbackGateway.streamingDataSourceFactory(request),
          )
          _uiState.update { it.copy(sourceLabel = "streaming") }
        }
        if (currentGeneration != generation.get() || closed || engine !== created) return@launch
        if (resume > 0L) created.seekTo(resume)
        created.playWhenReady = true
        created.prepare()
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        if (currentGeneration == generation.get() && !closed && engine === created) {
          _uiState.update { it.copy(phase = VideoPlaybackPhase.FatalError, error = VideoPlaybackErrorKind.UnknownPlaybackFailure) }
        }
      }
    }
    if (positionJob == null) {
      positionJob = viewModelScope.launch {
        var nextPersistenceAt = 0L
        while (isActive && !closed) {
          delay(250L)
          val current = engine ?: continue
          val now = clock.elapsedRealtime()
          _uiState.update { state ->
            state.copy(
              positionMs = current.currentPosition.coerceAtLeast(0L),
              durationMs = current.duration.takeIf { it > 0L } ?: state.durationMs,
            )
          }
          if (current.isPlaying && now >= nextPersistenceAt) {
            persistPosition(current, force = false)
            nextPersistenceAt = now + 5_000L
          }
        }
      }
    }
  }

  fun togglePlayPause() {
    val current = engine ?: return
    if (current.isPlaying) {
      current.pause()
      persistPosition(current, force = true)
    } else {
      current.play()
    }
    setControlsVisible(true)
  }

  fun seekTo(positionMs: Long) {
    val current = engine ?: return
    val target = positionMs.coerceIn(0L, current.duration.takeIf { it > 0L } ?: Long.MAX_VALUE)
    val wasPlaying = current.isPlaying
    val resolvesImmediately = !wasPlaying || current.bufferedPosition >= target
    val seekStartedAt = clock.elapsedRealtime()
    current.seekTo(target)
    VideoStreamingDiagnostics.seekCommitted()
    if (wasPlaying) {
      VideoStreamingDiagnostics.seekStarted(seekStartedAt)
      if (resolvesImmediately) VideoStreamingDiagnostics.seekResumed(seekStartedAt)
    } else {
      VideoStreamingDiagnostics.seekAbandoned()
    }
    _uiState.update {
      it.copy(
        phase = when {
          !wasPlaying -> VideoPlaybackPhase.Paused
          resolvesImmediately -> VideoPlaybackPhase.Playing
          else -> VideoPlaybackPhase.Seeking
        },
        positionMs = target,
        pendingSeekPositionMs = target.takeIf { wasPlaying && !resolvesImmediately },
      )
    }
    persistPosition(current, force = true)
  }

  fun seekBy(deltaMs: Long) {
    val current = engine ?: return
    seekTo(current.currentPosition + deltaMs)
  }

  fun replay() = seekTo(0L).also { engine?.play() }

  fun setControlsVisible(visible: Boolean) {
    _uiState.update { it.copy(controlsVisible = visible) }
  }

  fun onStop() {
    val current = engine
    resumeOnForeground = current?.isPlaying == true
    current?.pause()
    VideoStreamingDiagnostics.rebufferEnded(clock.elapsedRealtime())
    VideoStreamingDiagnostics.seekAbandoned()
    current?.let { persistPosition(it, force = true) }
  }

  fun onStart() {
    if (resumeOnForeground && _uiState.value.phase != VideoPlaybackPhase.Ended) engine?.play()
    resumeOnForeground = false
  }

  fun retry(context: Context) {
    retryWith { engineFactory.create(context.applicationContext) }
  }

  internal fun retryForTests() {
    retryWith { engineFactory.create(null) }
  }

  private fun retryWith(createEngine: () -> VideoPlayerEngine) {
    if (closed || !canRetryPlayback(_uiState.value.phase)) return
    engine?.let { persistPosition(it, force = true) }
    prepareJob?.cancel()
    releasePlayer()
    closed = false
    _uiState.value = _uiState.value.copy(phase = VideoPlaybackPhase.Opening, error = null, firstFrameRendered = false)
    initializeEngine(createEngine())
  }

  fun closePlayback() {
    if (closed) return
    engine?.let { persistPosition(it, force = true) }
    closed = true
    prepareJob?.cancel()
    positionJob?.cancel()
    positionJob = null
    positionWriter.close()
    releasePlayer()
    _uiState.update { it.copy(phase = VideoPlaybackPhase.Closed) }
  }

  private fun persistPosition(current: VideoPlayerEngine, force: Boolean) {
    positionWriter.enqueue(
      PlaybackPositionSnapshot(
        positionMs = current.currentPosition.coerceAtLeast(0L),
        durationMs = current.duration.coerceAtLeast(0L),
      ),
      force = force,
    )
  }

  private fun releasePlayer() {
    VideoStreamingDiagnostics.rebufferEnded(clock.elapsedRealtime())
    VideoStreamingDiagnostics.seekAbandoned()
    val current = engine
    if (current != null) {
      currentListener?.let(current::removeListener)
      currentListener = null
      current.stop()
      current.release()
      engine = null
      _player.value = null
      VideoPlayerDiagnostics.playerReleased()
    }
    playbackGateway.closePlayback(request)
  }

  private fun classify(error: VideoPlayerEngineError): VideoPlaybackErrorKind {
    return classifyVideoPlaybackFailure(
      errorCode = error.errorCode,
      details = "${error.errorCode} ${error.details}",
      cause = error.cause,
    )
  }

  override fun onCleared() {
    engine?.let { persistPosition(it, force = true) }
    closed = true
    prepareJob?.cancel()
    positionJob?.cancel()
    positionWriter.close()
    releasePlayer()
    super.onCleared()
  }

  class Factory(
    private val request: VideoPlaybackRequest,
    private val mediaAccess: MediaAccessCoordinator,
  ) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
      VideoPlayerViewModel(request, mediaAccess) as T
  }
}
