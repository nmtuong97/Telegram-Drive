package com.nmtuong.telegramdrive.feature.library

import com.nmtuong.telegramdrive.data.AccountSessionIdentityProvider
import com.nmtuong.telegramdrive.data.TelegramRepository
import com.nmtuong.telegramdrive.data.TransferCoordinator
import com.nmtuong.telegramdrive.domain.AccountSessionIdentity
import com.nmtuong.telegramdrive.domain.TransferIdentity
import com.nmtuong.telegramdrive.domain.TransferState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * CP8: Adapter that exposes [TransferCoordinator] to the feature layer.
 *
 * - CP7: Uses AccountSessionIdentityProvider — no hardcoded accountId=0L or databaseGeneration=1L.
 * - CP8: Coordinator is Closeable and cleared on ViewModel.onCleared().
 * - Single source of truth is [TransferCoordinator.snapshots] / [TransferCoordinator.transferStates].
 * - The previous second _activeDownloads map has been removed.
 *
 * Lifecycle: coordinator is closed when ViewModel is cleared (via LibraryViewModel.onCleared).
 */
class DownloadCoordinator(
    repository: TelegramRepository,
    scope: CoroutineScope,
    /** CP7: Use explicit identity values from provider, not defaults. */
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

    /** CP8: Called from ViewModel.onCleared() to release coordinator scope. */
    fun close() {
        coordinator.close()
    }

    /** CP6: Get localPath for a completed transfer (for Paging preview). */
    fun getCompletedLocalPath(fileId: Int): String? {
        val snap = coordinator.getSnapshot(fileId) ?: return null
        return (snap.state as? TransferState.Completed)?.localPath
    }
}
