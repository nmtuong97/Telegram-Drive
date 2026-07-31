package com.nmtuong.telegramdrive.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.nmtuong.telegramdrive.data.TelegramRepository
import com.nmtuong.telegramdrive.domain.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class LibraryViewModel(
    private val repository: TelegramRepository,
    private val accountId: Long = 1L,
    private val databaseGeneration: Long = 1L,
) : ViewModel() {

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
        loadSources()
    }

    fun loadSources() {
        viewModelScope.launch {
            val available = repository.getAvailableSources()
            _sources.value = available
            if (_selectedSourceId.value == null) {
                val savedMessagesChat = available.firstOrNull { it.savedMessages }?.id
                    ?: repository.getSavedMessagesChatId()
                _selectedSourceId.value = savedMessagesChat
            }
        }
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

    fun preview(itemId: Long): PreviewTarget? = repository.preview(itemId)
}
