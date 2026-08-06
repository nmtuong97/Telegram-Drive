package com.nmtuong.telegramdrive.feature.gallery

import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import com.nmtuong.telegramdrive.data.GalleryMediaFilter
import com.nmtuong.telegramdrive.data.GalleryQuery
import com.nmtuong.telegramdrive.data.SavedMediaSyncResult
import com.nmtuong.telegramdrive.data.local.MediaSyncPhase
import com.nmtuong.telegramdrive.data.local.SavedMediaEntity
import com.nmtuong.telegramdrive.data.local.SyncStateEntity
import com.nmtuong.telegramdrive.ui.theme.TelegramDriveTheme
import java.io.File
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(
  viewModel: GalleryViewModel,
  gridState: LazyGridState,
  restoreAnchorIndex: Int,
  restoreAnchorOffset: Int,
  shouldRestoreAnchor: Boolean,
  onAnchorRestored: () -> Unit,
  onOpenSourceBrowser: () -> Unit,
  onOpenMedia: (SavedMediaEntity, String, String?) -> Unit,
) {
  val items = viewModel.pagingData.collectAsLazyPagingItems()
  val query by viewModel.query.collectAsStateWithLifecycle()
  val syncState by viewModel.syncState.collectAsStateWithLifecycle()
  val syncResult by viewModel.syncResult.collectAsStateWithLifecycle(initialValue = null)
  val thumbnailPaths by viewModel.thumbnailPaths.collectAsStateWithLifecycle()
  val openState by viewModel.openState.collectAsStateWithLifecycle()
  var searchText by remember { mutableStateOf(query.search) }
  val syncPresentation = syncPresentation(syncState, syncResult)
  val hasActiveQuery = query.search.isNotBlank() || query.mediaFilter != GalleryMediaFilter.ALL || query.localOnly

  LaunchedEffect(query.search) {
    searchText = query.search
  }

  LaunchedEffect(openState) {
    when (val state = openState) {
      is GalleryOpenState.Opened -> {
        onOpenMedia(
          state.entity,
          state.path,
          state.entity.thumbnailStableFileIdentity?.let(thumbnailPaths::get),
        )
        viewModel.consumeOpenState()
      }
      else -> Unit
    }
  }

  LaunchedEffect(shouldRestoreAnchor, restoreAnchorIndex, restoreAnchorOffset, items.itemCount) {
    if (shouldRestoreAnchor && items.itemCount > restoreAnchorIndex) {
      gridState.scrollToItem(restoreAnchorIndex, restoreAnchorOffset)
      onAnchorRestored()
    }
  }

  Scaffold(
    modifier = Modifier.fillMaxSize(),
    containerColor = MaterialTheme.colorScheme.background,
    topBar = { GalleryTopBar(onOpenSourceBrowser) },
  ) { innerPadding ->
    Column(
      modifier = Modifier
        .fillMaxSize()
        .padding(innerPadding)
        .consumeWindowInsets(innerPadding)
        .imePadding()
        .padding(horizontal = 16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      GallerySearchField(
        searchText = searchText,
        onSearchTextChanged = { searchText = it; viewModel.setSearch(it) },
      )
      GalleryFilters(
        query = query,
        onMediaFilterChanged = viewModel::setMediaFilter,
        onLocalOnlyChanged = viewModel::setLocalOnly,
      )
      GalleryControls(
        query = query,
        syncPresentation = syncPresentation,
        onToggleSort = viewModel::toggleSort,
        onSync = viewModel::refreshSync,
      )
      SyncStatusBanner(
        presentation = syncPresentation,
        onRetry = viewModel::refreshSync,
      )
      OpenStatus(openState, onRetry = viewModel::retryOpen)

      Box(Modifier.fillMaxWidth().weight(1f)) {
        val refreshState = items.loadState.refresh
        when {
          refreshState is LoadState.Loading && items.itemCount == 0 -> {
            InitialLoadingPanel()
          }
          refreshState is LoadState.Error && items.itemCount == 0 -> {
            GalleryErrorPanel(onRetry = items::retry)
          }
          items.itemCount == 0 -> {
            EmptyPanel(
              syncState = syncState,
              canRetrySync = syncPresentation.showRetry,
              hasActiveQuery = hasActiveQuery,
              onRetry = viewModel::refreshSync,
              onClearQuery = {
                searchText = ""
                viewModel.setSearch("")
                viewModel.setMediaFilter(GalleryMediaFilter.ALL)
                viewModel.setLocalOnly(false)
              },
            )
          }
          else -> {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
              if (refreshState is LoadState.Error) {
                InlineError(message = "Saved media could not be refreshed", onRetry = items::retry)
              }
              GalleryGrid(
                items = items,
                thumbnailPaths = thumbnailPaths,
                state = gridState,
                onLoadThumbnail = viewModel::loadThumbnail,
                onOpenMedia = viewModel::openMedia,
                modifier = Modifier.weight(1f),
              )
            }
          }
        }
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GalleryTopBar(onOpenSourceBrowser: () -> Unit) {
  TopAppBar(
    title = { Text("Saved Media", style = MaterialTheme.typography.titleLarge) },
    actions = {
      TextButton(
        onClick = onOpenSourceBrowser,
        modifier = Modifier.semantics { contentDescription = "Open sources" },
      ) {
        Text("↗", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.width(6.dp))
        Text("Sources")
      }
    },
    colors = TopAppBarDefaults.topAppBarColors(
      containerColor = MaterialTheme.colorScheme.background,
    ),
  )
}

@Composable
private fun GallerySearchField(
  searchText: String,
  onSearchTextChanged: (String) -> Unit,
) {
  OutlinedTextField(
    value = searchText,
    onValueChange = onSearchTextChanged,
    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
    singleLine = true,
    placeholder = { Text("Search filename or caption") },
    leadingIcon = {
      Text(
        text = "⌕",
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.semantics { contentDescription = "Search" },
      )
    },
    trailingIcon = if (searchText.isNotEmpty()) {
      {
        IconButton(
          onClick = { onSearchTextChanged("") },
          modifier = Modifier
            .size(48.dp)
            .semantics { contentDescription = "Clear search" },
        ) {
          Text("×", style = MaterialTheme.typography.titleLarge)
        }
      }
    } else {
      null
    },
    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
  )
}

@Composable
private fun GalleryFilters(
  query: GalleryQuery,
  onMediaFilterChanged: (GalleryMediaFilter) -> Unit,
  onLocalOnlyChanged: (Boolean) -> Unit,
) {
  LazyRow(
    modifier = Modifier.fillMaxWidth(),
    contentPadding = PaddingValues(horizontal = 2.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    item {
      GalleryFilterChip(
        label = "All",
        selected = query.mediaFilter == GalleryMediaFilter.ALL,
        onClick = { onMediaFilterChanged(GalleryMediaFilter.ALL) },
      )
    }
    item {
      GalleryFilterChip(
        label = "Images",
        selected = query.mediaFilter == GalleryMediaFilter.IMAGE,
        onClick = { onMediaFilterChanged(GalleryMediaFilter.IMAGE) },
      )
    }
    item {
      GalleryFilterChip(
        label = "Videos",
        selected = query.mediaFilter == GalleryMediaFilter.VIDEO,
        onClick = { onMediaFilterChanged(GalleryMediaFilter.VIDEO) },
      )
    }
    item {
      GalleryFilterChip(
        label = "Local",
        selected = query.localOnly,
        onClick = { onLocalOnlyChanged(!query.localOnly) },
      )
    }
  }
}

@Composable
private fun GalleryFilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
  FilterChip(
    selected = selected,
    onClick = onClick,
    label = { Text(label, maxLines = 1) },
  )
}

@Composable
private fun GalleryControls(
  query: GalleryQuery,
  syncPresentation: SyncPresentation,
  onToggleSort: () -> Unit,
  onSync: () -> Unit,
) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    TextButton(onClick = onToggleSort) {
      Text("↕", style = MaterialTheme.typography.titleMedium)
      Spacer(Modifier.width(6.dp))
      Text(if (query.newestFirst) "Newest first" else "Oldest first")
    }
    FilledTonalButton(
      onClick = onSync,
      enabled = syncPresentation.syncEnabled && !syncPresentation.isActive,
    ) {
      Text(if (syncPresentation.isActive) "Syncing…" else "⟳  Sync")
    }
  }
}

internal data class SyncPresentation(
  val label: String,
  val isActive: Boolean,
  val showRetry: Boolean,
  val syncEnabled: Boolean,
)

internal fun syncPresentation(
  state: SyncStateEntity?,
  result: SavedMediaSyncResult?,
): SyncPresentation {
  val active = state?.phase in setOf(
    MediaSyncPhase.DISCOVERING_HEAD.name,
    MediaSyncPhase.BACKFILLING.name,
    MediaSyncPhase.CATCHING_UP.name,
  )
  return when {
    active -> SyncPresentation("Syncing saved media…", isActive = true, showRetry = false, syncEnabled = false)
    result is SavedMediaSyncResult.Failed -> SyncPresentation(
      label = if (result.retryable) "Sync couldn't complete" else "Sync unavailable",
      isActive = false,
      showRetry = result.retryable,
      syncEnabled = result.retryable,
    )
    state?.phase == MediaSyncPhase.ERROR.name ->
      SyncPresentation("Sync couldn't complete", isActive = false, showRetry = true, syncEnabled = true)
    state?.phase == MediaSyncPhase.COMPLETED.name || result is SavedMediaSyncResult.Completed ->
      SyncPresentation("Up to date", isActive = false, showRetry = false, syncEnabled = true)
    state?.phase == MediaSyncPhase.IDLE.name ->
      SyncPresentation("Ready to sync", isActive = false, showRetry = false, syncEnabled = true)
    else -> SyncPresentation("Preparing your gallery…", isActive = false, showRetry = false, syncEnabled = true)
  }
}

@Composable
private fun SyncStatusBanner(presentation: SyncPresentation, onRetry: () -> Unit) {
  Surface(
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(12.dp),
    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
  ) {
    Column {
      Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        if (presentation.isActive) {
          CircularProgressIndicator(Modifier.size(18.dp))
        }
        Text(
          text = presentation.label,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.weight(1f),
        )
        if (presentation.showRetry) {
          TextButton(onClick = onRetry) { Text("Try again") }
        }
      }
      if (presentation.isActive) {
        LinearProgressIndicator(
          modifier = Modifier.fillMaxWidth().height(2.dp),
          color = MaterialTheme.colorScheme.primary,
          trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )
      }
    }
  }
}

@Composable
private fun OpenStatus(openState: GalleryOpenState, onRetry: () -> Unit) {
  when (openState) {
    GalleryOpenState.Idle,
    is GalleryOpenState.Opened,
    -> Unit
    GalleryOpenState.Loading -> InlineLoading(message = "Preparing media…")
    is GalleryOpenState.Failed -> InlineError(message = "Couldn't open this media", onRetry = onRetry)
  }
}

@Composable
private fun GalleryGrid(
  items: androidx.paging.compose.LazyPagingItems<GalleryGridItem>,
  thumbnailPaths: Map<String, String>,
  state: LazyGridState,
  onLoadThumbnail: (SavedMediaEntity) -> Unit,
  onOpenMedia: (SavedMediaEntity) -> Unit,
  modifier: Modifier = Modifier,
) {
  LazyVerticalGrid(
    columns = GridCells.Adaptive(minSize = 156.dp),
    state = state,
    modifier = modifier
      .fillMaxWidth()
      .semantics { contentDescription = "Saved media grid" },
    contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp),
    horizontalArrangement = Arrangement.spacedBy(10.dp),
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    items(
      count = items.itemCount,
      key = { index ->
        items.peek(index)?.stableKey ?: "placeholder:$index"
      },
      span = { index ->
        if (items.peek(index) is GalleryGridItem.MonthHeader) GridItemSpan(maxLineSpan)
        else GridItemSpan(1)
      },
    ) { index ->
      when (val item = items[index]) {
        is GalleryGridItem.MonthHeader -> MonthHeader(item.month)
        is GalleryGridItem.Media -> {
          val entity = item.entity
          LaunchedEffect(entity.thumbnailStableFileIdentity) { onLoadThumbnail(entity) }
          GalleryTile(
            entity = entity,
            thumbnailPath = entity.thumbnailStableFileIdentity?.let(thumbnailPaths::get),
            onClick = { onOpenMedia(entity) },
          )
        }
        null -> Unit
      }
    }
    if (items.loadState.append is LoadState.Loading) {
      item(span = { GridItemSpan(maxLineSpan) }) {
        Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
          CircularProgressIndicator(Modifier.size(24.dp))
        }
      }
    }
    if (items.loadState.append is LoadState.Error) {
      item(span = { GridItemSpan(maxLineSpan) }) {
        InlineError(message = "More media couldn't be loaded", onRetry = items::retry)
      }
    }
  }
}

@Composable
private fun MonthHeader(month: String) {
  Text(
    text = month,
    style = MaterialTheme.typography.titleSmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
  )
}

@Composable
private fun GalleryTile(entity: SavedMediaEntity, thumbnailPath: String?, onClick: () -> Unit) {
  val local = entity.localFilePath?.let(::File)?.takeIf { it.isFile }
  val isVideo = entity.mediaType == "VIDEO"
  val duration = entity.durationSeconds.takeIf { isVideo && it > 0 }?.let(::formatDuration)
  val displayName = entity.stableDisplayName.ifBlank { "Untitled media" }
  val accessibilityDescription = buildString {
    append(if (isVideo) "Video" else "Image")
    append(": ")
    append(displayName)
    duration?.let { append(", $it") }
    if (local != null) append(", Local copy")
  }

  Card(
    onClick = onClick,
    modifier = Modifier
      .fillMaxWidth()
      .semantics(mergeDescendants = true) { contentDescription = accessibilityDescription },
    shape = RoundedCornerShape(12.dp),
    colors = CardDefaults.cardColors(
      containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.48f),
    ),
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(8.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      MediaThumbnail(entity, thumbnailPath, duration)
      Text(
        text = displayName,
        style = MaterialTheme.typography.bodyMedium,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        color = MaterialTheme.colorScheme.onSurface,
      )
      if (local != null) {
        Surface(
          shape = RoundedCornerShape(6.dp),
          color = MaterialTheme.colorScheme.primaryContainer,
          contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ) {
          Text(
            text = "Local",
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
          )
        }
      }
    }
  }
}

@Composable
private fun MediaThumbnail(entity: SavedMediaEntity, thumbnailPath: String?, duration: String?) {
  val bitmap = remember(entity.minithumbnailData, thumbnailPath) {
    thumbnailPath?.let { BitmapFactory.decodeFile(it) } ?: entity.minithumbnailData?.let { encoded ->
      runCatching {
        val bytes = Base64.decode(encoded, Base64.DEFAULT)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
      }.getOrNull()
    }
  }
  val isVideo = entity.mediaType == "VIDEO"
  val shape = RoundedCornerShape(10.dp)

  Box(
    modifier = Modifier
      .fillMaxWidth()
      .aspectRatio(1f)
      .clip(shape)
      .background(MaterialTheme.colorScheme.surface),
    contentAlignment = Alignment.Center,
  ) {
    when {
      bitmap != null -> Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = null,
        modifier = Modifier.fillMaxSize(),
        contentScale = ContentScale.Crop,
      )
      isVideo -> Text("Video", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
      else -> Text("Image", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    if (isVideo) {
      Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f)))
      Surface(
        modifier = Modifier.size(44.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
        contentColor = MaterialTheme.colorScheme.onSurface,
      ) {
        Box(contentAlignment = Alignment.Center) {
          Text("▶", style = MaterialTheme.typography.titleMedium)
        }
      }
      duration?.let {
        Surface(
          modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
          shape = RoundedCornerShape(6.dp),
          color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.78f),
          contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
          Text(
            text = it,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
          )
        }
      }
    }
  }
}

@Composable
private fun InitialLoadingPanel() {
  Column(
    modifier = Modifier.fillMaxSize(),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
  ) {
    CircularProgressIndicator()
    Spacer(Modifier.height(12.dp))
    Text("Loading saved media…", style = MaterialTheme.typography.bodyMedium)
  }
}

@Composable
private fun EmptyPanel(
  syncState: SyncStateEntity?,
  canRetrySync: Boolean,
  hasActiveQuery: Boolean,
  onRetry: () -> Unit,
  onClearQuery: () -> Unit,
) {
  val syncError = syncState?.phase == MediaSyncPhase.ERROR.name
  val isSyncing = syncState?.phase in setOf(
    MediaSyncPhase.DISCOVERING_HEAD.name,
    MediaSyncPhase.BACKFILLING.name,
    MediaSyncPhase.CATCHING_UP.name,
  )
  val title = when {
    isSyncing -> "Syncing saved media…"
    hasActiveQuery -> "No matching media"
    syncError -> "Sync couldn't complete"
    else -> "No saved media yet"
  }
  val message = when {
    isSyncing -> "Your media will appear here as it is indexed."
    hasActiveQuery -> "Try another search or filter."
    syncError -> "Try again to refresh your saved media."
    else -> "Media saved in Telegram will appear here."
  }

  Column(
    modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
  ) {
    Text(title, style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))
    Text(
      text = message,
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(16.dp))
    when {
      hasActiveQuery -> TextButton(onClick = onClearQuery) { Text("Clear search & filters") }
      canRetrySync -> Button(onClick = onRetry) { Text("Try again") }
      else -> Unit
    }
  }
}

@Composable
private fun GalleryErrorPanel(onRetry: () -> Unit) {
  Column(
    modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
  ) {
    Text("Couldn't load saved media", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))
    Text(
      "Check your connection and try again.",
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(16.dp))
    Button(onClick = onRetry) { Text("Try again") }
  }
}

@Composable
private fun InlineLoading(message: String) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    CircularProgressIndicator(Modifier.size(18.dp))
    Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
  }
}

@Composable
private fun InlineError(message: String, onRetry: () -> Unit) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Text(
      text = message,
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.error,
      modifier = Modifier.weight(1f),
    )
    TextButton(onClick = onRetry) { Text("Retry") }
  }
}

private fun formatDuration(seconds: Int): String {
  val minutes = seconds / 60
  val remainingSeconds = seconds % 60
  return when {
    minutes >= 60 -> "${minutes / 60}h ${minutes % 60}m"
    minutes > 0 -> "%d:%02d".format(Locale.US, minutes, remainingSeconds)
    else -> "${remainingSeconds}s"
  }
}

private enum class GalleryPreviewState { CONTENT, SYNCING, LOADING, EMPTY, NO_RESULTS, ERROR }

@Composable
private fun GalleryPreviewFrame(
  state: GalleryPreviewState,
  query: GalleryQuery = GalleryQuery(),
  searchText: String = query.search,
  syncState: SyncStateEntity? = null,
  syncResult: SavedMediaSyncResult? = null,
  items: List<SavedMediaEntity> = previewItems(),
) {
  val presentation = syncPresentation(syncState, syncResult)
  Scaffold(
    modifier = Modifier.fillMaxSize(),
    containerColor = MaterialTheme.colorScheme.background,
    topBar = { GalleryTopBar(onOpenSourceBrowser = {}) },
  ) { innerPadding ->
    Column(
      modifier = Modifier
        .fillMaxSize()
        .padding(innerPadding)
        .consumeWindowInsets(innerPadding)
        .padding(horizontal = 16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      GallerySearchField(searchText, onSearchTextChanged = {})
      GalleryFilters(query, onMediaFilterChanged = {}, onLocalOnlyChanged = {})
      GalleryControls(query, presentation, onToggleSort = {}, onSync = {})
      SyncStatusBanner(presentation, onRetry = {})
      Box(Modifier.fillMaxWidth().weight(1f)) {
        when (state) {
          GalleryPreviewState.CONTENT,
          GalleryPreviewState.SYNCING,
          -> PreviewGalleryGrid(items)
          GalleryPreviewState.LOADING -> InitialLoadingPanel()
          GalleryPreviewState.EMPTY -> EmptyPanel(null, presentation.showRetry, false, {}, {})
          GalleryPreviewState.NO_RESULTS -> EmptyPanel(null, presentation.showRetry, true, {}, {})
          GalleryPreviewState.ERROR -> GalleryErrorPanel {}
        }
      }
    }
  }
}

@Composable
private fun PreviewGalleryGrid(items: List<SavedMediaEntity>) {
  val previewItems = remember(items) {
    buildList<GalleryGridItem> {
      var previousMonth: String? = null
      items.forEach { entity ->
        val month = galleryMonthKey(entity)
        if (month != previousMonth) add(GalleryGridItem.MonthHeader(month))
        add(GalleryGridItem.Media(entity))
        previousMonth = month
      }
    }
  }
  LazyVerticalGrid(
    columns = GridCells.Adaptive(minSize = 156.dp),
    modifier = Modifier.fillMaxSize(),
    contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp),
    horizontalArrangement = Arrangement.spacedBy(10.dp),
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    items(
      previewItems,
      key = { it.stableKey },
      span = { item ->
        if (item is GalleryGridItem.MonthHeader) GridItemSpan(maxLineSpan) else GridItemSpan(1)
      },
    ) { item ->
      when (item) {
        is GalleryGridItem.MonthHeader -> MonthHeader(item.month)
        is GalleryGridItem.Media -> {
          Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            GalleryTile(item.entity, thumbnailPath = null, onClick = {})
          }
        }
      }
    }
  }
}

private fun previewItems(): List<SavedMediaEntity> = listOf(
  previewEntity(1, "holiday-photo.jpg", "IMAGE", 0, 1_725_000_000L),
  previewEntity(2, "birthday-video.mp4", "VIDEO", 92, 1_725_000_000L),
  previewEntity(3, "landscape.png", "IMAGE", 0, 1_722_000_000L),
  previewEntity(4, "team-update.mp4", "VIDEO", 0, 1_722_000_000L),
)

private fun previewEntity(
  messageId: Long,
  displayName: String,
  mediaType: String,
  durationSeconds: Int,
  dateEpochSeconds: Long,
) = SavedMediaEntity(
  accountId = 1L,
  databaseGeneration = 1L,
  chatId = 1L,
  messageId = messageId,
  mediaType = mediaType,
  messageDateEpochSeconds = dateEpochSeconds,
  caption = "",
  stableDisplayName = displayName,
  mimeType = null,
  width = 1_080,
  height = 1_080,
  durationSeconds = durationSeconds,
  telegramFileId = messageId.toInt(),
  originalStableFileIdentity = "preview:$messageId",
  thumbnailFileId = null,
  thumbnailStableFileIdentity = null,
  minithumbnailData = null,
  minithumbnailWidth = 0,
  minithumbnailHeight = 0,
  localFilePath = null,
)

@Preview(name = "Saved media gallery", showBackground = true, widthDp = 390, heightDp = 844, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun GalleryContentPreview() {
  TelegramDriveTheme { GalleryPreviewFrame(GalleryPreviewState.CONTENT, syncState = previewCompletedSyncState()) }
}

@Preview(name = "Partial sync", showBackground = true, widthDp = 390, heightDp = 844, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun GallerySyncingPreview() {
  TelegramDriveTheme { GalleryPreviewFrame(GalleryPreviewState.SYNCING, syncState = previewSyncingState()) }
}

@Preview(name = "Initial loading", showBackground = true, widthDp = 390, heightDp = 844, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun GalleryLoadingPreview() {
  TelegramDriveTheme { GalleryPreviewFrame(GalleryPreviewState.LOADING) }
}

@Preview(name = "Empty gallery", showBackground = true, widthDp = 390, heightDp = 844, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun GalleryEmptyPreview() {
  TelegramDriveTheme { GalleryPreviewFrame(GalleryPreviewState.EMPTY, syncState = previewCompletedSyncState()) }
}

@Preview(name = "No matching media", showBackground = true, widthDp = 390, heightDp = 844, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun GalleryNoResultsPreview() {
  TelegramDriveTheme {
    GalleryPreviewFrame(
      state = GalleryPreviewState.NO_RESULTS,
      query = GalleryQuery(search = "receipt", mediaFilter = GalleryMediaFilter.VIDEO),
    )
  }
}

@Preview(name = "Gallery error", showBackground = true, widthDp = 390, heightDp = 844, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun GalleryErrorPreview() {
  TelegramDriveTheme { GalleryPreviewFrame(GalleryPreviewState.ERROR) }
}

@Preview(name = "Non-retryable error", showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun GalleryNonRetryableErrorPreview() {
  TelegramDriveTheme {
    GalleryPreviewFrame(
      state = GalleryPreviewState.EMPTY,
      syncResult = SavedMediaSyncResult.Failed("Account is not ready", retryable = false),
    )
  }
}

@Preview(name = "Unicode filename", showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun GalleryUnicodeFilenamePreview() {
  TelegramDriveTheme {
    GalleryPreviewFrame(
      state = GalleryPreviewState.CONTENT,
      syncState = previewCompletedSyncState(),
      items = listOf(previewEntity(20, "旅行写真_مرحبا_очень-длинное-название.jpg", "IMAGE", 0, 1_725_000_000L)),
    )
  }
}

@Preview(name = "Large text landscape", showBackground = true, widthDp = 844, heightDp = 390, fontScale = 1.3f)
@Composable
private fun GalleryLargeTextLandscapePreview() {
  TelegramDriveTheme {
    GalleryPreviewFrame(
      state = GalleryPreviewState.CONTENT,
      syncState = previewCompletedSyncState(),
    )
  }
}

@Preview(name = "Long filename", showBackground = true, widthDp = 320, heightDp = 844, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun GalleryLongFilenamePreview() {
  TelegramDriveTheme {
    GalleryPreviewFrame(
      state = GalleryPreviewState.CONTENT,
      syncState = previewCompletedSyncState(),
      items = listOf(
        previewEntity(
          messageId = 10,
          displayName = "2026-07-21_family-trip_camera-export_final-final-2.jpg",
          mediaType = "IMAGE",
          durationSeconds = 0,
          dateEpochSeconds = 1_725_000_000L,
        ),
        previewEntity(11, "unknown-duration-video.mp4", "VIDEO", 0, 1_725_000_000L),
      ),
    )
  }
}

private fun previewSyncingState() = SyncStateEntity(
  accountId = 1L,
  databaseGeneration = 1L,
  chatId = 1L,
  phase = MediaSyncPhase.BACKFILLING.name,
  backfillCursor = 10L,
  headWatermark = 20L,
  lastCheckpointAtEpochMillis = null,
  lastSuccessfulCatchUpHead = null,
  lastError = null,
  retryCount = 0,
  lastAttemptAtEpochMillis = null,
)

private fun previewCompletedSyncState() = previewSyncingState().copy(
  phase = MediaSyncPhase.COMPLETED.name,
  lastSuccessfulCatchUpHead = 20L,
)
