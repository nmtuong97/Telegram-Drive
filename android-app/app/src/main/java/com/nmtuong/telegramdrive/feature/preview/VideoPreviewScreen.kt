package com.nmtuong.telegramdrive.feature.preview

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.annotation.OptIn
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import com.nmtuong.telegramdrive.R
import com.nmtuong.telegramdrive.domain.VideoPlaybackRequest
import java.io.File
import kotlinx.coroutines.delay

@Composable
@OptIn(UnstableApi::class)
fun VideoPreviewScreen(
  request: VideoPlaybackRequest,
  mediaAccess: com.nmtuong.telegramdrive.data.MediaAccessCoordinator,
  onBack: () -> Unit,
  viewModel: VideoPlayerViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
    key = request.playbackKey,
    factory = VideoPlayerViewModel.Factory(request, mediaAccess),
  ),
) {
  val context = LocalContext.current
  val lifecycleOwner = LocalLifecycleOwner.current
  val player by viewModel.player.collectAsStateWithLifecycle()
  val state by viewModel.uiState.collectAsStateWithLifecycle()

  LaunchedEffect(viewModel, context) { viewModel.initialize(context) }
  DisposableEffect(viewModel, lifecycleOwner) {
    val observer = LifecycleEventObserver { _, event ->
      when (event) {
        Lifecycle.Event.ON_STOP -> viewModel.onStop()
        Lifecycle.Event.ON_START -> viewModel.onStart()
        else -> Unit
      }
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
  }
  BackHandler {
    viewModel.closePlayback()
    onBack()
  }
  LaunchedEffect(state.phase, state.controlsVisible) {
    if (state.phase == VideoPlaybackPhase.Playing && state.controlsVisible) {
      delay(3_500L)
      viewModel.setControlsVisible(false)
    }
  }

  Box(
    modifier = Modifier
      .fillMaxSize()
      .background(Color.Black),
  ) {
    AndroidView(
      factory = { playerContext ->
        PlayerView(playerContext).apply {
          useController = false
          setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
        }
      },
      update = { view -> view.player = player },
      modifier = Modifier.fillMaxSize(),
    )

    if (!state.firstFrameRendered) {
      VideoPoster(request)
    }

    Box(
      modifier = Modifier
        .fillMaxSize()
        .pointerInput(player, state.phase) {
          detectTapGestures(
            onTap = { viewModel.setControlsVisible(!state.controlsVisible) },
            onDoubleTap = { offset ->
              viewModel.seekBy(if (offset.x < size.width / 2f) -10_000L else 10_000L)
              viewModel.setControlsVisible(true)
            },
          )
        },
    )

    val controlsPersistent = state.phase in setOf(
      VideoPlaybackPhase.Paused,
      VideoPlaybackPhase.RecoverableError,
      VideoPlaybackPhase.FatalError,
      VideoPlaybackPhase.Ended,
    )
    AnimatedVisibility(
      visible = state.controlsVisible || controlsPersistent,
      modifier = Modifier.fillMaxSize(),
    ) {
      Column(Modifier.fillMaxSize()) {
        TopControls(
          title = request.displayName,
          onBack = {
            viewModel.closePlayback()
            onBack()
          },
        )
        Spacer(Modifier.weight(1f))
        if (state.resumePositionMs > 0L && state.positionMs <= state.resumePositionMs + 1_000L) {
          Surface(
            modifier = Modifier.align(Alignment.CenterHorizontally),
            color = Color.Black.copy(alpha = 0.72f),
            shape = RoundedCornerShape(8.dp),
          ) {
            Text(
              text = stringResource(R.string.video_resume_notice, formatClock(state.resumePositionMs)),
              color = Color.White,
              modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
          }
        }
        BottomControls(
          state = state,
          onPlayPause = viewModel::togglePlayPause,
          onSeekBack = { viewModel.seekBy(-10_000L) },
          onSeekForward = { viewModel.seekBy(10_000L) },
          onSeek = viewModel::seekTo,
          onReplay = viewModel::replay,
        )
      }
    }

    if (state.phase == VideoPlaybackPhase.InitialBuffering || state.phase == VideoPlaybackPhase.PreparingSource) {
      LoadingOverlay(message = stringResource(R.string.video_loading))
    } else if (state.phase == VideoPlaybackPhase.Rebuffering || state.phase == VideoPlaybackPhase.Seeking) {
      LoadingOverlay(message = stringResource(R.string.video_rebuffering))
    }

    if (state.phase == VideoPlaybackPhase.RecoverableError || state.phase == VideoPlaybackPhase.FatalError) {
      ErrorOverlay(
        kind = state.error,
        onRetry = { viewModel.retry(context) },
        onBack = {
          viewModel.closePlayback()
          onBack()
        },
      )
    }
  }
}

@Composable
private fun TopControls(title: String, onBack: () -> Unit) {
  val backDescription = stringResource(R.string.back)
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.78f), Color.Transparent)))
      .statusBarsPadding()
      .padding(horizontal = 8.dp, vertical = 8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    IconButton(
      onClick = onBack,
      modifier = Modifier.semantics { contentDescription = backDescription },
    ) {
      PlayerGlyph(PlayerGlyphKind.Back, Color.White)
    }
    Text(
      text = title,
      color = Color.White,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      style = MaterialTheme.typography.titleMedium,
      modifier = Modifier.weight(1f),
    )
  }
}

@Composable
private fun BottomControls(
  state: VideoPlayerUiState,
  onPlayPause: () -> Unit,
  onSeekBack: () -> Unit,
  onSeekForward: () -> Unit,
  onSeek: (Long) -> Unit,
  onReplay: () -> Unit,
) {
  val seekPositionDescription = stringResource(R.string.seek_position)
  val playPauseDescription = stringResource(
    if (state.phase == VideoPlaybackPhase.Playing) R.string.pause else R.string.play,
  )
  val seekBackDescription = stringResource(R.string.seek_back_10)
  val seekForwardDescription = stringResource(R.string.seek_forward_10)
  val replayDescription = stringResource(R.string.replay)
  val duration = state.durationMs.coerceAtLeast(1L)
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.86f))))
      .navigationBarsPadding()
      .padding(horizontal = 16.dp, vertical = 12.dp),
  ) {
    Slider(
      value = state.positionMs.coerceIn(0L, duration).toFloat(),
      onValueChange = { onSeek(it.toLong()) },
      valueRange = 0f..duration.toFloat(),
      modifier = Modifier
        .fillMaxWidth()
        .semantics { contentDescription = seekPositionDescription },
    )
    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      IconButton(
        onClick = onPlayPause,
        modifier = Modifier.semantics { contentDescription = playPauseDescription },
      ) {
        PlayerGlyph(
          if (state.phase == VideoPlaybackPhase.Playing) PlayerGlyphKind.Pause else PlayerGlyphKind.Play,
          Color.White,
        )
      }
      IconButton(onClick = onSeekBack, modifier = Modifier.semantics { contentDescription = seekBackDescription }) {
        Text("-10", color = Color.White, style = MaterialTheme.typography.labelLarge)
      }
      IconButton(onClick = onSeekForward, modifier = Modifier.semantics { contentDescription = seekForwardDescription }) {
        Text("+10", color = Color.White, style = MaterialTheme.typography.labelLarge)
      }
      Text(
        text = "${formatClock(state.positionMs)} / ${formatClock(state.durationMs)}",
        color = Color.White,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.weight(1f),
      )
      if (state.phase == VideoPlaybackPhase.Ended) {
        IconButton(onClick = onReplay, modifier = Modifier.semantics { contentDescription = replayDescription }) {
          Text(stringResource(R.string.replay), color = Color.White, style = MaterialTheme.typography.labelLarge)
        }
      }
    }
  }
}

@Composable
private fun LoadingOverlay(message: String) {
  Column(
    modifier = Modifier.fillMaxSize(),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
  ) {
    CircularProgressIndicator(color = Color.White)
    Spacer(Modifier.size(12.dp))
    Text(message, color = Color.White, style = MaterialTheme.typography.bodyMedium)
  }
}

@Composable
private fun ErrorOverlay(
  kind: VideoPlaybackErrorKind?,
  onRetry: () -> Unit,
  onBack: () -> Unit,
) {
  val message = when (kind) {
    VideoPlaybackErrorKind.Offline -> R.string.video_error_offline
    VideoPlaybackErrorKind.TimeoutOrSlowNetwork -> R.string.video_error_slow_network
    VideoPlaybackErrorKind.TelegramSessionChanged -> R.string.video_error_session
    VideoPlaybackErrorKind.RemoteFileUnavailable -> R.string.video_error_unavailable
    VideoPlaybackErrorKind.CorruptOrIncompleteFile -> R.string.video_error_corrupt
    VideoPlaybackErrorKind.UnsupportedFormatOrDecoder -> R.string.video_error_unsupported
    VideoPlaybackErrorKind.SourceIdentityMismatch -> R.string.video_error_identity
    null, VideoPlaybackErrorKind.UnknownPlaybackFailure -> R.string.video_error_unknown
  }
  Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    Surface(
      modifier = Modifier.padding(24.dp),
      color = Color.Black.copy(alpha = 0.86f),
      shape = RoundedCornerShape(16.dp),
    ) {
      Column(
        modifier = Modifier.padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        Text(stringResource(message), color = Color.White, style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          if (isRetryableVideoPlaybackError(kind)) {
            Surface(onClick = onRetry, color = MaterialTheme.colorScheme.primary, shape = RoundedCornerShape(8.dp)) {
              Text(stringResource(R.string.retry), modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
            }
          }
          Surface(onClick = onBack, color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(8.dp)) {
            Text(stringResource(R.string.back), modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
          }
        }
      }
    }
  }
}

@Composable
private fun VideoPoster(request: VideoPlaybackRequest) {
  val bitmap = remember(request.thumbnailPath, request.minithumbnailData) {
    request.thumbnailPath?.let { BitmapFactory.decodeFile(it) }
      ?: request.minithumbnailData?.let { encoded ->
        runCatching {
          val bytes = Base64.decode(encoded, Base64.DEFAULT)
          BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }.getOrNull()
      }
  }
  if (bitmap != null) {
    Image(bitmap = bitmap.asImageBitmap(), contentDescription = request.displayName, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
  } else {
    Box(Modifier.fillMaxSize().background(Color.Black))
  }
}

private enum class PlayerGlyphKind { Back, Play, Pause }

@Composable
private fun PlayerGlyph(kind: PlayerGlyphKind, color: Color) {
  Canvas(Modifier.size(24.dp)) {
    when (kind) {
      PlayerGlyphKind.Back -> {
        drawLine(color, start = androidx.compose.ui.geometry.Offset(18f, 5f), end = androidx.compose.ui.geometry.Offset(7f, 12f), strokeWidth = 3f)
        drawLine(color, start = androidx.compose.ui.geometry.Offset(7f, 12f), end = androidx.compose.ui.geometry.Offset(18f, 19f), strokeWidth = 3f)
        drawLine(color, start = androidx.compose.ui.geometry.Offset(7f, 12f), end = androidx.compose.ui.geometry.Offset(21f, 12f), strokeWidth = 3f)
      }
      PlayerGlyphKind.Play -> {
        drawPath(Path().apply {
          moveTo(7f, 4f)
          lineTo(19f, 12f)
          lineTo(7f, 20f)
          close()
        }, color)
      }
      PlayerGlyphKind.Pause -> {
        drawRect(color, topLeft = androidx.compose.ui.geometry.Offset(6f, 4f), size = androidx.compose.ui.geometry.Size(4f, 16f))
        drawRect(color, topLeft = androidx.compose.ui.geometry.Offset(14f, 4f), size = androidx.compose.ui.geometry.Size(4f, 16f))
      }
    }
  }
}

private fun formatClock(positionMs: Long): String {
  val totalSeconds = (positionMs.coerceAtLeast(0L) / 1_000L)
  val hours = totalSeconds / 3_600L
  val minutes = (totalSeconds % 3_600L) / 60L
  val seconds = totalSeconds % 60L
  return if (hours > 0L) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
}

/** Legacy local preview entry point used by older library targets. */
@Composable
@OptIn(UnstableApi::class)
fun VideoPreviewScreen(
  path: String,
  onBack: () -> Unit,
  dataSourceFactory: DataSource.Factory? = null,
) {
  androidx.compose.runtime.key(path, dataSourceFactory) {
    val context = LocalContext.current
    val player = remember(path, dataSourceFactory) {
      androidx.media3.exoplayer.ExoPlayer.Builder(context).build().apply {
        if (dataSourceFactory == null) setMediaItem(androidx.media3.common.MediaItem.fromUri(android.net.Uri.fromFile(File(path))))
        else setMediaSource(ProgressiveMediaSource.Factory(dataSourceFactory).createMediaSource(androidx.media3.common.MediaItem.fromUri("tdlib://legacy-video".toUri())))
        prepare()
        playWhenReady = true
      }
    }
    DisposableEffect(player) {
      onDispose { player.stop(); player.release() }
    }
    BackHandler(onBack = onBack)
    Box(Modifier.fillMaxSize().background(Color.Black)) {
      AndroidView(
        factory = { PlayerView(it).apply { useController = true; this.player = player } },
        update = { it.player = player },
        modifier = Modifier.fillMaxSize(),
      )
    }
  }
}
