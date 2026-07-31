package com.nmtuong.telegramdrive.feature.library

import com.nmtuong.telegramdrive.data.TelegramRepository
import com.nmtuong.telegramdrive.data.TransferCoordinator
import com.nmtuong.telegramdrive.domain.TransferIdentity
import com.nmtuong.telegramdrive.domain.TransferState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Adapter that exposes [TransferCoordinator] to the feature layer.
 *
 * Single source of truth is [TransferCoordinator.transferStates].
 * The previous [DownloadCoordinator._activeDownloads] second map has been removed.
 *
 * Lifecycle: scope is the ViewModel's scope (cleared on ViewModel.onCleared).
 */
class DownloadCoordinator(
    repository: TelegramRepository,
    scope: CoroutineScope,
    private val accountId: Long = 0L,
    private val databaseGeneration: Long = 1L,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val coordinator = TransferCoordinator(
        repository = repository,
        accountId = accountId,
        databaseGeneration = databaseGeneration,
        dispatcher = dispatcher,
    )

    /** Single source of truth: fileId → TransferState */
    val transferStates: StateFlow<Map<Int, TransferState>> = coordinator.transferStates

    fun startDownload(fileId: Int) {
        coordinator.startTransfer(
            fileId = fileId,
            identity = TransferIdentity(
                accountId = accountId,
                databaseGeneration = databaseGeneration,
                fileId = fileId,
            ),
        )
    }

    fun cancelDownload(fileId: Int) {
        coordinator.cancelTransfer(fileId)
    }

    fun clear() {
        coordinator.clear()
    }
}
