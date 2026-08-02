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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * Checkpoint 1, 2, 4, 7, 8: LibraryViewModel — authorization & identity-driven source loading.
 *
 * Source loading and coordinator creation ONLY trigger after AuthorizationState.Ready AND valid non-zero identity resolution.
 */
class LibraryViewModel(
    private val repository: TelegramRepository,
    private val identityProvider: AccountSessionIdentityProvider? = null,
    private val transferDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val _transferStates = MutableStateFlow<Map<Int, TransferState>>(emptyMap())
    val transferStates: StateFlow<Map<Int, TransferState>> = _transferStates.asStateFlow()

    private var activeCoordinator: DownloadCoordinator? = null
    private var coordinatorJob: Job? = null

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
        // CP1+CP2+CP4: Combine authorization & identity state
        viewModelScope.launch {
            val identityFlow: Flow<AccountSessionIdentity?> = identityProvider?.currentIdentity ?: repository.authorization.map { auth ->
                if (auth.state == AuthorizationState.Ready) AccountSessionIdentity(1L, 1L) else null
            }
            combine(repository.authorization, identityFlow) { auth, identity ->
                Pair(auth.state, identity)
            }.collect { (authState, identity) ->
                if (authState == AuthorizationState.Ready && identity != null && identity.accountId != 0L) {
                    onSessionReady(identity)
                } else if (authState is AuthorizationState.Closed || authState is AuthorizationState.LoggingOut || authState is AuthorizationState.Closing || identity == null) {
                    onSessionClosed()
                }
            }
        }
    }

    private fun onSessionReady(identity: AccountSessionIdentity) {
        if (activeCoordinator != null) return

        val newCoordinator = DownloadCoordinator(
            repository = repository,
            accountId = identity.accountId,
            databaseGeneration = identity.databaseGeneration,
            dispatcher = transferDispatcher,
            activeGenerationProvider = { identityProvider?.databaseGeneration ?: identity.databaseGeneration },
        )
        activeCoordinator = newCoordinator

        coordinatorJob?.cancel()
        coordinatorJob = viewModelScope.launch {
            newCoordinator.transferStates.collect { states ->
                _transferStates.value = states
            }
        }

        viewModelScope.launch { loadSourcesInternal() }
    }

    private fun onSessionClosed() {
        coordinatorJob?.cancel()
        coordinatorJob = null
        activeCoordinator?.close()
        activeCoordinator = null

        _transferStates.value = emptyMap()
        _sources.value = emptyList()
        _selectedSourceId.value = null
        _sourceError.value = null
    }

    private suspend fun loadSourcesInternal() {
        val identity = identityProvider?.currentIdentity?.value ?: AccountSessionIdentity(1L, 1L)
        if (identity.accountId == 0L || repository.authorization.value.state != AuthorizationState.Ready) {
            return // Never load sources before valid identity
        }

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
        val identity = identityProvider?.currentIdentity?.value ?: AccountSessionIdentity(1L, 1L)
        if (identity.accountId == 0L) return
        val request = TransferRequest(
            identity = TransferIdentity(identity.accountId, identity.databaseGeneration, item.fileId),
            messageId = item.id,
            sourceId = item.sourceId,
            fileId = item.fileId,
            mediaKind = item.kind,
            expectedSizeBytes = item.sizeBytes,
            knownLocalPath = item.localPath,
        )
        activeCoordinator?.startDownload(request)
    }

    fun download(fileId: Int) {
        val identity = identityProvider?.currentIdentity?.value ?: AccountSessionIdentity(1L, 1L)
        if (identity.accountId == 0L) return
        activeCoordinator?.startDownload(
            TransferRequest(
                identity = TransferIdentity(identity.accountId, identity.databaseGeneration, fileId),
                messageId = fileId.toLong(),
                sourceId = 0L,
                fileId = fileId,
                mediaKind = MediaKind.DOCUMENT,
            )
        )
    }

    fun cancel(fileId: Int) {
        val identity = identityProvider?.currentIdentity?.value ?: AccountSessionIdentity(1L, 1L)
        activeCoordinator?.cancelDownload(TransferIdentity(identity.accountId, identity.databaseGeneration, fileId))
    }

    fun logout() {
        viewModelScope.launch {
            onSessionClosed()
            repository.logoutAndReset()
        }
    }

    fun previewPagingItem(itemId: Long, mediaKind: MediaKind, fileId: Int): PreviewTarget? {
        val localPath = activeCoordinator?.getCompletedLocalPath(fileId) ?: return null
        return repository.previewPagingItem(itemId, mediaKind, localPath)
    }

    fun preview(itemId: Long): PreviewTarget? = repository.preview(itemId)

    override fun onCleared() {
        super.onCleared()
        onSessionClosed()
    }
}
