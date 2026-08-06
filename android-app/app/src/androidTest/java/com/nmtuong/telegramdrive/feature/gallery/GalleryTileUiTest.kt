package com.nmtuong.telegramdrive.feature.gallery

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nmtuong.telegramdrive.data.local.SavedMediaEntity
import com.nmtuong.telegramdrive.domain.AccountSessionIdentity
import com.nmtuong.telegramdrive.ui.theme.TelegramDriveTheme
import java.util.Locale
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertTrue
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GalleryTileUiTest {
  @get:Rule
  val composeRule = createComposeRule()

  @Test
  fun videoCardShowsFullFilenameMetadataDurationAndAvailability() {
    val name = "2026-07-21_family-trip_camera-export_final-final-2.mp4"
    val item = galleryItemUiModel(
      entity("VIDEO", name, durationSeconds = 342).copy(expectedSizeBytes = 125L * 1024 * 1024, width = 1_920, height = 1_080),
      AccountSessionIdentity(1L, 1L),
      Locale.US,
    )
    val loader = GalleryThumbnailLoader()

    composeRule.setContent {
      TelegramDriveTheme { GalleryTile(item, loader, 200, onClick = {}) }
    }

    composeRule.onNodeWithText(name, useUnmergedTree = true).assertIsDisplayed()
    composeRule.onNodeWithText("5:42", useUnmergedTree = true).assertIsDisplayed()
    composeRule.onNodeWithText("125 MB • 1920×1080", useUnmergedTree = true).assertIsDisplayed()
    composeRule.onNodeWithText("Remote", useUnmergedTree = true).assertIsDisplayed()
    loader.close()
  }

  @Test
  fun imageCardDoesNotShowVideoDuration() {
    val item = galleryItemUiModel(
      entity("IMAGE", "holiday-photo.jpg", durationSeconds = 99),
      AccountSessionIdentity(1L, 1L),
      Locale.US,
    )
    val loader = GalleryThumbnailLoader()

    composeRule.setContent {
      TelegramDriveTheme { GalleryTile(item, loader, 200, onClick = {}) }
    }

    composeRule.onNodeWithText("holiday-photo.jpg", useUnmergedTree = true).assertIsDisplayed()
    assertTrue(composeRule.onAllNodesWithText("1:39", useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
    loader.close()
  }

  private fun entity(mediaType: String, name: String, durationSeconds: Int) = SavedMediaEntity(
    accountId = 1L,
    databaseGeneration = 1L,
    chatId = 1L,
    messageId = name.hashCode().toLong(),
    mediaType = mediaType,
    messageDateEpochSeconds = 1_725_000_000L,
    caption = "",
    stableDisplayName = name,
    mimeType = null,
    width = 100,
    height = 100,
    durationSeconds = durationSeconds,
    telegramFileId = 4,
    originalStableFileIdentity = "test:$name",
    thumbnailFileId = null,
    thumbnailStableFileIdentity = null,
    minithumbnailData = null,
    minithumbnailWidth = 0,
    minithumbnailHeight = 0,
    localFilePath = null,
  )
}
