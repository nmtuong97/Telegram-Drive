package com.nmtuong.telegramdrive.data

import com.nmtuong.telegramdrive.domain.*
import java.io.Closeable
import kotlinx.coroutines.flow.StateFlow
import androidx.paging.PagingSource

/**
 * Repository contract for the application layer.
 *
 * Dependency direction:
 * - Repository may import Paging (data layer).
 * - Repository does NOT expose TdLib types.
 * - ViewModel/UI consume this interface only.
 *
 * CP3: downloadPagingItem(fileId) is kept for backward compatibility with
 * coordinator internals; external callers should prefer downloadPagingItem(TransferRequest).
 */
interface TelegramRepository : Closeable {
    val diagnostics: StateFlow<DiagnosticsState>
    val authorization: StateFlow<AuthorizationSession>
    val library: StateFlow<LibraryState>
    val resetProgress: StateFlow<ResetProgress>
    val transferUpdates: kotlinx.coroutines.flow.Flow<TransferUpdate>
    fun start()
    fun submit(action: AuthorizationAction): ActionResult
    suspend fun logoutAndReset(): AccountResetResult
    fun loadSavedMessages(limit: Int = 50): ActionResult
    fun download(request: TransferRequest): ActionResult
    fun download(fileId: Int): ActionResult
    fun downloadPagingItem(fileId: Int): ActionResult
    fun cancel(identity: TransferIdentity): ActionResult
    fun cancelDownload(fileId: Int): ActionResult

    /**
     * CP6: Preview from Paging item snapshot + TransferCoordinator localPath.
     * Does not look up item in legacy LibraryState.
     */
    fun previewPagingItem(
        itemId: Long,
        mediaKind: MediaKind,
        localPath: String,
    ): PreviewTarget?

    /** Legacy preview — looks up item in LibraryState.Content. Used by P1 compat. */
    fun preview(itemId: Long): PreviewTarget?

    suspend fun getSavedMessagesChatId(): Long?
    suspend fun getAvailableSources(): List<FileSource>

    /**
     * Load a bounded page of chat history.
     * Delegates to infrastructure [com.nmtuong.telegramdrive.domain.HistoryPage] semantics.
     * Used by PagingSource implementations in this data layer.
     */
    suspend fun loadHistoryPage(chatId: Long, fromMessageId: Long, limit: Int): HistoryPage

    /**
     * Returns a PagingSource for chat history.
     * Owned by Repository — infrastructure never creates PagingSource.
     */
    fun getChatHistoryPagingSource(chatId: Long): PagingSource<Long, MediaItem>
}
