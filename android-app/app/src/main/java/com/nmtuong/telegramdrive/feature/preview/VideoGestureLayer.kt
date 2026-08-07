package com.nmtuong.telegramdrive.feature.preview

import android.content.Context
import android.media.AudioManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.abs

@Composable
internal fun VideoGestureLayer(
  player: androidx.media3.common.Player?,
  phase: VideoPlaybackPhase,
  controlsVisible: Boolean,
  onSetControlsVisible: (Boolean) -> Unit,
  onSeekBy: (Long) -> Unit,
) {
  if (!allowsPlaybackGestures(phase)) return

  val context = LocalContext.current
  val density = LocalDensity.current
  val touchSlop = with(density) { 20.dp.toPx() }

  var accumulatedSeek by remember { mutableLongStateOf(0L) }
  var showSeekFeedback by remember { mutableStateOf(false) }
  var seekDirection by remember { mutableIntStateOf(0) }

  var showBrightness by remember { mutableStateOf(false) }
  var brightnessPercent by remember { mutableIntStateOf(0) }
  var showVolume by remember { mutableStateOf(false) }
  var volumePercent by remember { mutableIntStateOf(0) }

  var isDragging by remember { mutableStateOf(false) }
  var dragType by remember { mutableStateOf(DragType.NONE) }
  var startY by remember { mutableFloatStateOf(0f) }
  var startValue by remember { mutableFloatStateOf(0f) }

  LaunchedEffect(accumulatedSeek) {
    if (accumulatedSeek != 0L) {
      showSeekFeedback = true
      delay(800)
      if (allowsSeekGestures(phase)) {
          onSeekBy(accumulatedSeek)
          onSetControlsVisible(true)
      }
      accumulatedSeek = 0L
      showSeekFeedback = false
    }
  }

  LaunchedEffect(showBrightness, isDragging) {
      if (showBrightness && !isDragging) {
          delay(1000)
          showBrightness = false
      }
  }
  LaunchedEffect(showVolume, isDragging) {
      if (showVolume && !isDragging) {
          delay(1000)
          showVolume = false
      }
  }

  Box(
    modifier = Modifier
      .fillMaxSize()
      .semantics { contentDescription = "Video playback surface" }
      .pointerInput(player, phase) {
        detectTapGestures(
          onTap = {
              if (dragType == DragType.NONE) {
                  onSetControlsVisible(!controlsVisible)
              }
          },
          onDoubleTap = { offset ->
            if (dragType != DragType.NONE) return@detectTapGestures
            if (allowsSeekGestures(phase)) {
              if (offset.x < size.width / 2f) {
                if (seekDirection == 1) accumulatedSeek = 0L
                accumulatedSeek -= 10_000L
                seekDirection = -1
              } else {
                if (seekDirection == -1) accumulatedSeek = 0L
                accumulatedSeek += 10_000L
                seekDirection = 1
              }
            }
          },
        )
      }
      .pointerInput(player, phase) {
          detectDragGestures(
              onDragStart = { offset ->
                  dragType = DragType.NONE
                  isDragging = true
                  startY = offset.y
              },
              onDragEnd = {
                  isDragging = false
                  dragType = DragType.NONE
                  if (showBrightness) { showBrightness = false; showBrightness = true }
                  if (showVolume) { showVolume = false; showVolume = true }
              },
              onDragCancel = {
                  isDragging = false
                  dragType = DragType.NONE
                  showBrightness = false
                  showVolume = false
              },
              onDrag = { change, _ ->
                  change.consume()
                  if (dragType == DragType.NONE) {
                      if (abs(change.position.y - startY) > touchSlop) {
                          if (change.position.x < size.width / 2f) {
                              dragType = DragType.BRIGHTNESS
                              startValue = getCurrentBrightness(context)
                              showBrightness = true
                          } else {
                              dragType = DragType.VOLUME
                              startValue = getCurrentVolume(context)
                              showVolume = true
                          }
                      }
                  }

                  if (dragType != DragType.NONE) {
                      val deltaY = startY - change.position.y
                      val deltaPercent = deltaY / size.height

                      if (dragType == DragType.BRIGHTNESS) {
                          val newValue = (startValue + deltaPercent).coerceIn(0f, 1f)
                          setBrightness(context, newValue)
                          brightnessPercent = (newValue * 100).toInt()
                      } else if (dragType == DragType.VOLUME) {
                          val newValue = (startValue + deltaPercent).coerceIn(0f, 1f)
                          setVolume(context, newValue)
                          volumePercent = (newValue * 100).toInt()
                      }
                  }
              }
          )
      },
  ) {
      AnimatedVisibility(
          visible = showSeekFeedback,
          enter = fadeIn(tween(150)),
          exit = fadeOut(tween(300)),
          modifier = Modifier.align(if (seekDirection == -1) Alignment.CenterStart else Alignment.CenterEnd)
      ) {
          Box(
              modifier = Modifier
                  .fillMaxHeight()
                  .width(LocalConfiguration.current.screenWidthDp.dp / 2)
                  .background(Color.White.copy(alpha = 0.15f), RoundedCornerShape(topStart = if (seekDirection == -1) 0.dp else 100.dp, bottomStart = if (seekDirection == -1) 0.dp else 100.dp, topEnd = if (seekDirection == 1) 0.dp else 100.dp, bottomEnd = if (seekDirection == 1) 0.dp else 100.dp)),
              contentAlignment = Alignment.Center
          ) {
              Text(
                  text = if (accumulatedSeek > 0) "+${accumulatedSeek / 1000}s" else "${accumulatedSeek / 1000}s",
                  color = Color.White,
                  fontSize = 24.sp
              )
          }
      }

      if (showBrightness || showVolume) {
          Box(
              modifier = Modifier
                  .align(Alignment.Center)
                  .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                  .padding(16.dp),
              contentAlignment = Alignment.Center
          ) {
              Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                  Text(
                      text = if (showBrightness) "☀️" else "🔊",
                      fontSize = 32.sp
                  )
                  Text(
                      text = "${if (showBrightness) brightnessPercent else volumePercent}%",
                      color = Color.White,
                      fontSize = 18.sp
                  )
              }
          }
      }
  }
}

private enum class DragType { NONE, BRIGHTNESS, VOLUME }

private fun getCurrentBrightness(context: Context): Float {
    val window = context.findActivity()?.window ?: return 0.5f
    val lp = window.attributes
    return if (lp.screenBrightness < 0) 0.5f else lp.screenBrightness
}

private fun setBrightness(context: Context, value: Float) {
    val window = context.findActivity()?.window ?: return
    val lp = window.attributes
    lp.screenBrightness = value
    window.attributes = lp
}

private fun getCurrentVolume(context: Context): Float {
    val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
    return current.toFloat() / max.toFloat()
}

private fun setVolume(context: Context, value: Float) {
    val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    val vol = (value * max).toInt()
    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, vol, 0)
}