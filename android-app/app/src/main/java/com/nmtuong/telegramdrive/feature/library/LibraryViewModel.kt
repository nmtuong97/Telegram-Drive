package com.nmtuong.telegramdrive.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.nmtuong.telegramdrive.data.AccountSessionIdentityProvider
import com.nmtuong.telegramdrive.data.TelegramRepository
import com.nmtuong.telegramdrive.domain.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * CP2: LibraryViewModel — authorization-driven source loading.
 *
 * Source loading ONLY triggers after AuthorizationState.Ready.
 * No calls to getAvailableSources() or getSavedMessagesChatId() before Ready.
 *
 * Flow:
 * AuthorizationState.Ready → loadSources() → set selectedSourceId → Pager starts
 *
 * CP7: Uses AccountSessionIdentityProvider — no hardcoded accountId=1L or databaseGeneration=1L.
 * CP8: DownloadCoordinator is closed on ViewModel.onCleared().
 * CP6: preview() uses coordinator snapshot localPath — no LibraryState lookup.
 */
class LibraryViewModel(
    private val repository: TelegramRepository,
    private val identityProvider: AccountSessionIdentityProvider? = null,
) : ViewModel() {

    // CP7: Derive accountId/generation from provider; fallback to 0/1 for tests using direct construction
    private val accountId: Long get() = identityProvider?.accountId ?: 0L
    private val databaseGeneration: Long get() = identityProvider?.databaseGeneration ?: 1L

    val downloadCoordinator = DownloadCoordinator(
        repository = repository,
        scope = viewModelScope,
        accountId = accountId,
        databaseGeneration = databaseGeneration,
    )

    val transferStates: StateFlow<Map<Int, TransferState>> = downloadCoordinator.transferStates

    private val _sources = MutableStateFlow<List<FileSource>>(emptyList())
    val sources: StateFlow<List<FileSource>> = _sources.asStateFlow()

    private val _selectedSourceId = MutableStateFlow<Long?>(null)
    val selectedSourceId: StateFlow<Long?> = _selectedSourceId.asStateFlow()

    private val _sourceError = MutableStateFlow<String?>(null)
    val sourceError: StateFlow<String?> = _sourceError.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    val pagingDataFlow: Flow<PagingData<MediaItem>> = _selectedSourceId
        .filterNotNull()
        .flatMapLatest { chatId ->
            Pager(
                config = PagingConfig(pageSize = 20, enablePlaceholders = false)
            ) {
                repository.getChatHistoryPagingSource(chatId)
            }.flow
        }
        .cachedIn(viewModelScope)

    init {
        // CP2: Observe authorization state, load sources only after Ready
        viewModelScope.launch {
            repository.authorization.collect { session ->
                when (session.state) {
                    AuthorizationState.Ready -> {
                        // Trigger source load when authorization becomes Ready
                        loadSourcesInternal()
                    }
                    AuthorizationState.Closed,
                    AuthorizationState.LoggingOut,
                    AuthorizationState.Closing -> {
                        // Clear sources and Paging state on logout/reset
                        _sources.value = emptyList()
                        _selectedSourceId.value = null
                        _sourceError.value = null
                    }
                    else -> Unit
                }
            }
        }

        // CP2: If already Ready when ViewModel is created (e.g., configuration change)
        if (repository.authorization.value.state == AuthorizationState.Ready) {
            viewModelScope.launch { loadSourcesInternal() }
        }
    }

    private suspend fun loadSourcesInternal() {
        _sourceError.value = null
        try {
            val available = repository.getAvailableSources()
            _sources.value = available
            if (_selectedSourceId.value == null) {
                val savedMessagesChat = available.firstOrNull { it.savedMessages }?.id
                    ?: repository.getSavedMessagesChatId()
                _selectedSourceId.value = savedMessagesChat
            }
        } catch (e: Exception) {
            _sourceError.value = e.message ?: "Failed to load sources"
        }
    }

    /** CP2: Manual retry after source load failure. */
    fun reloadSources() {
        viewModelScope.launch { loadSourcesInternal() }
    }

    fun selectSource(sourceId: Long) {
        if (_selectedSourceId.value != sourceId) {
            _selectedSourceId.value = sourceId
        }
    }

    fun download(fileId: Int) {
        downloadCoordinator.startDownload(fileId)
    }

    fun cancel(fileId: Int) {
        downloadCoordinator.cancelDownload(fileId)
    }

    fun logout() {
        viewModelScope.launch {
            downloadCoordinator.clear()
            repository.logoutAndReset()
        }
    }

    /**
     * CP6: Preview from Paging item metadata + coordinator snapshot localPath.
     * Does NOT look up item in legacy LibraryState.Content.
     * Falls back to legacy preview() for backward compatibility.
     */
    fun previewPagingItem(itemId: Long, mediaKind: MediaKind, fileId: Int): PreviewTarget? {
        val localPath = downloadCoordinator.getCompletedLocalPath(fileId) ?: return null
        return repository.previewPagingItem(itemId, mediaKind, localPath)
    }

    /** Legacy preview — kept for P1 compatibility. */
    fun preview(itemId: Long): PreviewTarget? = repository.preview(itemId)

    /** CP8: Close coordinator on ViewModel cleared to release session collector scope. */
    override fun onCleared() {
        super.onCleared()
        downloadCoordinator.close()
    }
}
