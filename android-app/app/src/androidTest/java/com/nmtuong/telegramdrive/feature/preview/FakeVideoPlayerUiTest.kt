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
    submitAuthStep("Phone number", "123")
    submitAuthStep("Authentication code", "123")
    submitAuthStep("Two-step verification password", "1")

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

  private fun submitAuthStep(label: String, value: String) {
    composeRule.onNodeWithText(label).assertIsDisplayed()
    composeRule.onNode(hasSetTextAction()).performTextInput(value)
    composeRule.onNodeWithText("Continue").performClick()
  }
}
