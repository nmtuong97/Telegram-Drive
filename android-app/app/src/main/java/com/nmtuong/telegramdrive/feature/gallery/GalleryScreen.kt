package com.nmtuong.telegramdrive.feature.gallery

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import com.nmtuong.telegramdrive.data.GalleryMediaFilter
import com.nmtuong.telegramdrive.data.local.MediaSyncPhase
import com.nmtuong.telegramdrive.data.local.SavedMediaEntity
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun GalleryScreen(
  viewModel: GalleryViewModel,
  onOpenSourceBrowser: () -> Unit,
  onOpenMedia: (SavedMediaEntity, String) -> Unit,
) {
  val items = viewModel.pagingData.collectAsLazyPagingItems()
  val query by viewModel.query.collectAsStateWithLifecycle()
  val syncState by viewModel.syncState.collectAsStateWithLifecycle()
  val thumbnailPaths by viewModel.thumbnailPaths.collectAsStateWithLifecycle()
  val openState by viewModel.openState.collectAsStateWithLifecycle()
  var searchText by remember { mutableStateOf(query.search) }

  LaunchedEffect(openState) {
    when (val state = openState) {
      is GalleryOpenState.Opened -> {
        onOpenMedia(state.entity, state.path)
        viewModel.consumeOpenState()
      }
      else -> Unit
    }
  }

  Column(
    modifier = Modifier.fillMaxSize().padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
      Column(modifier = Modifier.weight(1f)) {
        Text("Saved Media", style = MaterialTheme.typography.headlineSmall)
        Text(
          text = syncState?.phase?.lowercase()?.replace('_', ' ') ?: "waiting for sync",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      TextButton(onClick = onOpenSourceBrowser) { Text("Sources") }
    }

    OutlinedTextField(
      value = searchText,
      onValueChange = { searchText = it; viewModel.setSearch(it) },
      modifier = Modifier.fillMaxWidth(),
      singleLine = true,
      label = { Text("Search filename or caption") },
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      FilterChip(query.mediaFilter == GalleryMediaFilter.ALL, { viewModel.setMediaFilter(GalleryMediaFilter.ALL) }, label = { Text("All") })
      FilterChip(query.mediaFilter == GalleryMediaFilter.IMAGE, { viewModel.setMediaFilter(GalleryMediaFilter.IMAGE) }, label = { Text("Images") })
      FilterChip(query.mediaFilter == GalleryMediaFilter.VIDEO, { viewModel.setMediaFilter(GalleryMediaFilter.VIDEO) }, label = { Text("Videos") })
      FilterChip(query.localOnly, { viewModel.setLocalOnly(!query.localOnly) }, label = { Text("Local") })
    }
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
      TextButton(onClick = viewModel::toggleSort) { Text(if (query.newestFirst) "Newest first" else "Oldest first") }
      TextButton(onClick = viewModel::refreshSync) { Text("Sync") }
    }
    if (openState is GalleryOpenState.Loading) {
      Text("Preparing media…", style = MaterialTheme.typography.bodySmall)
    }
    if (openState is GalleryOpenState.Failed) {
      Text((openState as GalleryOpenState.Failed).message, color = MaterialTheme.colorScheme.error)
    }

    when (val refresh = items.loadState.refresh) {
      is LoadState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
      is LoadState.Error -> ErrorPanel(refresh.error.message ?: "Gallery load failed", items::retry)
      is LoadState.NotLoading -> if (items.itemCount == 0) {
        EmptyPanel(syncState, viewModel::refreshSync)
      } else {
        LazyVerticalGrid(
          columns = GridCells.Adaptive(150.dp),
          modifier = Modifier.fillMaxSize(),
          contentPadding = PaddingValues(bottom = 24.dp),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          items(
            count = items.itemCount,
            key = { index -> items.peek(index)?.let { "${it.chatId}:${it.messageId}" } ?: "placeholder:$index" },
          ) { index ->
            items[index]?.let { entity ->
              LaunchedEffect(entity.thumbnailStableFileIdentity) { viewModel.loadThumbnail(entity) }
              Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (index == 0 || monthKey(entity) != monthKey(items.peek(index - 1))) {
                  Text(monthKey(entity), style = MaterialTheme.typography.labelLarge)
                }
                GalleryTile(
                  entity,
                  thumbnailPath = entity.thumbnailStableFileIdentity?.let { thumbnailPaths[it] },
                  onClick = { viewModel.openMedia(entity) },
                )
              }
            }
          }
          if (items.loadState.append is LoadState.Loading) item(span = { GridItemSpan(maxLineSpan) }) {
            Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp)) }
          }
        }
      }
    }
  }
}

@Composable
private fun GalleryTile(entity: SavedMediaEntity, thumbnailPath: String?, onClick: () -> Unit) {
  val local = entity.localFilePath?.let(::File)?.takeIf { it.isFile }
  Button(onClick = onClick, contentPadding = PaddingValues(6.dp), modifier = Modifier.fillMaxWidth()) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
      MiniThumbnail(entity, local, thumbnailPath)
      Text(entity.stableDisplayName, maxLines = 2, style = MaterialTheme.typography.labelMedium)
      if (entity.mediaType == "VIDEO") Text("${entity.durationSeconds}s", style = MaterialTheme.typography.labelSmall)
    }
  }
}

@Composable
private fun MiniThumbnail(entity: SavedMediaEntity, local: File?, thumbnailPath: String?) {
  val bitmap = remember(entity.minithumbnailData, thumbnailPath) {
    thumbnailPath?.let { BitmapFactory.decodeFile(it) } ?:
    entity.minithumbnailData?.let { encoded ->
      runCatching { BitmapFactory.decodeByteArray(Base64.decode(encoded, Base64.DEFAULT), 0, Base64.decode(encoded, Base64.DEFAULT).size) }.getOrNull()
    }
  }
  Box(
    modifier = Modifier.size(120.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
    contentAlignment = Alignment.Center,
  ) {
    when {
      bitmap != null -> Image(bitmap.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
      local != null -> Text("Local", style = MaterialTheme.typography.labelSmall)
      entity.mediaType == "VIDEO" -> Text("▶", style = MaterialTheme.typography.headlineMedium)
      else -> Text("Image", style = MaterialTheme.typography.labelSmall)
    }
  }
}

@Composable
private fun EmptyPanel(syncState: com.nmtuong.telegramdrive.data.local.SyncStateEntity?, onRetry: () -> Unit) {
  Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
    Text(if (syncState?.phase == MediaSyncPhase.ERROR.name) syncState.lastError ?: "Sync failed" else "No indexed media")
    Spacer(Modifier.height(8.dp))
    Button(onClick = onRetry) { Text("Retry sync") }
  }
}

@Composable
private fun ErrorPanel(message: String, onRetry: () -> Unit) {
  Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
    Text(message, color = MaterialTheme.colorScheme.error)
    Button(onClick = onRetry) { Text("Retry") }
  }
}

private val monthFormatter = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault())

private fun monthKey(entity: SavedMediaEntity?): String = entity?.let {
  if (it.messageDateEpochSeconds <= 0L) "Unknown date" else Instant.ofEpochSecond(it.messageDateEpochSeconds).atZone(ZoneId.systemDefault()).format(monthFormatter)
} ?: "Unknown date"
