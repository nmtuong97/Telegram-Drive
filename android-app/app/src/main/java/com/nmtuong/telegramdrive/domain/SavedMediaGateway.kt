package com.nmtuong.telegramdrive.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Domain-facing source contract for the Room-backed Saved Messages index.
 * Implementations may be TDLib-backed or deterministic fake data; neither leaks into UI.
 */
interface SavedMediaGateway {
  val savedMessageUpdates: Flow<SavedMessageUpdate>
    get() = emptyFlow()
  val fileUpdates: Flow<TdLibFileSnapshot>
    get() = emptyFlow()

  suspend fun getSavedMessagesChatId(): Long?

  /** Returns the newest raw Telegram message ID without filtering by media type. */
  suspend fun getSavedMessagesHead(chatId: Long): Long? =
    loadHistoryPage(chatId, fromMessageId = 0L, limit = 1).rawLastMessageId

  suspend fun loadHistoryPage(chatId: Long, fromMessageId: Long, limit: Int): HistoryPage

  suspend fun getFileSnapshot(fileId: Int): TdLibFileSnapshot? = null

  fun requestFileRange(fileId: Int, offsetBytes: Long, limitBytes: Long, priority: Int = 16): ActionResult =
    ActionResult.INVALID_STATE

  fun cancelFileRange(fileId: Int): ActionResult = ActionResult.INVALID_STATE

  fun deleteTemporaryFile(fileId: Int): ActionResult = ActionResult.INVALID_STATE
}
