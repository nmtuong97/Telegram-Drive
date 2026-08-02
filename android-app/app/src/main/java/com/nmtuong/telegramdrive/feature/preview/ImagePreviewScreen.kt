package com.nmtuong.telegramdrive.feature.preview

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nmtuong.telegramdrive.R
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun ImagePreviewScreen(path: String, onBack: () -> Unit) {
  BackHandler(onBack = onBack)
  var loadFinished by remember(path) { mutableStateOf(false) }
  val bitmap by produceState<android.graphics.Bitmap?>(null, path) {
    value = withContext(Dispatchers.IO) {
      runCatching { decodeSampledBitmap(path, 2048) }.getOrNull()
    }
    loadFinished = true
  }
  Column(Modifier.fillMaxSize().safeDrawingPadding()) {
    TextButton(onClick = onBack, modifier = Modifier.padding(8.dp)) { Text(stringResource(R.string.back)) }
    when {
      !File(path).isFile || loadFinished && bitmap == null ->
        Text(stringResource(R.string.media_error), modifier = Modifier.padding(16.dp))
      !loadFinished -> CircularProgressIndicator(modifier = Modifier.padding(16.dp))
      else -> {
        var scale by remember(path) { mutableFloatStateOf(1f) }
        var offset by remember(path) { mutableStateOf(Offset.Zero) }
        val transformState = rememberTransformableState { zoomChange, panChange, _ ->
          scale = (scale * zoomChange).coerceIn(1f, 8f)
          offset += panChange
        }
        Image(
          bitmap!!.asImageBitmap(),
          contentDescription = stringResource(R.string.image_preview),
          contentScale = ContentScale.Fit,
          modifier = Modifier
            .fillMaxSize()
            .clip(MaterialTheme.shapes.medium)
            .graphicsLayer {
              scaleX = scale
              scaleY = scale
              translationX = offset.x
              translationY = offset.y
            }
            .pointerInput(path) {
              detectTapGestures(onDoubleTap = {
                scale = 1f
                offset = Offset.Zero
              })
            }
            .transformable(transformState),
        )
      }
    }
  }
}

internal fun decodeSampledBitmap(path: String, maximumDimension: Int): android.graphics.Bitmap? {
  val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
  android.graphics.BitmapFactory.decodeFile(path, bounds)
  var sample = 1
  while (bounds.outWidth / sample > maximumDimension || bounds.outHeight / sample > maximumDimension) sample *= 2
  return android.graphics.BitmapFactory.decodeFile(
    path,
    android.graphics.BitmapFactory.Options().apply { inSampleSize = sample },
  )
}
