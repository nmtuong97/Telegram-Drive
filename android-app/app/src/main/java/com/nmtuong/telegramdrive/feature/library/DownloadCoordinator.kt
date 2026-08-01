package com.nmtuong.telegramdrive.feature.library

import com.nmtuong.telegramdrive.data.AccountSessionIdentityProvider
import com.nmtuong.telegramdrive.data.TelegramRepository
import com.nmtuong.telegramdrive.data.TransferCoordinator
import com.nmtuong.telegramdrive.domain.MediaKind
import com.nmtuong.telegramdrive.domain.TransferIdentity
import com.nmtuong.telegramdrive.domain.TransferRequest
import com.nmtuong.telegramdrive.domain.TransferState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

import java.io.Closeable
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Adapter exposing [TransferCoordinator] to the feature layer.
 *
 * Checkpoint 2 & 4: Uses dynamic AccountSessionIdentity or primitive parameters.
 */
class DownloadCoordinator(
    private val repository: TelegramRepository,
    scope: CoroutineScope,
    private val accountId: Long = 0L,
    private val databaseGeneration: Long = 1L,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val activeGenerationProvider: () -> Long = { databaseGeneration },
) : Closeable {
    private val coordinator = TransferCoordinator(
        repository = repository,
        accountId = accountId,
        databaseGeneration = databaseGeneration,
        dispatcher = dispatcher,
        activeGenerationProvider = activeGenerationProvider,
    )

    /** Single source of truth: fileId → TransferState */
    val transferStates: StateFlow<Map<Int, TransferState>> = coordinator.transferStates

    fun startDownload(request: TransferRequest) {
        coordinator.startTransfer(request)
    }

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

    fun cancelDownload(identity: TransferIdentity) {
        coordinator.cancelTransfer(identity)
    }

    fun cancelDownload(fileId: Int) {
        coordinator.cancelTransfer(fileId)
    }

    fun clear() {
        coordinator.clear()
    }

    override fun close() {
        coordinator.close()
    }

    fun getCompletedLocalPath(fileId: Int): String? {
        val snap = coordinator.getSnapshot(fileId) ?: return null
        return (snap.state as? TransferState.Completed)?.localPath
    }

    fun getCompletedLocalPath(identity: TransferIdentity): String? {
        val snap = coordinator.getSnapshot(identity) ?: return null
        return (snap.state as? TransferState.Completed)?.localPath
    }
}
