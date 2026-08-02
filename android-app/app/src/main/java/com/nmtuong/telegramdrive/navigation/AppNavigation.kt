package com.nmtuong.telegramdrive.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nmtuong.telegramdrive.bootstrap.AppContainer
import com.nmtuong.telegramdrive.domain.AuthorizationState
import com.nmtuong.telegramdrive.domain.PreviewTarget
import com.nmtuong.telegramdrive.data.local.SavedMediaEntity
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
  var preview by remember { mutableStateOf<PreviewTarget?>(null) }
  var galleryVideoEntity by remember { mutableStateOf<SavedMediaEntity?>(null) }
  var showGallery by remember { mutableStateOf(true) }

  when (val target = preview) {
    is PreviewTarget.Image -> ImagePreviewScreen(target.path) { preview = null; galleryVideoEntity = null }
    is PreviewTarget.Video -> VideoPreviewScreen(
      path = target.path,
      onBack = { preview = null; galleryVideoEntity = null },
      dataSourceFactory = galleryVideoEntity?.let(container.mediaAccessCoordinator::videoDataSourceFactory),
    )
    is PreviewTarget.Animation -> AnimationPreviewScreen(target.path) { preview = null }
    is PreviewTarget.Audio -> AudioPreviewScreen(target.path) { preview = null }
    is PreviewTarget.Pdf -> PdfPreviewScreen(target.path) { preview = null }
    is PreviewTarget.Text -> TextPreviewScreen(target.path) { preview = null }
    is PreviewTarget.External -> ExternalPreviewScreen(target.path, target.mimeType) { preview = null }
    null -> if (authorization.state == AuthorizationState.Ready) {
      if (showGallery) {
        GalleryScreen(
          viewModel = galleryViewModel,
          onOpenSourceBrowser = { showGallery = false },
          onOpenMedia = { entity, path ->
            galleryVideoEntity = entity.takeIf { it.mediaType == "VIDEO" }
            preview = if (entity.mediaType == "IMAGE") PreviewTarget.Image(entity.messageId, path)
            else PreviewTarget.Video(entity.messageId, path)
          },
        )
      } else {
        LibraryScreen(
          libraryViewModel,
          { galleryVideoEntity = null; preview = it },
          onOpenGallery = { showGallery = true },
        )
      }
    } else {
      AuthorizationScreen(authorizationViewModel)
    }
  }
}
