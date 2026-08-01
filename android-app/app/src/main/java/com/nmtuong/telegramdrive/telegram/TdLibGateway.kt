package com.nmtuong.telegramdrive.telegram

import com.nmtuong.telegramdrive.domain.*
import java.io.Closeable
import kotlinx.coroutines.flow.StateFlow

/**
 * Infrastructure-level gateway to TDLib.
 *
 * Dependency direction: Infrastructure never imports data-layer types (PagingSource etc.).
 * PagingSource is created by the Repository layer, not here.
 */
interface TdLibGateway : Closeable {
    val state: StateFlow<DiagnosticsState>
    val authorization: StateFlow<AuthorizationSession>
    val library: StateFlow<LibraryState>
    val transferUpdates: kotlinx.coroutines.flow.Flow<TransferUpdate>
    fun start()
    fun submit(action: AuthorizationAction): ActionResult
    suspend fun logoutAndReset(): AccountResetResult
    fun loadSavedMessages(limit: Int): ActionResult
    fun download(request: TransferRequest): ActionResult
    fun download(fileId: Int): ActionResult
    fun downloadPagingItem(fileId: Int): ActionResult
    fun cancel(identity: TransferIdentity): ActionResult
    fun cancelDownload(fileId: Int): ActionResult

    fun preview(itemId: Long): PreviewTarget?
    suspend fun getSavedMessagesChatId(): Long?
    suspend fun getAvailableSources(): List<FileSource>

    /**
     * Load a bounded page of chat history.
     *
     * Contract:
     * - [fromMessageId] = 0 for first page.
     * - [limit] must be in 1..100.
     * - Cursor for next page is [HistoryPage.rawLastMessageId] (raw TDLib ID, not mapped item ID).
     * - [HistoryPage.endOfHistory] = true when TDLib has no more older messages.
     * - Filters unsupported content types but scans forward if a page has no media.
     * - Returns [HistoryPage.error] on network/TDLib error.
     *
     * Infrastructure does NOT create PagingSource. Repository owns that.
     */
    suspend fun loadHistoryPage(chatId: Long, fromMessageId: Long, limit: Int): HistoryPage
}
