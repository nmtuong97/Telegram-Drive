package com.nmtuong.telegramdrive.feature.preview

import androidx.compose.foundation.layout.size
import android.content.Context
import android.media.AudioManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import kotlinx.coroutines.withTimeoutOrNull
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

  val latestControlsVisible by androidx.compose.runtime.rememberUpdatedState(controlsVisible)

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
          awaitEachGesture {
              val down = awaitFirstDown(requireUnconsumed = false)
              val interaction = processSingleInteraction(
                  down = down,
                  touchSlop = touchSlop,
                  onDragStart = { startX: Float ->
                      isDragging = true
                      if (startX < size.width / 2f) {
                          startValue = getCurrentBrightness(context)
                          showBrightness = true
                          DragType.BRIGHTNESS
                      } else {
                          startValue = getCurrentVolume(context)
                          showVolume = true
                          DragType.VOLUME
                      }
                  },
                  onDrag = { dragType: DragType, deltaY: Float ->
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
              )

              if (interaction == InteractionResult.DRAG) {
                  isDragging = false
                  if (showBrightness) { showBrightness = false; showBrightness = true }
                  if (showVolume) { showVolume = false; showVolume = true }
              } else if (interaction == InteractionResult.TAP) {
                  val tapTimeout = withTimeoutOrNull(300L) {
                      awaitFirstDown(requireUnconsumed = false)
                  }

                  if (tapTimeout != null) {
                      val secondInteraction = processSingleInteraction(
                          down = tapTimeout,
                          touchSlop = touchSlop,
                          onDragStart = { startX: Float ->
                              isDragging = true
                              if (startX < size.width / 2f) {
                                  startValue = getCurrentBrightness(context)
                                  showBrightness = true
                                  DragType.BRIGHTNESS
                              } else {
                                  startValue = getCurrentVolume(context)
                                  showVolume = true
                                  DragType.VOLUME
                              }
                          },
                          onDrag = { dragType: DragType, deltaY: Float ->
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
                      )

                      if (secondInteraction == InteractionResult.DRAG) {
                          isDragging = false
                          if (showBrightness) { showBrightness = false; showBrightness = true }
                          if (showVolume) { showVolume = false; showVolume = true }
                      } else if (secondInteraction == InteractionResult.TAP) {
                          if (allowsSeekGestures(phase)) {
                              val (newSeek, newDir) = GestureUtils.calculateSeekAccumulation(accumulatedSeek, tapTimeout.position.x < size.width / 2f, seekDirection)
                              accumulatedSeek = newSeek
                              seekDirection = newDir
                          }
                      }
                  } else {
                      onSetControlsVisible(!latestControlsVisible)
                  }
              }
          }
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
                  androidx.compose.foundation.Canvas(Modifier.size(48.dp)) {
                      val color = Color.White
                      if (showBrightness) {
                          drawCircle(color, radius = 8.dp.toPx(), center = center, style = androidx.compose.ui.graphics.drawscope.Stroke(3f))
                          val rayLength = 4.dp.toPx()
                          val rayOffset = 12.dp.toPx()
                          for (i in 0 until 8) {
                              val angle = i * Math.PI / 4
                              val startX = center.x + (rayOffset * kotlin.math.cos(angle)).toFloat()
                              val startY = center.y + (rayOffset * kotlin.math.sin(angle)).toFloat()
                              val endX = center.x + ((rayOffset + rayLength) * kotlin.math.cos(angle)).toFloat()
                              val endY = center.y + ((rayOffset + rayLength) * kotlin.math.sin(angle)).toFloat()
                              drawLine(color, start = androidx.compose.ui.geometry.Offset(startX, startY), end = androidx.compose.ui.geometry.Offset(endX, endY), strokeWidth = 3f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                          }
                      } else {
                          val cx = center.x
                          val cy = center.y
                          val speakerPath = androidx.compose.ui.graphics.Path().apply {
                              moveTo(cx - 12.dp.toPx(), cy - 4.dp.toPx())
                              lineTo(cx - 12.dp.toPx(), cy + 4.dp.toPx())
                              lineTo(cx - 6.dp.toPx(), cy + 4.dp.toPx())
                              lineTo(cx, cy + 10.dp.toPx())
                              lineTo(cx, cy - 10.dp.toPx())
                              lineTo(cx - 6.dp.toPx(), cy - 4.dp.toPx())
                              close()
                          }
                          drawPath(speakerPath, color)

                          drawArc(
                              color = color,
                              startAngle = -60f,
                              sweepAngle = 120f,
                              useCenter = false,
                              topLeft = androidx.compose.ui.geometry.Offset(cx - 4.dp.toPx(), cy - 6.dp.toPx()),
                              size = androidx.compose.ui.geometry.Size(12.dp.toPx(), 12.dp.toPx()),
                              style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                          )
                          drawArc(
                              color = color,
                              startAngle = -60f,
                              sweepAngle = 120f,
                              useCenter = false,
                              topLeft = androidx.compose.ui.geometry.Offset(cx - 8.dp.toPx(), cy - 10.dp.toPx()),
                              size = androidx.compose.ui.geometry.Size(20.dp.toPx(), 20.dp.toPx()),
                              style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                          )
                      }
                  }
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
    if (lp.screenBrightness >= 0) return lp.screenBrightness

    return try {
        val systemBrightness = android.provider.Settings.System.getInt(
            context.contentResolver,
            android.provider.Settings.System.SCREEN_BRIGHTNESS
        )
        // System brightness is usually 0..255
        systemBrightness.toFloat() / 255f
    } catch (e: Exception) {
        0.5f
    }
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

private enum class InteractionResult { TAP, DRAG, CANCELLED }

private suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.processSingleInteraction(
    down: androidx.compose.ui.input.pointer.PointerInputChange,
    touchSlop: Float,
    onDragStart: (Float) -> DragType,
    onDrag: (DragType, Float) -> Unit,
): InteractionResult {
    val startX = down.position.x
    val startY = down.position.y
    var dragType = DragType.NONE
    var result = InteractionResult.TAP

    do {
        val event = awaitPointerEvent()
        val pointer = event.changes.firstOrNull { it.id == down.id }
        if (pointer != null && pointer.pressed) {
            val dx = pointer.position.x - startX
            val dy = pointer.position.y - startY

            if (result == InteractionResult.TAP && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                if (abs(dy) > abs(dx)) {
                    dragType = onDragStart(startX)
                    result = InteractionResult.DRAG
                } else {
                    result = InteractionResult.CANCELLED
                }
            }

            if (result == InteractionResult.DRAG) {
                pointer.consume()
                val deltaY = startY - pointer.position.y
                onDrag(dragType, deltaY)
            }
        }
    } while (event.changes.any { it.pressed })

    return result
}
