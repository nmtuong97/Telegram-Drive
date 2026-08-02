package com.nmtuong.telegramdrive.feature.preview

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.nmtuong.telegramdrive.R
import java.io.File

@OptIn(UnstableApi::class)
@Composable
fun AudioPreviewScreen(path: String, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val file = remember(path) { File(path) }
    if (!file.isFile) {
        Column(Modifier.fillMaxSize()) {
            TextButton(onClick = onBack, modifier = Modifier.padding(8.dp)) { Text(stringResource(R.string.back)) }
            Text(stringResource(R.string.media_error), modifier = Modifier.padding(16.dp))
        }
        return
    }

    val player = remember(path) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
            prepare()
        }
    }
    var playbackFailed by remember(path) { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val releaseGuard = remember(player) { VideoPlayerReleaseGuard { player.stop(); player.release() } }
    DisposableEffect(releaseGuard, lifecycleOwner) {
        val lifecycleObserver = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) player.pause()
        }
        val playerListener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) { playbackFailed = true }
        }
        lifecycleOwner.lifecycle.addObserver(lifecycleObserver)
        player.addListener(playerListener)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(lifecycleObserver)
            player.removeListener(playerListener)
            releaseGuard.release()
        }
    }

    Column(Modifier.fillMaxSize()) {
        TextButton(onClick = onBack, modifier = Modifier.padding(8.dp)) { Text(stringResource(R.string.back)) }
        if (playbackFailed) {
            Text(stringResource(R.string.media_error), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
        }
        AndroidView(
            factory = { PlayerView(it).apply { this.player = player; useController = true } },
            modifier = Modifier.fillMaxSize(),
        )
    }
}
