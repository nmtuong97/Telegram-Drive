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
 * Checkpoint 2, 4, 7, 8: LibraryViewModel — authorization & identity-driven source loading.
 *
 * Source loading ONLY triggers after AuthorizationState.Ready AND valid identity resolution.
 */
class LibraryViewModel(
    private val repository: TelegramRepository,
    private val identityProvider: AccountSessionIdentityProvider? = null,
) : ViewModel() {

    private val accountId: Long get() = identityProvider?.accountId ?: 0L
    private val databaseGeneration: Long get() = identityProvider?.databaseGeneration ?: 1L

    val downloadCoordinator = DownloadCoordinator(
        repository = repository,
        scope = viewModelScope,
        accountId = accountId,
        databaseGeneration = databaseGeneration,
        activeGenerationProvider = { identityProvider?.databaseGeneration ?: 1L },
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
        // CP2+CP4: Observe authorization & identity state
        viewModelScope.launch {
            repository.authorization.collect { session ->
                when (session.state) {
                    AuthorizationState.Ready -> {
                        loadSourcesInternal()
                    }
                    AuthorizationState.Closed,
                    AuthorizationState.LoggingOut,
                    AuthorizationState.Closing -> {
                        _sources.value = emptyList()
                        _selectedSourceId.value = null
                        _sourceError.value = null
                    }
                    else -> Unit
                }
            }
        }

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

    fun reloadSources() {
        viewModelScope.launch { loadSourcesInternal() }
    }

    fun selectSource(sourceId: Long) {
        if (_selectedSourceId.value != sourceId) {
            _selectedSourceId.value = sourceId
        }
    }

    fun download(item: MediaItem) {
        val identity = TransferIdentity(
            accountId = accountId,
            databaseGeneration = databaseGeneration,
            fileId = item.fileId,
        )
        val request = TransferRequest(
            identity = identity,
            messageId = item.id,
            sourceId = item.sourceId,
            fileId = item.fileId,
            mediaKind = item.kind,
            expectedSizeBytes = item.sizeBytes,
            knownLocalPath = item.localPath,
        )
        downloadCoordinator.startDownload(request)
    }

    fun download(fileId: Int) {
        downloadCoordinator.startDownload(fileId)
    }

    fun cancel(fileId: Int) {
        val identity = TransferIdentity(
            accountId = accountId,
            databaseGeneration = databaseGeneration,
            fileId = fileId,
        )
        downloadCoordinator.cancelDownload(identity)
    }

    fun logout() {
        viewModelScope.launch {
            downloadCoordinator.clear()
            repository.logoutAndReset()
        }
    }

    fun previewPagingItem(itemId: Long, mediaKind: MediaKind, fileId: Int): PreviewTarget? {
        val localPath = downloadCoordinator.getCompletedLocalPath(fileId) ?: return null
        return repository.previewPagingItem(itemId, mediaKind, localPath)
    }

    fun preview(itemId: Long): PreviewTarget? = repository.preview(itemId)

    override fun onCleared() {
        super.onCleared()
        downloadCoordinator.close()
    }
}
