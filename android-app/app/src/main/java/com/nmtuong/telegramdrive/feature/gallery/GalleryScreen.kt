package com.nmtuong.telegramdrive.feature.gallery

import android.content.res.Configuration
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import com.nmtuong.telegramdrive.R
import com.nmtuong.telegramdrive.data.GalleryMediaFilter
import com.nmtuong.telegramdrive.data.GalleryQuery
import com.nmtuong.telegramdrive.data.SavedMediaSyncResult
import com.nmtuong.telegramdrive.data.local.MediaSyncPhase
import com.nmtuong.telegramdrive.data.local.SavedMediaEntity
import com.nmtuong.telegramdrive.data.local.SyncStateEntity
import com.nmtuong.telegramdrive.ui.theme.TelegramDriveTheme
import java.util.Locale
import kotlin.math.roundToInt

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
  val currentIdentity by viewModel.currentIdentity.collectAsStateWithLifecycle()
  val thumbnailLoader = remember { GalleryThumbnailLoader() }
  var searchText by remember { mutableStateOf(query.search) }
  val syncPresentation = syncPresentation(syncState, syncResult)
  val hasActiveQuery = query.search.isNotBlank() || query.mediaFilter != GalleryMediaFilter.ALL || query.localOnly

  LaunchedEffect(thumbnailLoader, currentIdentity) { thumbnailLoader.clear() }
  DisposableEffect(thumbnailLoader) { onDispose { thumbnailLoader.close() } }
  LaunchedEffect(query.search) { searchText = query.search }

  LaunchedEffect(openState) {
    when (val state = openState) {
      is GalleryOpenState.Opened -> {
        val thumbnailKey = state.entity.thumbnailStableFileIdentity
          ?: state.entity.thumbnailFileId?.let { "tdlib-file:$it" }
        onOpenMedia(state.entity, state.path, thumbnailKey?.let(thumbnailPaths::get))
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
      if (syncPresentation.showCompactStatus) {
        SyncStatusCompact(syncPresentation, onRetry = viewModel::refreshSync)
      }
      OpenStatus(openState, onRetry = viewModel::retryOpen)

      Box(Modifier.fillMaxWidth().weight(1f)) {
        val refreshState = items.loadState.refresh
        when {
          refreshState is LoadState.Loading && items.itemCount == 0 -> InitialLoadingPanel()
          refreshState is LoadState.Error && items.itemCount == 0 -> GalleryErrorPanel(onRetry = items::retry)
          items.itemCount == 0 -> EmptyPanel(
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
          else -> GalleryGrid(
            items = items,
            thumbnailPaths = thumbnailPaths,
            state = gridState,
            thumbnailLoader = thumbnailLoader,
            onLoadThumbnail = viewModel::loadThumbnail,
            onOpenMedia = viewModel::openMedia,
            modifier = Modifier.fillMaxSize(),
          )
        }
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GalleryTopBar(onOpenSourceBrowser: () -> Unit) {
  val openSourcesDescription = stringResource(R.string.gallery_open_sources)
  TopAppBar(
    title = { Text(stringResource(R.string.gallery_title), style = MaterialTheme.typography.titleLarge) },
    actions = {
      TextButton(
        onClick = onOpenSourceBrowser,
        modifier = Modifier.semantics { contentDescription = openSourcesDescription },
      ) {
        Icon(Icons.Default.List, contentDescription = null)
        Spacer(Modifier.width(6.dp))
        Text(stringResource(R.string.gallery_sources))
      }
    },
    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
  )
}

@Composable
private fun GallerySearchField(searchText: String, onSearchTextChanged: (String) -> Unit) {
  val searchDescription = stringResource(R.string.gallery_search)
  val clearSearchDescription = stringResource(R.string.gallery_clear_search)
  OutlinedTextField(
    value = searchText,
    onValueChange = onSearchTextChanged,
    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
    singleLine = true,
    placeholder = { Text(stringResource(R.string.gallery_search_hint)) },
    leadingIcon = { Icon(Icons.Default.Search, contentDescription = searchDescription) },
    trailingIcon = if (searchText.isNotEmpty()) {
      {
        IconButton(
          onClick = { onSearchTextChanged("") },
          modifier = Modifier.semantics { contentDescription = clearSearchDescription },
        ) { Icon(Icons.Default.Clear, contentDescription = null) }
      }
    } else null,
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
    item { GalleryFilterChip(stringResource(R.string.gallery_filter_all), query.mediaFilter == GalleryMediaFilter.ALL) { onMediaFilterChanged(GalleryMediaFilter.ALL) } }
    item { GalleryFilterChip(stringResource(R.string.gallery_filter_images), query.mediaFilter == GalleryMediaFilter.IMAGE) { onMediaFilterChanged(GalleryMediaFilter.IMAGE) } }
    item { GalleryFilterChip(stringResource(R.string.gallery_filter_videos), query.mediaFilter == GalleryMediaFilter.VIDEO) { onMediaFilterChanged(GalleryMediaFilter.VIDEO) } }
    item { GalleryFilterChip(stringResource(R.string.gallery_filter_local), query.localOnly) { onLocalOnlyChanged(!query.localOnly) } }
  }
}

@Composable
private fun GalleryFilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
  FilterChip(selected = selected, onClick = onClick, label = { Text(label, maxLines = 1) })
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
      Icon(Icons.Default.List, contentDescription = null)
      Spacer(Modifier.width(6.dp))
      Text(stringResource(if (query.newestFirst) R.string.gallery_sort_newest else R.string.gallery_sort_oldest))
    }
    FilledTonalButton(
      onClick = onSync,
      enabled = syncPresentation.syncEnabled && !syncPresentation.isActive,
    ) {
      Icon(Icons.Default.Refresh, contentDescription = null)
      Spacer(Modifier.width(6.dp))
      Text(stringResource(if (syncPresentation.isActive) R.string.gallery_syncing else R.string.gallery_sync))
    }
  }
}

internal enum class SyncPresentationState { IDLE, ACTIVE, RETRYABLE_ERROR, UNAVAILABLE }

internal data class SyncPresentation(
  val state: SyncPresentationState,
  val showRetry: Boolean,
  val syncEnabled: Boolean,
) {
  val isActive: Boolean get() = state == SyncPresentationState.ACTIVE
  val showCompactStatus: Boolean get() = state != SyncPresentationState.IDLE
}

internal fun syncPresentation(state: SyncStateEntity?, result: SavedMediaSyncResult?): SyncPresentation {
  val active = state?.phase in setOf(
    MediaSyncPhase.DISCOVERING_HEAD.name,
    MediaSyncPhase.BACKFILLING.name,
    MediaSyncPhase.CATCHING_UP.name,
  )
  return when {
    active -> SyncPresentation(SyncPresentationState.ACTIVE, showRetry = false, syncEnabled = false)
    result is SavedMediaSyncResult.Failed && result.retryable -> SyncPresentation(SyncPresentationState.RETRYABLE_ERROR, true, true)
    result is SavedMediaSyncResult.Failed -> SyncPresentation(SyncPresentationState.UNAVAILABLE, false, false)
    state?.phase == MediaSyncPhase.ERROR.name -> SyncPresentation(SyncPresentationState.RETRYABLE_ERROR, true, true)
    else -> SyncPresentation(SyncPresentationState.IDLE, false, true)
  }
}

@Composable
private fun SyncStatusCompact(presentation: SyncPresentation, onRetry: () -> Unit) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
      .padding(horizontal = 10.dp, vertical = 6.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    if (presentation.isActive) {
      CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
    } else if (presentation.state == SyncPresentationState.UNAVAILABLE) {
      Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
    }
    Text(
      text = stringResource(
        when (presentation.state) {
          SyncPresentationState.ACTIVE -> R.string.gallery_sync_status_active
          SyncPresentationState.RETRYABLE_ERROR -> R.string.gallery_sync_status_retryable
          SyncPresentationState.UNAVAILABLE -> R.string.gallery_sync_status_unavailable
          SyncPresentationState.IDLE -> R.string.gallery_sync_status_idle
        },
      ),
      style = MaterialTheme.typography.bodySmall,
      modifier = Modifier.weight(1f),
    )
    if (presentation.showRetry) {
      TextButton(onClick = onRetry) { Text(stringResource(R.string.gallery_retry)) }
    }
  }
  if (presentation.isActive) {
    LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
  }
}

@Composable
private fun OpenStatus(openState: GalleryOpenState, onRetry: () -> Unit) {
  when (openState) {
    GalleryOpenState.Idle,
    is GalleryOpenState.Opened,
    -> Unit
    GalleryOpenState.Loading -> InlineLoading(stringResource(R.string.gallery_opening))
    GalleryOpenState.Unavailable -> InlineError(stringResource(R.string.gallery_unavailable), onRetry = {}, showRetry = false)
    is GalleryOpenState.Failed -> InlineError(stringResource(R.string.gallery_open_error), onRetry = onRetry)
  }
}

@Composable
private fun GalleryGrid(
  items: LazyPagingItems<GalleryItemUiModel>,
  thumbnailPaths: Map<String, String>,
  state: LazyGridState,
  thumbnailLoader: GalleryThumbnailLoader,
  onLoadThumbnail: (SavedMediaEntity) -> Unit,
  onOpenMedia: (SavedMediaEntity) -> Unit,
  modifier: Modifier = Modifier,
) {
  val density = LocalDensity.current
  val targetSizePx = (220.dp.value * density.density).roundToInt()
  val gridDescription = stringResource(R.string.gallery_grid_description)
  BoxWithConstraints(modifier = modifier) {
    LazyVerticalGrid(
      columns = GridCells.Fixed(galleryColumnCount(maxWidth.value.roundToInt())),
      state = state,
      modifier = Modifier
        .fillMaxSize()
        .semantics { contentDescription = gridDescription },
      contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp),
      horizontalArrangement = Arrangement.spacedBy(10.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      items(
        count = items.itemCount,
        key = { index -> items.peek(index)?.stableKey ?: "placeholder:$index" },
      ) { index ->
        val item = items[index]
        if (item != null) {
          LaunchedEffect(item.thumbnailStableIdentity, item.source.accountId, item.source.databaseGeneration) {
            onLoadThumbnail(item.source)
          }
          GalleryTile(
            item = item.copy(thumbnailPath = item.thumbnailStableIdentity?.let(thumbnailPaths::get)),
            thumbnailLoader = thumbnailLoader,
            targetSizePx = targetSizePx,
            onClick = { onOpenMedia(item.source) },
          )
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
          InlineError(stringResource(R.string.gallery_more_error), onRetry = items::retry)
        }
      }
    }
  }
}

@Composable
internal fun GalleryTile(
  item: GalleryItemUiModel,
  thumbnailLoader: GalleryThumbnailLoader,
  targetSizePx: Int,
  onClick: () -> Unit,
) {
  val displayName = if (item.usesFallbackName) {
    stringResource(if (item.mediaType == GalleryItemMediaType.VIDEO) R.string.gallery_untitled_video else R.string.gallery_untitled_image)
  } else item.displayName
  val typeLabel = stringResource(if (item.mediaType == GalleryItemMediaType.VIDEO) R.string.gallery_media_type_video else R.string.gallery_media_type_image)
  val availabilityLabel = stringResource(availabilityString(item.availability))
  val dateLabel = item.dateText ?: stringResource(R.string.gallery_unknown_date)
  val accessibilityDescription = buildString {
    append(typeLabel).append(": ").append(displayName)
    item.durationText?.let { append(", ").append(it) }
    item.fileSizeText?.let { append(", ").append(it) }
    item.resolutionText?.let { append(", ").append(it) }
    append(", ").append(dateLabel).append(", ").append(availabilityLabel)
  }
  val enabled = item.availability != GalleryFileAvailability.UNAVAILABLE

  Card(
    onClick = onClick,
    enabled = enabled,
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
      GalleryMediaThumbnail(item, thumbnailLoader, targetSizePx)
      Text(text = displayName, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
      item.metadataText?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
      Text(dateLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      GalleryAvailabilityChip(item.availability)
    }
  }
}

@Composable
private fun GalleryAvailabilityChip(availability: GalleryFileAvailability) {
  val containerColor = when (availability) {
    GalleryFileAvailability.LOCAL_COMPLETE -> MaterialTheme.colorScheme.primaryContainer
    GalleryFileAvailability.REMOTE_STREAMABLE -> MaterialTheme.colorScheme.secondaryContainer
    GalleryFileAvailability.PARTIAL -> MaterialTheme.colorScheme.tertiaryContainer
    GalleryFileAvailability.DOWNLOADING -> MaterialTheme.colorScheme.surfaceVariant
    GalleryFileAvailability.UNAVAILABLE -> MaterialTheme.colorScheme.errorContainer
  }
  val contentColor = when (availability) {
    GalleryFileAvailability.UNAVAILABLE -> MaterialTheme.colorScheme.onErrorContainer
    GalleryFileAvailability.LOCAL_COMPLETE -> MaterialTheme.colorScheme.onPrimaryContainer
    GalleryFileAvailability.REMOTE_STREAMABLE -> MaterialTheme.colorScheme.onSecondaryContainer
    GalleryFileAvailability.PARTIAL -> MaterialTheme.colorScheme.onTertiaryContainer
    GalleryFileAvailability.DOWNLOADING -> MaterialTheme.colorScheme.onSurfaceVariant
  }
  Surface(
    shape = RoundedCornerShape(6.dp),
    color = containerColor,
    contentColor = contentColor,
  ) {
    Text(
      text = stringResource(availabilityString(availability)),
      style = MaterialTheme.typography.labelSmall,
      modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
    )
  }
}

private fun availabilityString(availability: GalleryFileAvailability): Int = when (availability) {
  GalleryFileAvailability.LOCAL_COMPLETE -> R.string.gallery_availability_local
  GalleryFileAvailability.REMOTE_STREAMABLE -> R.string.gallery_availability_remote
  GalleryFileAvailability.PARTIAL -> R.string.gallery_availability_partial
  GalleryFileAvailability.DOWNLOADING -> R.string.gallery_availability_downloading
  GalleryFileAvailability.UNAVAILABLE -> R.string.gallery_availability_unavailable
}

@Composable
private fun GalleryMediaThumbnail(item: GalleryItemUiModel, loader: GalleryThumbnailLoader, targetSizePx: Int) {
  val source = remember(item.stableKey, item.thumbnailStableIdentity, item.thumbnailPath, item.minithumbnailData) {
    GalleryThumbnailSource(
      accountIdentity = com.nmtuong.telegramdrive.domain.AccountSessionIdentity(item.source.accountId, item.source.databaseGeneration),
      stableIdentity = item.thumbnailStableIdentity ?: item.stableKey,
      filePath = item.thumbnailPath,
      minithumbnailData = item.minithumbnailData,
    )
  }
  val bitmap by produceState<android.graphics.Bitmap?>(null, source, targetSizePx) {
    value = loader.load(source, targetSizePx, targetSizePx)
  }
  val shape = RoundedCornerShape(10.dp)
  Box(
    modifier = Modifier
      .fillMaxWidth()
      .aspectRatio(1f)
      .clip(shape)
      .background(MaterialTheme.colorScheme.surface),
    contentAlignment = Alignment.Center,
  ) {
    if (bitmap != null) {
      Image(
        bitmap = bitmap!!.asImageBitmap(),
        contentDescription = null,
        modifier = Modifier.fillMaxSize(),
        contentScale = ContentScale.Crop,
      )
    } else {
      Text(
        text = stringResource(if (item.mediaType == GalleryItemMediaType.VIDEO) R.string.gallery_media_type_video else R.string.gallery_media_type_image),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    if (item.mediaType == GalleryItemMediaType.VIDEO) {
      Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f)))
      Surface(
        modifier = Modifier.size(44.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
        contentColor = MaterialTheme.colorScheme.onSurface,
      ) { Icon(Icons.Default.PlayArrow, contentDescription = null) }
      item.durationText?.let {
        Surface(
          modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
          shape = RoundedCornerShape(6.dp),
          color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.78f),
          contentColor = MaterialTheme.colorScheme.onSurface,
        ) { Text(it, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)) }
      }
    }
  }
}

@Composable
private fun InitialLoadingPanel() {
  Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
    CircularProgressIndicator()
    Spacer(Modifier.height(12.dp))
    Text(stringResource(R.string.gallery_loading), style = MaterialTheme.typography.bodyMedium)
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
    isSyncing -> R.string.gallery_empty_syncing_title
    hasActiveQuery -> R.string.gallery_no_results_title
    syncError -> R.string.gallery_empty_sync_error_title
    else -> R.string.gallery_empty_title
  }
  val message = when {
    isSyncing -> R.string.gallery_empty_syncing_message
    hasActiveQuery -> R.string.gallery_no_results_message
    syncError -> R.string.gallery_empty_sync_error_message
    else -> R.string.gallery_empty_message
  }
  Column(
    modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
  ) {
    Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))
    Text(stringResource(message), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(16.dp))
    when {
      hasActiveQuery -> TextButton(onClick = onClearQuery) { Text(stringResource(R.string.gallery_clear_filters)) }
      canRetrySync -> Button(onClick = onRetry) { Text(stringResource(R.string.gallery_retry)) }
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
    Text(stringResource(R.string.gallery_load_error_title), style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))
    Text(stringResource(R.string.gallery_load_error_message), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(16.dp))
    Button(onClick = onRetry) { Text(stringResource(R.string.gallery_retry)) }
  }
}

@Composable
private fun InlineLoading(message: String) {
  Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    CircularProgressIndicator(Modifier.size(18.dp))
    Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
  }
}

@Composable
private fun InlineError(message: String, onRetry: () -> Unit, showRetry: Boolean = true) {
  Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
    if (showRetry) TextButton(onClick = onRetry) { Text(stringResource(R.string.gallery_retry)) }
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
  val thumbnailLoader = remember { GalleryThumbnailLoader() }
  DisposableEffect(thumbnailLoader) { onDispose { thumbnailLoader.close() } }
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
      if (presentation.showCompactStatus) SyncStatusCompact(presentation, onRetry = {})
      Box(Modifier.fillMaxWidth().weight(1f)) {
        when (state) {
          GalleryPreviewState.CONTENT,
          GalleryPreviewState.SYNCING,
          -> PreviewGalleryGrid(items, thumbnailLoader)
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
private fun PreviewGalleryGrid(items: List<SavedMediaEntity>, thumbnailLoader: GalleryThumbnailLoader) {
  val previewItems = remember(items) {
    items.map { galleryItemUiModel(it, com.nmtuong.telegramdrive.domain.AccountSessionIdentity(1L, 1L), Locale.getDefault()) }
  }
  val density = LocalDensity.current
  val targetSizePx = (220.dp.value * density.density).roundToInt()
  BoxWithConstraints(Modifier.fillMaxSize()) {
    LazyVerticalGrid(
      columns = GridCells.Fixed(galleryColumnCount(maxWidth.value.roundToInt())),
      modifier = Modifier.fillMaxSize(),
      contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp),
      horizontalArrangement = Arrangement.spacedBy(10.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      items(previewItems, key = { it.stableKey }) { item ->
        GalleryTile(item, thumbnailLoader, targetSizePx, onClick = {})
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

private fun previewEntity(messageId: Long, displayName: String, mediaType: String, durationSeconds: Int, dateEpochSeconds: Long) = SavedMediaEntity(
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
  TelegramDriveTheme { GalleryPreviewFrame(GalleryPreviewState.NO_RESULTS, query = GalleryQuery(search = "receipt", mediaFilter = GalleryMediaFilter.VIDEO)) }
}

@Preview(name = "Gallery error", showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun GalleryErrorPreview() {
  TelegramDriveTheme { GalleryPreviewFrame(GalleryPreviewState.ERROR) }
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
  TelegramDriveTheme { GalleryPreviewFrame(GalleryPreviewState.CONTENT, syncState = previewCompletedSyncState()) }
}

@Preview(name = "Long filename", showBackground = true, widthDp = 320, heightDp = 844, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun GalleryLongFilenamePreview() {
  TelegramDriveTheme {
    GalleryPreviewFrame(
      state = GalleryPreviewState.CONTENT,
      syncState = previewCompletedSyncState(),
      items = listOf(
        previewEntity(10, "2026-07-21_family-trip_camera-export_final-final-2.jpg", "IMAGE", 0, 1_725_000_000L),
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

private fun previewCompletedSyncState() = previewSyncingState().copy(phase = MediaSyncPhase.COMPLETED.name, lastSuccessfulCatchUpHead = 20L)
