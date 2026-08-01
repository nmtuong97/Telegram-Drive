package com.nmtuong.telegramdrive.data

import com.nmtuong.telegramdrive.data.fake.FakeTelegramCatalog
import com.nmtuong.telegramdrive.domain.*
import com.nmtuong.telegramdrive.telegram.TdLibGateway
import java.io.File
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class RealTelegramRepository(private val gateway: TdLibGateway) : TelegramRepository {
    override val diagnostics = gateway.state
    override val authorization = gateway.authorization
    override val library = gateway.library
    override val resetProgress = gateway.resetProgress
    override val transferUpdates = gateway.transferUpdates
    override fun start() = gateway.start()
    override fun submit(action: AuthorizationAction) = gateway.submit(action)
    override suspend fun logoutAndReset(): AccountResetResult = gateway.logoutAndReset()
    override fun loadSavedMessages(limit: Int) = gateway.loadSavedMessages(limit)
    override fun download(request: TransferRequest) = gateway.download(request)
    override fun download(fileId: Int) = gateway.download(fileId)
    override fun downloadPagingItem(fileId: Int) = gateway.downloadPagingItem(fileId)
    override fun cancel(identity: TransferIdentity) = gateway.cancel(identity)
    override fun cancelDownload(fileId: Int) = gateway.cancelDownload(fileId)
    override fun previewPagingItem(
        itemId: Long,
        mediaKind: MediaKind,
        localPath: String,
    ): PreviewTarget? {
        val file = File(localPath)
        if (!file.isFile) return null
        return when (mediaKind) {
            MediaKind.IMAGE -> PreviewTarget.Image(itemId, localPath)
            MediaKind.VIDEO -> PreviewTarget.Video(itemId, localPath)
            else -> null
        }
    }
    override fun preview(itemId: Long) = gateway.preview(itemId)
    override suspend fun getSavedMessagesChatId(): Long? = gateway.getSavedMessagesChatId()
    override suspend fun getAvailableSources(): List<FileSource> = gateway.getAvailableSources()
    override suspend fun loadHistoryPage(chatId: Long, fromMessageId: Long, limit: Int): HistoryPage =
        gateway.loadHistoryPage(chatId, fromMessageId, limit)
    override fun getChatHistoryPagingSource(chatId: Long): androidx.paging.PagingSource<Long, MediaItem> =
        TdLibPagingSource(this, chatId)
    override fun close() = gateway.close()
}

class FakeTelegramRepository(
    val catalog: FakeTelegramCatalog,
    private val filesDirectory: File = File(System.getProperty("java.io.tmpdir"), "telegram-drive-fake"),
    private val videoBytes: () -> ByteArray = { ByteArray(0) },
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val downloadStepDelayMillis: Long = 150,
    val identityProvider: AccountSessionIdentityProvider = AccountSessionIdentityProvider(),
) : TelegramRepository {
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val downloadJobs = mutableMapOf<Int, Job>()
    private val downloadGenerations = mutableMapOf<Int, Long>()
    private val mutableDiagnostics = MutableStateFlow(
        DiagnosticsState(
            dataSource = DataSourceMode.FAKE,
            lifecycle = GatewayLifecycle.RUNNING,
            authorizationState = AuthorizationState.WaitingForPhoneNumber,
        ),
    )
    override val diagnostics: StateFlow<DiagnosticsState> = mutableDiagnostics
    private val mutableAuthorization = MutableStateFlow(AuthorizationSession(AuthorizationState.WaitingForPhoneNumber))
    override val authorization: StateFlow<AuthorizationSession> = mutableAuthorization
    private val mutableLibrary = MutableStateFlow<LibraryState>(LibraryState.Idle)
    override val library: StateFlow<LibraryState> = mutableLibrary
    private val mutableResetProgress = MutableStateFlow<ResetProgress>(ResetProgress.Idle)
    override val resetProgress: StateFlow<ResetProgress> = mutableResetProgress.asStateFlow()
    private val mutableTransferUpdates = kotlinx.coroutines.flow.MutableSharedFlow<TransferUpdate>(extraBufferCapacity = 64)
    override val transferUpdates: kotlinx.coroutines.flow.Flow<TransferUpdate> = mutableTransferUpdates.asSharedFlow()

    private fun currentIdentityFor(fileId: Int): TransferIdentity {
        val accId = identityProvider.accountId ?: catalog.account.id
        val gen = identityProvider.databaseGeneration ?: 1L
        return TransferIdentity(accId, gen, fileId)
    }

    override fun start() = Unit

    override fun submit(action: AuthorizationAction): ActionResult {
        val next = when (action) {
            is AuthorizationAction.SubmitPhone -> if (authorization.value.state == AuthorizationState.WaitingForPhoneNumber) AuthorizationState.WaitingForCode else null
            is AuthorizationAction.SubmitCode -> if (authorization.value.state == AuthorizationState.WaitingForCode) AuthorizationState.WaitingForPassword("fake 2FA") else null
            is AuthorizationAction.SubmitPassword -> if (authorization.value.state is AuthorizationState.WaitingForPassword) AuthorizationState.Ready else null
            is AuthorizationAction.SubmitEmailAddress -> if (authorization.value.state == AuthorizationState.WaitingForEmailAddress) AuthorizationState.WaitingForEmailCode else null
            is AuthorizationAction.SubmitEmailCode -> if (authorization.value.state == AuthorizationState.WaitingForEmailCode) AuthorizationState.Ready else null
            AuthorizationAction.Logout -> if (authorization.value.state == AuthorizationState.Ready) AuthorizationState.Closed else null
            AuthorizationAction.Reset -> AuthorizationState.Closed
        } ?: return ActionResult.INVALID_STATE
        mutableAuthorization.value = AuthorizationSession(next)
        mutableDiagnostics.value = mutableDiagnostics.value.copy(authorizationState = next)
        if (next == AuthorizationState.Ready) {
            identityProvider.updateAccount(catalog.account.id)
        } else {
            cancelDownloadsAndClearFiles()
            mutableLibrary.value = LibraryState.Idle
        }
        return ActionResult.ACCEPTED
    }

    override suspend fun logoutAndReset(): AccountResetResult {
        // CP6: Atomic Reset state machine for Fake Repository
        mutableResetProgress.value = ResetProgress.BlockingTransfers
        mutableResetProgress.value = ResetProgress.CancellingTransfers
        cancelDownloadsAndClearFiles()
        mutableResetProgress.value = ResetProgress.InvalidatingGeneration
        identityProvider.invalidateGeneration()
        mutableResetProgress.value = ResetProgress.LoggingOut
        mutableResetProgress.value = ResetProgress.WaitingForClosed
        mutableAuthorization.value = AuthorizationSession(AuthorizationState.Closed)
        mutableDiagnostics.value = mutableDiagnostics.value.copy(authorizationState = AuthorizationState.Closed)
        mutableLibrary.value = LibraryState.Idle
        mutableResetProgress.value = ResetProgress.DeletingFiles
        mutableResetProgress.value = ResetProgress.ClearingIdentity
        identityProvider.clear()
        mutableResetProgress.value = ResetProgress.Completed
        return AccountResetResult.Completed
    }

    override fun loadSavedMessages(limit: Int): ActionResult {
        if (authorization.value.state != AuthorizationState.Ready) return ActionResult.INVALID_STATE
        val supportedKinds = setOf(MediaKind.IMAGE, MediaKind.VIDEO, MediaKind.ANIMATION, MediaKind.DOCUMENT)
        val items = catalog.media.filter {
            it.sourceId == catalog.sources.first { source -> source.savedMessages }.id && it.kind in supportedKinds
        }
            .distinctBy { it.fileId }
            .take(limit.coerceIn(1, 50))
            .map { it.copy(downloadState = DownloadState.NotDownloaded, localPath = null) }
        mutableLibrary.value = if (items.isEmpty()) LibraryState.Empty else LibraryState.Content(items)
        return ActionResult.ACCEPTED
    }

    override fun download(request: TransferRequest): ActionResult {
        return downloadInternal(request.fileId, request.identity)
    }

    override fun download(fileId: Int): ActionResult {
        return downloadInternal(fileId, currentIdentityFor(fileId))
    }

    override fun downloadPagingItem(fileId: Int): ActionResult {
        return downloadInternal(fileId, currentIdentityFor(fileId))
    }

    private fun downloadInternal(fileId: Int, identity: TransferIdentity): ActionResult {
        if (resetProgress.value != ResetProgress.Idle) return ActionResult.INVALID_STATE
        val item = (library.value as? LibraryState.Content)?.items?.firstOrNull { it.fileId == fileId }
            ?: catalog.media.firstOrNull { it.fileId == fileId }
            ?: return ActionResult.INVALID_STATE
        if (item.downloadState is DownloadState.Downloading) return ActionResult.DUPLICATE
        if (item.downloadState == DownloadState.Complete && item.localPath?.let(::File)?.isFile == true) return ActionResult.DUPLICATE
        val generation = synchronized(lock) {
            if (downloadJobs[fileId]?.isActive == true) return ActionResult.DUPLICATE
            (downloadGenerations[fileId] ?: 0L) + 1L
        }
        synchronized(lock) { downloadGenerations[fileId] = generation }

        updateItem(fileId) { it.copy(downloadState = DownloadState.Downloading(0), localPath = null) }
        mutableTransferUpdates.tryEmit(TransferUpdate(identity, TransferState.InProgress(0)))

        // CP5 Atomic: CoroutineStart.LAZY before adding to downloadJobs
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                delay(downloadStepDelayMillis)
                if (!isCurrentDownload(fileId, generation)) return@launch
                updateItem(fileId) { it.copy(downloadState = DownloadState.Downloading(50)) }
                mutableTransferUpdates.tryEmit(TransferUpdate(identity, TransferState.InProgress(50)))
                delay(downloadStepDelayMillis)
                if (!isCurrentDownload(fileId, generation)) return@launch
                if (item.kind == MediaKind.ANIMATION) {
                    updateItem(fileId) { it.copy(downloadState = DownloadState.Failed("Simulated download failure"), localPath = null) }
                    mutableTransferUpdates.tryEmit(TransferUpdate(identity, TransferState.TransferFailed("Simulated download failure")))
                } else {
                    filesDirectory.mkdirs()
                    val target = File(filesDirectory, item.name)
                    when (item.kind) {
                        MediaKind.IMAGE -> target.writeBytes(FAKE_PNG)
                        MediaKind.VIDEO -> target.writeBytes(videoBytes())
                        else -> target.writeBytes(ByteArray(0))
                    }
                    if (isCurrentDownload(fileId, generation)) {
                        updateItem(fileId) { it.copy(downloadState = DownloadState.Complete, localPath = target.absolutePath) }
                        mutableTransferUpdates.tryEmit(TransferUpdate(identity, TransferState.Completed(target.absolutePath), 100, target.absolutePath))
                    }
                }
            } catch (e: CancellationException) {
                mutableTransferUpdates.tryEmit(TransferUpdate(identity, TransferState.TransferCancelled))
            } finally {
                synchronized(lock) { downloadJobs.remove(fileId) }
            }
        }
        synchronized(lock) { downloadJobs[fileId] = job }
        job.start()
        return ActionResult.ACCEPTED
    }

    override fun cancel(identity: TransferIdentity): ActionResult {
        return cancelDownloadInternal(identity.fileId, identity)
    }

    override fun cancelDownload(fileId: Int): ActionResult {
        return cancelDownloadInternal(fileId, currentIdentityFor(fileId))
    }

    private fun cancelDownloadInternal(fileId: Int, identity: TransferIdentity): ActionResult {
        val item = (library.value as? LibraryState.Content)?.items?.firstOrNull { it.fileId == fileId }
            ?: catalog.media.firstOrNull { it.fileId == fileId }
            ?: return ActionResult.INVALID_STATE
        val isActive = synchronized(lock) { downloadJobs[fileId]?.isActive == true }
        if (!isActive && item.downloadState !is DownloadState.Downloading) return ActionResult.INVALID_STATE

        synchronized(lock) {
            downloadGenerations[fileId] = (downloadGenerations[fileId] ?: 0L) + 1L
            downloadJobs.remove(fileId)?.cancel()
        }
        mutableTransferUpdates.tryEmit(TransferUpdate(identity, TransferState.TransferCancelled))
        updateItem(fileId) { it.copy(downloadState = DownloadState.Canceled, localPath = null) }
        return ActionResult.ACCEPTED
    }

    override fun previewPagingItem(
        itemId: Long,
        mediaKind: MediaKind,
        localPath: String,
    ): PreviewTarget? {
        val file = File(localPath)
        if (!file.isFile) return null
        return when (mediaKind) {
            MediaKind.IMAGE -> PreviewTarget.Image(itemId, localPath)
            MediaKind.VIDEO -> PreviewTarget.Video(itemId, localPath)
            else -> null
        }
    }

    override fun preview(itemId: Long): PreviewTarget? {
        val item = (library.value as? LibraryState.Content)?.items?.firstOrNull { it.id == itemId } ?: return null
        val path = item.localPath?.takeIf { File(it).isFile } ?: return null
        return when (item.kind) {
            MediaKind.IMAGE -> PreviewTarget.Image(item.id, path)
            MediaKind.VIDEO -> PreviewTarget.Video(item.id, path)
            else -> null
        }
    }

    override suspend fun getSavedMessagesChatId(): Long? {
        return catalog.sources.firstOrNull { it.savedMessages }?.id
    }

    override suspend fun getAvailableSources(): List<FileSource> = catalog.sources

    override suspend fun loadHistoryPage(chatId: Long, fromMessageId: Long, limit: Int): HistoryPage {
        val safeLimit = limit.coerceIn(1, 100)
        var cursor = fromMessageId
        var scanCount = 0

        val rawList = if (catalog.rawMessages.isNotEmpty()) {
            catalog.rawMessages.filter { it.sourceId == chatId }
        } else {
            catalog.media.filter { it.sourceId == chatId }.map { com.nmtuong.telegramdrive.data.fake.FakeRawMessage(it.id, it.sourceId, mediaItem = it) }
        }

        val allSorted = rawList.sortedByDescending { it.id }

        while (scanCount < 10) {
            val startIndex = if (cursor == 0L) {
                0
            } else {
                val idx = allSorted.indexOfFirst { it.id == cursor }
                if (idx < 0) return HistoryPage.error("Invalid cursor")
                idx + 1
            }

            if (startIndex >= allSorted.size) {
                return HistoryPage.empty()
            }

            val rawPage = allSorted.drop(startIndex).take(safeLimit)
            val endOfHistory = (startIndex + rawPage.size) >= allSorted.size
            val rawLastMessageId = rawPage.lastOrNull()?.id

            val supportedKinds = setOf(MediaKind.IMAGE, MediaKind.VIDEO, MediaKind.ANIMATION, MediaKind.DOCUMENT)
            val mappedItems = rawPage.mapNotNull { rawMsg ->
                rawMsg.mediaItem?.takeIf { it.kind in supportedKinds }?.copy(downloadState = DownloadState.NotDownloaded, localPath = null)
            }

            if (mappedItems.isNotEmpty()) {
                return HistoryPage(
                    items = mappedItems,
                    rawLastMessageId = rawLastMessageId,
                    endOfHistory = endOfHistory,
                )
            }

            if (endOfHistory || rawLastMessageId == null || rawLastMessageId == cursor) {
                return HistoryPage.empty()
            }

            cursor = rawLastMessageId
            scanCount++
        }

        return HistoryPage(
            items = emptyList(),
            rawLastMessageId = cursor.takeIf { it != fromMessageId },
            endOfHistory = false,
        )
    }

    override fun getChatHistoryPagingSource(chatId: Long): androidx.paging.PagingSource<Long, MediaItem> {
        return TdLibPagingSource(this, chatId)
    }

    override fun close() {
        synchronized(lock) { downloadJobs.values.forEach(Job::cancel); downloadJobs.clear() }
        scope.cancel()
    }

    private fun isCurrentDownload(fileId: Int, generation: Long): Boolean =
        synchronized(lock) { downloadGenerations[fileId] == generation }

    private fun updateItem(fileId: Int, transform: (MediaItem) -> MediaItem) {
        synchronized(lock) {
            val content = mutableLibrary.value as? LibraryState.Content ?: return
            mutableLibrary.value = LibraryState.Content(content.items.map { if (it.fileId == fileId) transform(it) else it })
        }
    }

    private fun cancelDownloadsAndClearFiles() {
        synchronized(lock) {
            downloadJobs.values.forEach(Job::cancel)
            downloadJobs.clear()
            downloadGenerations.replaceAll { _, generation -> generation + 1L }
        }
        filesDirectory.deleteRecursively()
    }

    private companion object {
        val FAKE_PNG: ByteArray = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=",
        )
    }
}
