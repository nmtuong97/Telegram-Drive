package com.nmtuong.telegramdrive.data

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.nmtuong.telegramdrive.domain.MediaItem

/**
 * PagingSource for chat history backed by [TelegramRepository.loadHistoryPage].
 *
 * Dependency direction: Depends on [TelegramRepository] only — never on concrete TdLibJsonGateway.
 *
 * TDLib cursor semantics:
 * - Key = raw TDLib message ID (not mapped MediaItem ID).
 * - First page: key = null → fromMessageId = 0.
 * - Subsequent pages: key = rawLastMessageId from previous HistoryPage.
 * - Boundary message is deduplicated server-side via the raw cursor (TDLib excludes it when non-zero).
 * - Limit is capped at 1..100 — never exceeds 100.
 * - End of history: when HistoryPage.endOfHistory = true AND items is empty on terminal.
 *
 * Filtered empty pages:
 * Infrastructure already handles scanning through text-only raw pages.
 * If a page returns no media items but endOfHistory = false, we return an empty page
 * with a valid nextKey so Paging can continue requesting older history.
 */
class TdLibPagingSource(
    private val repository: TelegramRepository,
    private val chatId: Long,
) : PagingSource<Long, MediaItem>() {

    override suspend fun load(params: LoadParams<Long>): LoadResult<Long, MediaItem> {
        return try {
            val fromMessageId = params.key ?: 0L
            // Limit: 1..100, never exceed TDLib max
            val limit = params.loadSize.coerceIn(1, 100)

            val page = repository.loadHistoryPage(
                chatId = chatId,
                fromMessageId = fromMessageId,
                limit = limit,
            )

            if (page.error != null) {
                return LoadResult.Error(RuntimeException(page.error))
            }

            val nextKey = when {
                page.endOfHistory && page.items.isEmpty() -> null
                page.rawLastMessageId != null -> page.rawLastMessageId
                else -> null
            }

            LoadResult.Page(
                data = page.items,
                prevKey = null, // We page backwards in time (older messages), no prevKey
                nextKey = nextKey,
            )
        } catch (e: Exception) {
            LoadResult.Error(e)
        }
    }

    override fun getRefreshKey(state: PagingState<Long, MediaItem>): Long? {
        // On refresh, start from the beginning
        return null
    }
}
