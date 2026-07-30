package com.nmtuong.telegramdrive.feature.library

import com.nmtuong.telegramdrive.data.TelegramRepository
import com.nmtuong.telegramdrive.domain.DownloadState
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
        // Note: The actual progress and completion will still come from TdLibJsonGateway updateFile
        // In a full implementation, we'd listen to the repository's file stream.
      } catch (e: Exception) {
        updateState(fileId, DownloadState.Failed(e.message ?: "Download failed"))
      } finally {
        semaphore.release()
      }
    }
    downloadJobs[fileId] = job
  }

  fun cancelDownload(fileId: Int) {
    downloadJobs.remove(fileId)?.cancel()
    repository.cancelDownload(fileId)
    updateState(fileId, DownloadState.Canceled)
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
