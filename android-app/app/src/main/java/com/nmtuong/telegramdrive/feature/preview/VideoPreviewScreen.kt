package com.nmtuong.telegramdrive.feature.preview

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import com.nmtuong.telegramdrive.R
import java.io.File
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

@OptIn(UnstableApi::class)
@Composable
fun VideoPreviewScreen(
  path: String,
  onBack: () -> Unit,
  dataSourceFactory: DataSource.Factory? = null,
) {
  BackHandler(onBack = onBack)
  val context = LocalContext.current
  val file = remember(path) { File(path) }
  // A TDLib-backed video may legitimately have no readable partial file after a
  // cancelled/failed attempt. Let the DataSource reconcile and request the range
  // again; only local-file playback requires the path to exist before composition.
  if (!file.isFile && dataSourceFactory == null) {
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
      TextButton(onClick = onBack, modifier = Modifier.padding(8.dp)) { Text(stringResource(R.string.back)) }
      Text(stringResource(R.string.media_error), modifier = Modifier.padding(16.dp))
    }
    return
  }
  var retryToken by remember(path) { mutableIntStateOf(0) }
  val player = remember(path, dataSourceFactory, retryToken) {
    ExoPlayer.Builder(context).build().apply {
      if (dataSourceFactory == null) {
        setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
      } else {
        val mediaItem = MediaItem.fromUri(Uri.parse("tdlib://telegram-media"))
        setMediaSource(ProgressiveMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem))
      }
      prepare()
    }
  }
  var playbackFailed by remember(path, retryToken) { mutableStateOf(false) }
  var buffering by remember(path, retryToken) { mutableStateOf(true) }
  val lifecycleOwner = LocalLifecycleOwner.current
  val releaseGuard = remember(player) { VideoPlayerReleaseGuard { player.stop(); player.release() } }
  DisposableEffect(releaseGuard, lifecycleOwner) {
    val lifecycleObserver = LifecycleEventObserver { _, event ->
      if (event == Lifecycle.Event.ON_STOP) player.pause()
    }
    val playerListener = object : Player.Listener {
      override fun onIsLoadingChanged(isLoading: Boolean) { buffering = isLoading }
      override fun onPlaybackStateChanged(playbackState: Int) {
        buffering = playbackState == Player.STATE_BUFFERING || playbackState == Player.STATE_IDLE
      }
      override fun onPlayerError(error: PlaybackException) {
        playbackFailed = true
        buffering = false
      }
    }
    lifecycleOwner.lifecycle.addObserver(lifecycleObserver)
    player.addListener(playerListener)
    onDispose {
      lifecycleOwner.lifecycle.removeObserver(lifecycleObserver)
      player.removeListener(playerListener)
      releaseGuard.release()
    }
  }
  Column(Modifier.fillMaxSize().safeDrawingPadding()) {
    TextButton(onClick = onBack, modifier = Modifier.padding(8.dp)) { Text(stringResource(R.string.back)) }
    if (buffering && !playbackFailed) {
      Text("Buffering…", modifier = Modifier.padding(horizontal = 16.dp))
    }
    if (playbackFailed) {
      Row(
        modifier = Modifier.padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(stringResource(R.string.media_error), color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
        TextButton(onClick = { retryToken++ }) { Text("Retry") }
      }
    }
    AndroidView(factory = { PlayerView(it).apply { this.player = player } }, modifier = Modifier.fillMaxSize())
  }
}
