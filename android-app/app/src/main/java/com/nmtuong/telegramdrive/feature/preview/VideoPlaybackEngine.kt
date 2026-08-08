package com.nmtuong.telegramdrive.feature.preview

import android.content.Context
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import java.io.File

/** Minimal Media3 boundary used to make playback lifecycle tests deterministic. */
internal interface VideoPlayerEngine {
  val player: Player?
  val currentPosition: Long
  val duration: Long
  val bufferedPosition: Long
  val isPlaying: Boolean
  var playWhenReady: Boolean

  fun addListener(listener: VideoPlayerEngineListener)
  fun removeListener(listener: VideoPlayerEngineListener)
  fun setLocalSource(path: String)
  fun setStreamingSource(uri: String, mimeType: String?, dataSourceFactory: DataSource.Factory)
  fun seekTo(positionMs: Long)
  fun prepare()
  fun play()
  fun pause()
  fun stop()
  fun release()
}

internal interface VideoPlayerEngineListener {
  fun onPlaybackStateChanged(playbackState: Int)
  fun onIsPlayingChanged(isPlaying: Boolean)
  fun onRenderedFirstFrame()
  fun onPositionDiscontinuity(positionMs: Long, reason: Int)
  fun onPlayerError(error: VideoPlayerEngineError)
}

internal data class VideoPlayerEngineError(
  val errorCode: Int,
  val details: String,
  val cause: Throwable?,
)

internal fun interface VideoPlayerEngineFactory {
  fun create(context: Context?): VideoPlayerEngine
}

internal fun interface VideoPlaybackClock {
  fun elapsedRealtime(): Long
}

internal object AndroidVideoPlaybackClock : VideoPlaybackClock {
  override fun elapsedRealtime(): Long = SystemClock.elapsedRealtime()
}

internal object Media3VideoPlayerEngineFactory : VideoPlayerEngineFactory {
  override fun create(context: Context?): VideoPlayerEngine =
    Media3VideoPlayerEngine(requireNotNull(context) { "Media3 playback requires an Android context" })
}

@OptIn(UnstableApi::class)
private class Media3VideoPlayerEngine(context: Context) : VideoPlayerEngine {
  private val delegate = ExoPlayer.Builder(context.applicationContext).build().apply { setAudioAttributes(androidx.media3.common.AudioAttributes.DEFAULT, true) }
  private var listener: VideoPlayerEngineListener? = null
  private var listenerAttached = false
  private val bridge = object : Player.Listener {
    override fun onPlaybackStateChanged(playbackState: Int) {
      listener?.onPlaybackStateChanged(playbackState)
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
      listener?.onIsPlayingChanged(isPlaying)
    }

    override fun onRenderedFirstFrame() {
      listener?.onRenderedFirstFrame()
    }

    override fun onPositionDiscontinuity(
      oldPosition: Player.PositionInfo,
      newPosition: Player.PositionInfo,
      reason: Int,
    ) {
      listener?.onPositionDiscontinuity(newPosition.positionMs, reason)
    }

    override fun onPlayerError(error: PlaybackException) {
      listener?.onPlayerError(
        VideoPlayerEngineError(
          errorCode = error.errorCode,
          details = error.message.orEmpty(),
          cause = error.cause,
        ),
      )
    }
  }

  override val player: Player = delegate
  override val currentPosition: Long get() = delegate.currentPosition
  override val duration: Long get() = delegate.duration
  override val bufferedPosition: Long get() = delegate.bufferedPosition
  override val isPlaying: Boolean get() = delegate.isPlaying
  override var playWhenReady: Boolean
    get() = delegate.playWhenReady
    set(value) {
      delegate.playWhenReady = value
    }

  override fun addListener(listener: VideoPlayerEngineListener) {
    this.listener = listener
    if (!listenerAttached) {
      delegate.addListener(bridge)
      listenerAttached = true
    }
  }

  override fun removeListener(listener: VideoPlayerEngineListener) {
    if (this.listener !== listener) return
    this.listener = null
    if (listenerAttached) {
      delegate.removeListener(bridge)
      listenerAttached = false
    }
  }

  override fun setLocalSource(path: String) {
    delegate.setMediaItem(MediaItem.fromUri(File(path).toURI().toString()))
  }

  override fun setStreamingSource(uri: String, mimeType: String?, dataSourceFactory: DataSource.Factory) {
    val item = MediaItem.Builder().setUri(uri).setMimeType(mimeType).build()
    delegate.setMediaSource(ProgressiveMediaSource.Factory(dataSourceFactory).createMediaSource(item))
  }

  override fun seekTo(positionMs: Long) = delegate.seekTo(positionMs)
  override fun prepare() = delegate.prepare()
  override fun play() = delegate.play()
  override fun pause() = delegate.pause()
  override fun stop() = delegate.stop()
  override fun release() = delegate.release()
}
