package com.nmtuong.telegramdrive.feature.library

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import com.nmtuong.telegramdrive.R
import com.nmtuong.telegramdrive.domain.*
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    onPreview: (PreviewTarget) -> Unit,
    onOpenGallery: (() -> Unit)? = null,
) {
    val lazyPagingItems = viewModel.pagingDataFlow.collectAsLazyPagingItems()
    val sources by viewModel.sources.collectAsStateWithLifecycle()
    val selectedSourceId by viewModel.selectedSourceId.collectAsStateWithLifecycle()
    val sourceError by viewModel.sourceError.collectAsStateWithLifecycle()
    val transferStates by viewModel.transferStates.collectAsStateWithLifecycle()
    val selectedSourceTitle = sources.firstOrNull { it.id == selectedSourceId }?.title
        ?: stringResource(R.string.saved_messages)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Header bar with logout
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = selectedSourceTitle,
                style = MaterialTheme.typography.headlineSmall,
            )
            if (onOpenGallery != null) {
                TextButton(onClick = onOpenGallery) { Text("Gallery") }
            }
            TextButton(onClick = viewModel::logout) {
                Text(stringResource(R.string.logout))
            }
        }

        // Minimal Source Browser (Checkpoint 8)
        if (sources.isNotEmpty()) {
            Text(
                text = stringResource(R.string.source_selector),
                style = MaterialTheme.typography.labelMedium,
            )
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                items(sources, key = { it.id }) { source ->
                    FilterChip(
                        selected = source.id == selectedSourceId,
                        onClick = { viewModel.selectSource(source.id) },
                        label = { Text(source.title) },
                    )
                }
            }
        }
        if (sourceError != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = sourceError!!,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = viewModel::reloadSources) {
                    Text(stringResource(R.string.retry))
                }
            }
        }

        // Paging UI (Checkpoint 7)
        Box(modifier = Modifier.fillMaxSize()) {
            val loadState = lazyPagingItems.loadState

            when (val refreshState = loadState.refresh) {
                is LoadState.Loading -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
                is LoadState.Error -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = refreshState.error.message?.takeIf { it.isNotBlank() } ?: "Failed to load history",
                            color = MaterialTheme.colorScheme.error,
                        )
                        Button(onClick = { lazyPagingItems.retry() }) {
                            Text(stringResource(R.string.retry))
                        }
                    }
                }
                is LoadState.NotLoading -> {
                    if (lazyPagingItems.itemCount == 0) {
                        Column(
                            modifier = Modifier.align(Alignment.Center),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(stringResource(R.string.empty_library))
                            Button(onClick = { lazyPagingItems.refresh() }) {
                                Text(stringResource(R.string.refresh))
                            }
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(
                                count = lazyPagingItems.itemCount,
                                key = { index ->
                                    lazyPagingItems[index]?.let { "${it.sourceId}:${it.id}" } ?: "placeholder:$index"
                                },
                            ) { index ->
                                val item = lazyPagingItems[index]
                                if (item != null) {
                                    val transferState = transferStates[item.fileId] ?: item.downloadState.toTransferState(item.localPath)
                                    MediaCard(
                                        item = item,
                                        transferState = transferState,
                                        onDownload = { viewModel.download(item) },
                                        onCancel = { viewModel.cancel(item.fileId) },
                                        onPreview = {
                                            viewModel.previewPagingItem(item.id, item.kind, item.fileId, item.localPath)?.let(onPreview)
                                        },
                                    )
                                }
                            }

                            // Append state at bottom
                            when (val appendState = loadState.append) {
                                is LoadState.Loading -> {
                                    item {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(8.dp),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            CircularProgressIndicator(modifier = Modifier.size(24.dp))
                                        }
                                    }
                                }
                                is LoadState.Error -> {
                                    item {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(8.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Text(
                                                text = appendState.error.message?.takeIf { it.isNotBlank() } ?: "Failed to load more",
                                                color = MaterialTheme.colorScheme.error,
                                                style = MaterialTheme.typography.bodySmall,
                                            )
                                            Button(onClick = { lazyPagingItems.retry() }) {
                                                Text(stringResource(R.string.retry))
                                            }
                                        }
                                    }
                                }
                                is LoadState.NotLoading -> Unit
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MediaCard(
    item: MediaItem,
    transferState: TransferState,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onPreview: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MediaKindBadge(item.kind)
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Text(item.name, style = MaterialTheme.typography.titleMedium)
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = item.kind.name.lowercase(),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            text = if (item.sizeBytes > 0) formatBytes(item.sizeBytes) else "Size unavailable",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    val metadata = buildList {
                        add(formatDate(item.dateEpochSeconds))
                        when {
                            item.durationSeconds > 0 -> add(formatDuration(item.durationSeconds))
                            item.kind in setOf(MediaKind.VIDEO, MediaKind.ANIMATION, MediaKind.AUDIO) -> {
                                add("Duration unavailable")
                            }
                        }
                    }
                    Text(
                        text = metadata.joinToString(" • "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = transferState.statusLabel(),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (transferState is TransferState.TransferFailed) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }

            when (transferState) {
                is TransferState.InProgress -> {
                    LinearProgressIndicator(
                        progress = { transferState.percent / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                is TransferState.TransferFailed -> {
                    Text(
                        text = transferState.reason,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                TransferState.TransferCancelled -> {
                    Text(
                        text = stringResource(R.string.download_canceled),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                TransferState.Unavailable -> {
                    Text(
                        text = "File unavailable",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                else -> Unit
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (transferState is TransferState.Completed) {
                    Button(onClick = onPreview) {
                        Text(stringResource(R.string.preview))
                    }
                } else if (transferState !is TransferState.InProgress && transferState !is TransferState.Queued) {
                    Button(onClick = onDownload) {
                        Text(stringResource(R.string.download))
                    }
                }

                if (transferState is TransferState.InProgress || transferState is TransferState.Queued) {
                    TextButton(onClick = onCancel) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            }
        }
    }
}

@Composable
private fun MediaKindBadge(kind: MediaKind) {
    Surface(
        modifier = Modifier.size(36.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = kind.name.first().toString(),
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

private fun DownloadState.toTransferState(localPath: String?): TransferState = when (this) {
    DownloadState.NotDownloaded -> TransferState.NotStarted
    is DownloadState.Downloading -> TransferState.InProgress(percent)
    DownloadState.Complete -> localPath?.takeIf { File(it).isFile }
        ?.let(TransferState::Completed)
        ?: TransferState.NotStarted
    DownloadState.Canceled -> TransferState.TransferCancelled
    is DownloadState.Failed -> TransferState.TransferFailed(reason)
    DownloadState.Unavailable -> TransferState.Unavailable
}


private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val exp = (Math.log(bytes.toDouble()) / Math.log(1024.0)).toInt()
    val pre = "KMGTPE"[exp - 1]
    return String.format("%.1f %sB", bytes / Math.pow(1024.0, exp.toDouble()), pre)
}

private val mediaDateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.getDefault())

private fun formatDate(epochSeconds: Long): String = if (epochSeconds > 0) {
    Instant.ofEpochSecond(epochSeconds)
        .atZone(ZoneId.systemDefault())
        .toLocalDate()
        .format(mediaDateFormatter)
} else {
    "Date unavailable"
}

private fun formatDuration(totalSeconds: Int): String {
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.getDefault(), "%d:%02d", minutes, seconds)
    }
}

private fun TransferState.statusLabel(): String = when (this) {
    TransferState.NotStarted -> "Remote"
    TransferState.Queued -> "Queued"
    is TransferState.InProgress -> "Downloading"
    is TransferState.Completed -> "Local"
    is TransferState.TransferFailed -> "Error"
    TransferState.TransferCancelled -> "Canceled"
    TransferState.Unavailable -> "Unavailable"
}
