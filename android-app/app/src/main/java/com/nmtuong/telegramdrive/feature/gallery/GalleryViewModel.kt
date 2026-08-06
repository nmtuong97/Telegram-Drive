package com.nmtuong.telegramdrive.feature.gallery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.insertSeparators
import androidx.paging.map
import com.nmtuong.telegramdrive.data.AccountSessionIdentityProvider
import com.nmtuong.telegramdrive.data.GalleryQuery
import com.nmtuong.telegramdrive.data.GalleryMediaFilter
import com.nmtuong.telegramdrive.data.MediaAccessCoordinator
import com.nmtuong.telegramdrive.data.MediaOpenResult
import com.nmtuong.telegramdrive.data.SavedMediaRepository
import com.nmtuong.telegramdrive.data.SavedMediaSyncResult
import com.nmtuong.telegramdrive.data.local.SavedMediaEntity
import com.nmtuong.telegramdrive.data.local.SyncStateEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

class GalleryViewModel(
  private val repository: SavedMediaRepository,
  private val mediaAccess: MediaAccessCoordinator,
  private val identityProvider: AccountSessionIdentityProvider,
) : ViewModel() {
  private val _query = MutableStateFlow(GalleryQuery())
  val query: StateFlow<GalleryQuery> = _query.asStateFlow()
  private val _syncState = MutableStateFlow<SyncStateEntity?>(null)
  val syncState: StateFlow<SyncStateEntity?> = _syncState.asStateFlow()
  private val _syncResult = MutableStateFlow<SavedMediaSyncResult?>(null)
  val syncResult: StateFlow<SavedMediaSyncResult?> = _syncResult.asStateFlow()
  private val _thumbnailPaths = MutableStateFlow<Map<String, String>>(emptyMap())
  val thumbnailPaths: StateFlow<Map<String, String>> = _thumbnailPaths.asStateFlow()
  private val _openState = MutableStateFlow<GalleryOpenState>(GalleryOpenState.Idle)
  val openState: StateFlow<GalleryOpenState> = _openState.asStateFlow()
  private var lastOpenEntity: SavedMediaEntity? = null
  private var syncJob: Job? = null

  @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
  val pagingData: Flow<PagingData<GalleryGridItem>> = _query
    .debounce(150)
    .distinctUntilChanged()
    .flatMapLatest(repository::paging)
    .map { paging ->
      paging
        .map { entity -> GalleryGridItem.Media(entity) }
        .insertSeparators { before, after -> monthHeaderFor(before, after) }
    }
    .cachedIn(viewModelScope)

  init {
    repository.start()
    viewModelScope.launch {
      repository.observeCurrentSyncState().collect { _syncState.value = it }
    }
    viewModelScope.launch {
      identityProvider.currentIdentity
        .collect { identity ->
          syncJob?.cancel()
          _syncResult.value = null
          _syncState.value = null
          _thumbnailPaths.value = emptyMap()
          _openState.value = GalleryOpenState.Idle
          lastOpenEntity = null
          if (identity != null) refreshSync()
        }
    }
  }

  fun setSearch(search: String) = _query.update { it.copy(search = search) }

  fun setMediaFilter(filter: GalleryMediaFilter) = _query.update { it.copy(mediaFilter = filter) }

  fun setLocalOnly(localOnly: Boolean) = _query.update { it.copy(localOnly = localOnly) }

  fun toggleSort() = _query.update { it.copy(newestFirst = !it.newestFirst) }

  fun refreshSync() {
    val requestIdentity = identityProvider.currentIdentity.value ?: return
    if (syncJob?.isActive == true) return
    syncJob = viewModelScope.launch {
      val result = repository.syncSavedMessages()
      if (shouldPublishSyncResult(requestIdentity, identityProvider.currentIdentity.value)) {
        _syncResult.value = result
      }
    }
  }

  fun loadThumbnail(entity: SavedMediaEntity) {
    val key = entity.thumbnailStableFileIdentity ?: return
    val existingPath = _thumbnailPaths.value[key]
    if (existingPath != null && File(existingPath).isFile && File(existingPath).canRead()) return
    if (existingPath != null) _thumbnailPaths.update { it - key }
    viewModelScope.launch {
      mediaAccess.ensureThumbnail(entity)?.let { path ->
        _thumbnailPaths.update { it + (key to path) }
      }
    }
  }

  fun openMedia(entity: SavedMediaEntity) {
    if (_openState.value is GalleryOpenState.Loading || _openState.value is GalleryOpenState.Opened) return
    lastOpenEntity = entity
    if (entity.mediaType == "VIDEO") {
      // Opening a video must never wait for a fixed remote prefix. The player
      // validates this local candidate and otherwise starts TDLib range loading.
      _openState.value = GalleryOpenState.Opened(entity, entity.localFilePath.orEmpty())
      return
    }
    viewModelScope.launch {
      _openState.value = GalleryOpenState.Loading
      _openState.value = when (val result = mediaAccess.openOriginal(entity)) {
        is MediaOpenResult.Opened -> GalleryOpenState.Opened(entity, result.path)
        is MediaOpenResult.Failed -> GalleryOpenState.Failed(result.message)
      }
    }
  }

  fun retryOpen() {
    lastOpenEntity?.let(::openMedia)
  }

  fun consumeOpenState() {
    _openState.value = GalleryOpenState.Idle
  }
}

internal fun shouldPublishSyncResult(
  requestIdentity: com.nmtuong.telegramdrive.domain.AccountSessionIdentity,
  currentIdentity: com.nmtuong.telegramdrive.domain.AccountSessionIdentity?,
): Boolean = requestIdentity == currentIdentity

sealed interface GalleryOpenState {
  data object Idle : GalleryOpenState
  data object Loading : GalleryOpenState
  data class Opened(val entity: SavedMediaEntity, val path: String) : GalleryOpenState
  data class Failed(val message: String) : GalleryOpenState
}
