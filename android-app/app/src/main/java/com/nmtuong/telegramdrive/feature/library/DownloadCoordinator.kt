package com.nmtuong.telegramdrive.feature.library

import com.nmtuong.telegramdrive.data.TelegramRepository
import com.nmtuong.telegramdrive.domain.DownloadState
import com.nmtuong.telegramdrive.domain.LibraryState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import java.util.concurrent.ConcurrentHashMap

class DownloadCoordinator(
  private val repository: TelegramRepository,
  private val scope: CoroutineScope
) {

  private val _activeDownloads = MutableStateFlow<Map<Int, DownloadState>>(emptyMap())
  val activeDownloads: StateFlow<Map<Int, DownloadState>> = _activeDownloads

  private val downloadJobs = ConcurrentHashMap<Int, Job>()
  // Concurrency limit of 3 concurrent downloads
  private val semaphore = Semaphore(3)

  fun startDownload(fileId: Int) {
    if (downloadJobs.containsKey(fileId)) return

    val job = scope.launch(Dispatchers.IO) {
      updateState(fileId, DownloadState.Downloading(0))
      semaphore.acquire()
      try {
        repository.download(fileId)
        
        repository.library.collect { state ->
            if (state is LibraryState.Content) {
                val item = state.items.firstOrNull { it.fileId == fileId }
                if (item != null) {
                    updateState(fileId, item.downloadState)
                    if (item.downloadState is DownloadState.Complete || item.downloadState is DownloadState.Failed || item.downloadState is DownloadState.Canceled) {
                        throw CancellationException("Terminal state reached")
                    }
                }
            }
        }
      } catch (e: CancellationException) {
        // Expected on terminal state
      } catch (e: Exception) {
        updateState(fileId, DownloadState.Failed(e.message ?: "Download failed"))
      } finally {
        semaphore.release()
        downloadJobs.remove(fileId)
        val currentState = _activeDownloads.value[fileId]
        if (currentState is DownloadState.Complete || currentState is DownloadState.Canceled || currentState is DownloadState.Failed) {
            _activeDownloads.value = _activeDownloads.value.toMutableMap().apply { remove(fileId) }
        }
      }
    }
    // Safely deduplicate
    val previous = downloadJobs.putIfAbsent(fileId, job)
    if (previous != null) {
        job.cancel()
    }
  }

  fun cancelDownload(fileId: Int) {
    downloadJobs.remove(fileId)?.cancel()
    repository.cancelDownload(fileId)
    updateState(fileId, DownloadState.Canceled)
    _activeDownloads.value = _activeDownloads.value.toMutableMap().apply { remove(fileId) }
  }

  private fun updateState(fileId: Int, state: DownloadState) {
    _activeDownloads.value = _activeDownloads.value.toMutableMap().apply {
      put(fileId, state)
    }
  }

  fun clear() {
    downloadJobs.values.forEach { it.cancel() }
    downloadJobs.clear()
    _activeDownloads.value = emptyMap()
  }
}
