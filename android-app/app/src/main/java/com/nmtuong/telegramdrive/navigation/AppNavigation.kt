package com.nmtuong.telegramdrive.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nmtuong.telegramdrive.bootstrap.AppContainer
import com.nmtuong.telegramdrive.domain.AuthorizationState
import com.nmtuong.telegramdrive.domain.AccountSessionIdentity
import com.nmtuong.telegramdrive.domain.PreviewTarget
import com.nmtuong.telegramdrive.domain.VideoPlaybackRequest
import com.nmtuong.telegramdrive.feature.auth.AuthorizationScreen
import com.nmtuong.telegramdrive.feature.auth.AuthorizationViewModel
import com.nmtuong.telegramdrive.feature.library.LibraryScreen
import com.nmtuong.telegramdrive.feature.library.LibraryViewModel
import com.nmtuong.telegramdrive.feature.gallery.GalleryScreen
import com.nmtuong.telegramdrive.feature.gallery.GalleryViewModel
import com.nmtuong.telegramdrive.feature.preview.ImagePreviewScreen
import com.nmtuong.telegramdrive.feature.preview.VideoPreviewScreen
import com.nmtuong.telegramdrive.feature.preview.AnimationPreviewScreen
import com.nmtuong.telegramdrive.feature.preview.AudioPreviewScreen
import com.nmtuong.telegramdrive.feature.preview.PdfPreviewScreen
import com.nmtuong.telegramdrive.feature.preview.TextPreviewScreen
import com.nmtuong.telegramdrive.feature.preview.ExternalPreviewScreen

@Composable
fun AppNavigation(container: AppContainer) {
  val authorizationViewModel: AuthorizationViewModel = viewModel {
    AuthorizationViewModel(container.telegramRepository)
  }
  val libraryViewModel: LibraryViewModel = viewModel {
    LibraryViewModel(container.telegramRepository, container.identityProvider)
  }
  val galleryViewModel: GalleryViewModel = viewModel {
    GalleryViewModel(container.savedMediaRepository, container.mediaAccessCoordinator)
  }
  val authorization by authorizationViewModel.state.collectAsStateWithLifecycle()
  val currentIdentity by container.identityProvider.currentIdentity.collectAsStateWithLifecycle()
  var preview by remember { mutableStateOf<PreviewTarget?>(null) }
  var videoRequest by rememberSaveable(stateSaver = VideoPlaybackRequestSaver) { mutableStateOf<VideoPlaybackRequest?>(null) }
  var playbackSessionId by rememberSaveable { mutableLongStateOf(0L) }
  var showGallery by rememberSaveable { mutableStateOf(true) }
  val galleryGridState = rememberSaveable(saver = LazyGridState.Saver) { LazyGridState() }

  LaunchedEffect(authorization.state, currentIdentity, videoRequest?.accountIdentity) {
    val requestIdentity = videoRequest?.accountIdentity
    if (shouldDiscardPlaybackRequest(authorization.state, requestIdentity, currentIdentity)) {
      videoRequest = null
      preview = null
      showGallery = true
      container.mediaAccessCoordinator.cancelForAccount()
    }
  }

  if (authorization.state != AuthorizationState.Ready) {
    AuthorizationScreen(authorizationViewModel)
    return
  }

  val activeVideoRequest = videoRequest?.takeIf {
    canRestorePlaybackRequest(authorization.state, it.accountIdentity, currentIdentity)
  }
  when {
    activeVideoRequest != null -> VideoPreviewScreen(
      request = activeVideoRequest,
      mediaAccess = container.mediaAccessCoordinator,
      playbackSessionId = playbackSessionId,
      onBack = { videoRequest = null },
    )
    preview != null -> when (val target = checkNotNull(preview)) {
      is PreviewTarget.Image -> ImagePreviewScreen(target.path) { preview = null }
      is PreviewTarget.Video -> VideoPreviewScreen(path = target.path, onBack = { preview = null })
      is PreviewTarget.Animation -> AnimationPreviewScreen(target.path) { preview = null }
      is PreviewTarget.Audio -> AudioPreviewScreen(target.path) { preview = null }
      is PreviewTarget.Pdf -> PdfPreviewScreen(target.path) { preview = null }
      is PreviewTarget.Text -> TextPreviewScreen(target.path) { preview = null }
      is PreviewTarget.External -> ExternalPreviewScreen(target.path, target.mimeType) { preview = null }
    }
    else -> if (showGallery) {
      GalleryScreen(
        viewModel = galleryViewModel,
        gridState = galleryGridState,
        onOpenSourceBrowser = { showGallery = false },
        onOpenMedia = { entity, path, thumbnailPath ->
          if (entity.mediaType == "VIDEO") {
            playbackSessionId += 1L
            videoRequest = VideoPlaybackRequest(
              accountIdentity = AccountSessionIdentity(entity.accountId, entity.databaseGeneration),
              stableFileIdentity = entity.originalStableFileIdentity,
              telegramFileId = entity.telegramFileId,
              chatId = entity.chatId,
              messageId = entity.messageId,
              displayName = entity.stableDisplayName,
              durationSeconds = entity.durationSeconds.toLong(),
              mimeType = entity.mimeType,
              expectedSizeBytes = entity.expectedSizeBytes.takeIf { it > 0L },
              localPath = path.takeIf { it.isNotBlank() },
              thumbnailPath = thumbnailPath,
              minithumbnailData = entity.minithumbnailData,
            )
          } else {
            preview = PreviewTarget.Image(entity.messageId, path)
          }
        },
      )
    } else {
      LibraryScreen(
        libraryViewModel,
        { preview = it },
        onOpenGallery = { showGallery = true },
      )
    }
  }
}

internal fun shouldDiscardPlaybackRequest(
  authorizationState: AuthorizationState,
  requestIdentity: AccountSessionIdentity?,
  currentIdentity: AccountSessionIdentity?,
): Boolean =
  authorizationState != AuthorizationState.Ready ||
    (requestIdentity != null && currentIdentity != null && currentIdentity != requestIdentity)

internal fun canRestorePlaybackRequest(
  authorizationState: AuthorizationState,
  requestIdentity: AccountSessionIdentity,
  currentIdentity: AccountSessionIdentity?,
): Boolean =
  authorizationState == AuthorizationState.Ready && currentIdentity == requestIdentity

private val VideoPlaybackRequestSaver: Saver<VideoPlaybackRequest?, Any> = listSaver(
  save = { request -> request?.let {
    listOf(
        it.accountIdentity.accountId,
        it.accountIdentity.databaseGeneration,
        it.stableFileIdentity,
        it.telegramFileId,
        it.chatId,
        it.messageId,
        it.displayName,
        it.durationSeconds,
        it.mimeType,
        it.expectedSizeBytes,
        it.localPath,
        it.thumbnailPath,
        it.minithumbnailData,
    )
  } ?: emptyList() },
  restore = { values ->
    if (values.isEmpty()) {
      null
    } else {
      VideoPlaybackRequest(
        accountIdentity = AccountSessionIdentity(values[0] as Long, values[1] as Long),
        stableFileIdentity = values[2] as String,
        telegramFileId = values[3] as Int,
        chatId = values[4] as Long,
        messageId = values[5] as Long,
        displayName = values[6] as String,
        durationSeconds = values[7] as Long,
        mimeType = values[8] as String?,
        expectedSizeBytes = values[9] as Long?,
        localPath = values[10] as String?,
        thumbnailPath = values[11] as String?,
        minithumbnailData = values[12] as String?,
      )
    }
  },
)
