package com.nmtuong.telegramdrive.feature.preview

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nmtuong.telegramdrive.ui.theme.TelegramDriveTheme
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertTrue
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VideoGestureLayerTest {
  @get:Rule
  val composeRule = createComposeRule()

  @Test
  fun playingControlsAutoHideAndPhysicalTapsRestoreThemAcrossTenCycles() {
    composeRule.mainClock.autoAdvance = false
    var controlsVisible by mutableStateOf(true)
    composeRule.setContent {
      LaunchedEffect(controlsVisible) {
        if (shouldAutoHideControls(VideoPlaybackPhase.Playing, controlsVisible)) {
          val deadlineNanos = withFrameNanos { it } + VIDEO_CONTROLS_AUTO_HIDE_DELAY_MS * 1_000_000L
          while (withFrameNanos { it } < deadlineNanos) Unit
          controlsVisible = false
        }
      }
      Box(Modifier.fillMaxSize()) {
        VideoGestureLayer(
          player = null,
          phase = VideoPlaybackPhase.Playing,
          controlsVisible = controlsVisible,
          onSetControlsVisible = { controlsVisible = it },
          onSeekBy = {},
        )
        if (controlsVisible) Text("Playback controls")
      }
    }
    composeRule.mainClock.advanceTimeByFrame()
    composeRule.mainClock.advanceTimeByFrame()

    repeat(10) {
      assertTrue(hasText("Playback controls"))
      composeRule.mainClock.advanceTimeBy(VIDEO_CONTROLS_AUTO_HIDE_DELAY_MS + 1L)
      composeRule.mainClock.advanceTimeByFrame()
      assertTrue(!hasText("Playback controls"))

      composeRule.onNodeWithContentDescription("Video playback surface")
        .performTouchInput { click(center) }
      composeRule.mainClock.advanceTimeBy(500L)
      composeRule.mainClock.advanceTimeByFrame()
      assertTrue(hasText("Playback controls"))

      composeRule.onNodeWithContentDescription("Video playback surface")
        .performTouchInput { click(center) }
      composeRule.mainClock.advanceTimeBy(500L)
      composeRule.mainClock.advanceTimeByFrame()
      assertTrue(!hasText("Playback controls"))

      composeRule.runOnIdle { controlsVisible = true }
      composeRule.mainClock.advanceTimeByFrame()
      composeRule.mainClock.advanceTimeByFrame()
    }
  }

  @Test
  fun pausedControlsDoNotAutoHideAndFatalErrorHasNoGestureSurface() {
    composeRule.mainClock.autoAdvance = false
    var controlsVisible by mutableStateOf(true)
    var phase by mutableStateOf(VideoPlaybackPhase.Paused)
    composeRule.setContent {
      LaunchedEffect(phase, controlsVisible) {
        if (shouldAutoHideControls(phase, controlsVisible)) {
          kotlinx.coroutines.delay(VIDEO_CONTROLS_AUTO_HIDE_DELAY_MS)
          controlsVisible = false
        }
      }
      Box(Modifier.fillMaxSize()) {
        VideoGestureLayer(
          player = null,
          phase = phase,
          controlsVisible = controlsVisible,
          onSetControlsVisible = { controlsVisible = it },
          onSeekBy = {},
        )
        if (controlsVisible) Text("Playback controls")
      }
    }

    composeRule.mainClock.advanceTimeBy(VIDEO_CONTROLS_AUTO_HIDE_DELAY_MS * 2L)
    composeRule.waitForIdle()
    assertTrue(hasText("Playback controls"))

    composeRule.runOnIdle { phase = VideoPlaybackPhase.FatalError }
    composeRule.mainClock.advanceTimeByFrame()
    assertTrue(!hasContentDescription("Video playback surface"))
  }

  @Test
  fun recoverableErrorKeepsRetryAndBackReachable() {
    var retries = 0
    var backs = 0
    composeRule.setContent {
      TelegramDriveTheme {
        ErrorOverlay(
          kind = VideoPlaybackErrorKind.Offline,
          onRetry = { retries++ },
          onBack = { backs++ },
        )
      }
    }

    composeRule.onNodeWithText("Retry").performClick()
    composeRule.onNodeWithText("Back").performClick()
    composeRule.runOnIdle {
      assertTrue(retries == 1)
      assertTrue(backs == 1)
    }
  }

  private fun hasText(value: String): Boolean =
    composeRule.onAllNodesWithText(value).fetchSemanticsNodes().isNotEmpty()

  private fun hasContentDescription(value: String): Boolean =
    composeRule.onAllNodesWithContentDescription(value).fetchSemanticsNodes().isNotEmpty()
}
