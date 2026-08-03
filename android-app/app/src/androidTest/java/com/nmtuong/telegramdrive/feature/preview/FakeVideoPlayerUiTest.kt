package com.nmtuong.telegramdrive.feature.preview

import android.content.pm.ActivityInfo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nmtuong.telegramdrive.MainActivity
import org.junit.Assert.assertEquals
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FakeVideoPlayerUiTest {
  @get:Rule
  val composeRule = createAndroidComposeRule<MainActivity>()

  @After
  fun tearDown() {
    composeRule.activity.runOnUiThread {
      composeRule.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }
  }

  @Test
  fun opensPlayerWithControlsSurvivesRotationAndReturnsToGallery() {
    signInIfNeeded()

    composeRule.waitUntil(10_000L) {
      composeRule.onAllNodesWithText("demo.mp4").fetchSemanticsNodes().isNotEmpty()
    }
    composeRule.onNodeWithText("demo.mp4").performClick()

    composeRule.waitUntil(10_000L) {
      composeRule.onAllNodesWithContentDescription("Seek position").fetchSemanticsNodes().isNotEmpty()
    }
    composeRule.onNodeWithText("demo.mp4").assertIsDisplayed()
    composeRule.onNodeWithContentDescription("Seek position").assertIsDisplayed()
    composeRule.onNodeWithContentDescription("Seek back 10 seconds").assertIsDisplayed()
    composeRule.onNodeWithContentDescription("Seek forward 10 seconds").assertIsDisplayed()

    composeRule.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
    composeRule.waitForIdle()
    composeRule.onNodeWithText("demo.mp4").assertIsDisplayed()
    composeRule.onNodeWithContentDescription("Seek position").assertIsDisplayed()

    composeRule.activity.runOnUiThread {
      composeRule.activity.onBackPressedDispatcher.onBackPressed()
    }
    composeRule.waitUntil(10_000L) {
      composeRule.onAllNodesWithText("Saved Media").fetchSemanticsNodes().isNotEmpty()
    }
    composeRule.onNodeWithText("Saved Media").assertIsDisplayed()
    composeRule.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
  }

  @Test
  fun reopeningTheSameVideoFiveTimesReleasesEachSessionOwner() {
    VideoPlayerDiagnostics.resetForTests()
    signInIfNeeded()

    composeRule.waitUntil(10_000L) {
      composeRule.onAllNodesWithText("demo.mp4").fetchSemanticsNodes().isNotEmpty()
    }

    repeat(5) {
      composeRule.onNodeWithText("demo.mp4").performClick()
      composeRule.waitUntil(10_000L) {
        composeRule.onAllNodesWithContentDescription("Seek position").fetchSemanticsNodes().isNotEmpty()
      }
      composeRule.activity.runOnUiThread {
        composeRule.activity.onBackPressedDispatcher.onBackPressed()
      }
      composeRule.waitUntil(10_000L) {
        composeRule.onAllNodesWithText("Saved Media").fetchSemanticsNodes().isNotEmpty()
      }
    }

    val diagnostics = VideoPlayerDiagnostics.snapshot()
    assertEquals(5, diagnostics.playerCreateCount)
    assertEquals(5, diagnostics.playerReleaseCount)
    assertEquals(0, diagnostics.activePlayerCount)
  }

  private fun submitAuthStep(label: String, value: String) {
    composeRule.onNodeWithText(label).assertIsDisplayed()
    composeRule.onNode(hasSetTextAction()).performTextInput(value)
    composeRule.onNodeWithText("Continue").performClick()
  }

  private fun signInIfNeeded() {
    composeRule.waitUntil(10_000L) {
      hasText("demo.mp4") ||
        hasText("Phone number") ||
        hasText("Authentication code") ||
        hasText("Two-step verification password")
    }
    if (hasText("demo.mp4")) return
    if (hasText("Phone number")) submitAuthStep("Phone number", "123")
    if (hasText("Authentication code")) submitAuthStep("Authentication code", "123")
    if (hasText("Two-step verification password")) {
      submitAuthStep("Two-step verification password", "1")
    }
  }

  private fun hasText(value: String): Boolean =
    composeRule.onAllNodesWithText(value).fetchSemanticsNodes().isNotEmpty()
}
