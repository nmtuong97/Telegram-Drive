package com.nmtuong.telegramdrive.feature.library

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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

@Composable
fun LibraryScreen(viewModel: LibraryViewModel, onPreview: (PreviewTarget) -> Unit) {
    val lazyPagingItems = viewModel.pagingDataFlow.collectAsLazyPagingItems()
    val sources by viewModel.sources.collectAsStateWithLifecycle()
    val selectedSourceId by viewModel.selectedSourceId.collectAsStateWithLifecycle()
    val transferStates by viewModel.transferStates.collectAsStateWithLifecycle()

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
                text = stringResource(R.string.saved_messages),
                style = MaterialTheme.typography.headlineSmall,
            )
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
                            text = refreshState.error.message ?: "Failed to load history",
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
                                key = { index -> lazyPagingItems[index]?.id ?: index.toLong() },
                            ) { index ->
                                val item = lazyPagingItems[index]
                                if (item != null) {
                                    val transferState = transferStates[item.fileId] ?: item.downloadState.toTransferState()
                                    MediaCard(
                                        item = item,
                                        transferState = transferState,
                                        onDownload = { viewModel.download(item.fileId) },
                                        onCancel = { viewModel.cancel(item.fileId) },
                                        onPreview = {
                                            viewModel.preview(item.id)?.let(onPreview)
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
                                                text = appendState.error.message ?: "Failed to load more",
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
            Text(item.name, style = MaterialTheme.typography.titleMedium)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = item.kind.name.lowercase(),
                    style = MaterialTheme.typography.bodySmall,
                )
                if (item.sizeBytes > 0) {
                    Text(
                        text = formatBytes(item.sizeBytes),
                        style = MaterialTheme.typography.bodySmall,
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

private fun DownloadState.toTransferState(): TransferState = when (this) {
    DownloadState.NotDownloaded -> TransferState.NotStarted
    is DownloadState.Downloading -> TransferState.InProgress(percent)
    DownloadState.Complete -> TransferState.Completed
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
