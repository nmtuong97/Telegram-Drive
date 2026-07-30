package com.nmtuong.telegramdrive.data

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.nmtuong.telegramdrive.domain.MediaItem
import com.nmtuong.telegramdrive.telegram.TdLibJsonGateway
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

class TdLibPagingSource(
  private val gateway: TdLibJsonGateway,
  private val chatId: Long
) : PagingSource<Long, MediaItem>() {

  override suspend fun load(params: LoadParams<Long>): LoadResult<Long, MediaItem> = withContext(Dispatchers.IO) {
    try {
      val fromMessageId = params.key ?: 0L
      val limit = params.loadSize.coerceIn(1, 100)

      val request = buildJsonObject {
        put("@type", "getChatHistory")
        put("chat_id", chatId)
        put("from_message_id", fromMessageId)
        put("offset", 0)
        put("limit", limit)
        put("only_local", false)
      }

      val response = gateway.execute(request)
      if (response.string("@type") == "error") {
        return@withContext LoadResult.Error(RuntimeException(response.string("message") ?: "Unknown error fetching history"))
      }

      val messages = response["messages"]?.jsonArray.orEmpty()
      val items = messages.mapNotNull { 
        gateway.mapMessageForTest(it.jsonObject.toString()) 
      }.distinctBy { it.fileId }

      val nextKey = if (items.isEmpty()) null else items.last().id

      LoadResult.Page(
        data = items,
        prevKey = null, // We only page forward into the past
        nextKey = nextKey
      )
    } catch (e: Exception) {
      LoadResult.Error(e)
    }
  }

  override fun getRefreshKey(state: PagingState<Long, MediaItem>): Long? {
    return null // Start from the beginning on refresh
  }

  // Helper extension to get string safely
  private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
}
