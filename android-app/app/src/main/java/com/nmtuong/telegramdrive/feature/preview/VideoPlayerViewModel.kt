package com.nmtuong.telegramdrive.feature.preview

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.nmtuong.telegramdrive.data.MediaAccessCoordinator
import com.nmtuong.telegramdrive.domain.VideoPlaybackRequest
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicInteger
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

internal data class VideoPlayerDiagnosticsSnapshot(
  val playerCreateCount: Int,
  val playerReleaseCount: Int,
  val activePlayerCount: Int,
)

internal object VideoPlayerDiagnostics {
  private val playerCreateCount = AtomicInteger(0)
  private val playerReleaseCount = AtomicInteger(0)
  private val activePlayerCount = AtomicInteger(0)

  fun playerCreated() {
    playerCreateCount.incrementAndGet()
    activePlayerCount.incrementAndGet()
  }

  fun playerReleased() {
    playerReleaseCount.incrementAndGet()
    activePlayerCount.updateAndGet { count -> (count - 1).coerceAtLeast(0) }
  }

  fun snapshot(): VideoPlayerDiagnosticsSnapshot = VideoPlayerDiagnosticsSnapshot(
    playerCreateCount = playerCreateCount.get(),
    playerReleaseCount = playerReleaseCount.get(),
    activePlayerCount = activePlayerCount.get(),
  )

  fun resetForTests() {
    playerCreateCount.set(0)
    playerReleaseCount.set(0)
    activePlayerCount.set(0)
  }
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

@androidx.annotation.OptIn(UnstableApi::class)
class VideoPlayerViewModel(
  val request: VideoPlaybackRequest,
  private val mediaAccess: MediaAccessCoordinator,
) : ViewModel() {
  private val _player = MutableStateFlow<ExoPlayer?>(null)
  val player: StateFlow<ExoPlayer?> = _player.asStateFlow()
  private val _uiState = MutableStateFlow(VideoPlayerUiState())
  val uiState: StateFlow<VideoPlayerUiState> = _uiState.asStateFlow()
  private val generation = AtomicLong(0L)
  private var prepareJob: Job? = null
  private var positionJob: Job? = null
  private var resumeOnForeground = false
  private var closed = false
  private val positionWriter = PlaybackPositionWriter(viewModelScope) { snapshot ->
    mediaAccess.savePlaybackPosition(request, snapshot.positionMs, snapshot.durationMs)
  }
  private val listener = object : Player.Listener {
    override fun onPlaybackStateChanged(playbackState: Int) {
      val current = _player.value ?: return
      _uiState.update { state ->
        state.copy(
          phase = when (playbackState) {
            Player.STATE_BUFFERING -> if (state.firstFrameRendered) VideoPlaybackPhase.Rebuffering else VideoPlaybackPhase.InitialBuffering
            Player.STATE_READY -> if (current.isPlaying) VideoPlaybackPhase.Playing else VideoPlaybackPhase.Paused
            Player.STATE_ENDED -> VideoPlaybackPhase.Ended
            else -> state.phase
          },
          positionMs = current.currentPosition.coerceAtLeast(0L),
          durationMs = current.duration.takeIf { it > 0L } ?: request.durationSeconds * 1_000L,
          pendingSeekPositionMs = if (playbackState == Player.STATE_READY) null else state.pendingSeekPositionMs,
        )
      }
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
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
      _uiState.update { it.copy(firstFrameRendered = true) }
    }

    override fun onPositionDiscontinuity(
      oldPosition: Player.PositionInfo,
      newPosition: Player.PositionInfo,
      reason: Int,
    ) {
      if (reason == Player.DISCONTINUITY_REASON_SEEK) {
        _uiState.update { it.copy(positionMs = newPosition.positionMs.coerceAtLeast(0L)) }
      }
    }

    override fun onPlayerError(error: PlaybackException) {
      if (isExpectedVideoPlaybackCancellation(error)) {
        _uiState.update { state ->
          state.copy(
            phase = if (_player.value?.isPlaying == true) VideoPlaybackPhase.Playing else VideoPlaybackPhase.Paused,
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
    if (closed || _player.value != null) return
    val currentGeneration = generation.incrementAndGet()
    val created = ExoPlayer.Builder(context.applicationContext).build()
    VideoPlayerDiagnostics.playerCreated()
    created.addListener(listener)
    _player.value = created
    _uiState.value = VideoPlayerUiState(
      phase = VideoPlaybackPhase.Opening,
      durationMs = request.durationSeconds * 1_000L,
      controlsVisible = true,
    )
    prepareJob?.cancel()
    prepareJob = viewModelScope.launch {
      try {
        _uiState.update { it.copy(phase = VideoPlaybackPhase.PreparingSource) }
        val savedPosition = mediaAccess.loadPlaybackPosition(request)
        val resume = resumePositionMs(savedPosition, request.durationSeconds * 1_000L)
        _uiState.update { it.copy(resumePositionMs = resume) }
        val localPath = mediaAccess.resolveVideoLocalPath(request)
        if (currentGeneration != generation.get() || closed) return@launch
        if (localPath != null) {
          created.setMediaItem(MediaItem.fromUri(Uri.fromFile(java.io.File(localPath))))
          _uiState.update { it.copy(sourceLabel = "local") }
        } else {
          val mediaItem = MediaItem.Builder()
            .setUri("tdlib://${request.playbackKey}".toUri())
            .setMimeType(request.mimeType)
            .build()
          val sourceFactory: DataSource.Factory = mediaAccess.videoDataSourceFactory(request)
          created.setMediaSource(ProgressiveMediaSource.Factory(sourceFactory).createMediaSource(mediaItem))
          _uiState.update { it.copy(sourceLabel = "streaming") }
        }
        if (currentGeneration != generation.get() || closed) return@launch
        if (resume > 0L) created.seekTo(resume)
        created.playWhenReady = true
        created.prepare()
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        if (currentGeneration == generation.get() && !closed) {
          _uiState.update { it.copy(phase = VideoPlaybackPhase.FatalError, error = VideoPlaybackErrorKind.UnknownPlaybackFailure) }
        }
      }
    }
    if (positionJob == null) {
      positionJob = viewModelScope.launch {
        var nextPersistenceAt = 0L
        while (isActive && !closed) {
          delay(250L)
          val current = _player.value ?: continue
          val now = SystemClock.elapsedRealtime()
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
    val current = _player.value ?: return
    if (current.isPlaying) {
      current.pause()
      persistPosition(current, force = true)
    } else {
      current.play()
    }
    setControlsVisible(true)
  }

  fun seekTo(positionMs: Long) {
    val current = _player.value ?: return
    val target = positionMs.coerceIn(0L, current.duration.takeIf { it > 0L } ?: Long.MAX_VALUE)
    val wasPlaying = current.isPlaying
    val resolvesImmediately = !wasPlaying || current.bufferedPosition >= target
    current.seekTo(target)
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
    val current = _player.value ?: return
    seekTo(current.currentPosition + deltaMs)
  }

  fun replay() = seekTo(0L).also { _player.value?.play() }

  fun setControlsVisible(visible: Boolean) {
    _uiState.update { it.copy(controlsVisible = visible) }
  }

  fun onStop() {
    val current = _player.value
    resumeOnForeground = current?.isPlaying == true
    current?.pause()
    current?.let { persistPosition(it, force = true) }
  }

  fun onStart() {
    if (resumeOnForeground && _uiState.value.phase != VideoPlaybackPhase.Ended) _player.value?.play()
    resumeOnForeground = false
  }

  fun retry(context: Context) {
    _player.value?.let { persistPosition(it, force = true) }
    prepareJob?.cancel()
    releasePlayer()
    closed = false
    _uiState.value = _uiState.value.copy(phase = VideoPlaybackPhase.Opening, error = null, firstFrameRendered = false)
    initialize(context)
  }

  fun closePlayback() {
    if (closed) return
    _player.value?.let { persistPosition(it, force = true) }
    closed = true
    prepareJob?.cancel()
    positionJob?.cancel()
    positionJob = null
    positionWriter.close()
    releasePlayer()
    _uiState.update { it.copy(phase = VideoPlaybackPhase.Closed) }
  }

  private fun persistPosition(current: ExoPlayer, force: Boolean) {
    positionWriter.enqueue(
      PlaybackPositionSnapshot(
        positionMs = current.currentPosition.coerceAtLeast(0L),
        durationMs = current.duration.coerceAtLeast(0L),
      ),
      force = force,
    )
  }

  private fun releasePlayer() {
    val current = _player.value
    if (current != null) {
      current.removeListener(listener)
      current.stop()
      current.release()
      _player.value = null
      VideoPlayerDiagnostics.playerReleased()
    }
    mediaAccess.closeVideoPlayback(request)
  }

  private fun classify(error: PlaybackException): VideoPlaybackErrorKind {
    return classifyVideoPlaybackFailure(
      errorCode = error.errorCode,
      details = "${error.errorCode} ${error.message.orEmpty()}",
      cause = error.cause,
    )
  }

  override fun onCleared() {
    _player.value?.let { persistPosition(it, force = true) }
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
