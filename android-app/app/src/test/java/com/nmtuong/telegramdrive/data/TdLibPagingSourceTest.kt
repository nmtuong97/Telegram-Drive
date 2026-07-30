package com.nmtuong.telegramdrive.data

import androidx.paging.PagingSource
import com.nmtuong.telegramdrive.domain.*
import com.nmtuong.telegramdrive.security.TelegramApiConfiguration
import com.nmtuong.telegramdrive.telegram.*
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

class TdLibPagingSourceTest {

    private class FakeNative : TdLibNative {
        val requests = mutableListOf<String>()
        val responses = Channel<String>(Channel.UNLIMITED)
        override fun createClientId() = 1
        override fun send(clientId: Int, request: String) {
            requests.add(request)
        }
        override fun receive(timeout: Double): String? {
            return responses.tryReceive().getOrNull()
        }
    }

    @Test
    fun testPagingSourceLoadEmpty() = runTest {
        val native = FakeNative()
        val gateway = TdLibJsonGateway(
            configuration = TelegramApiConfiguration(1, "hash"),
            native = native,
            libraryLoader = object : NativeLibraryLoader { override fun load() {} },
            dispatcher = coroutineContext[kotlin.coroutines.ContinuationInterceptor] as kotlinx.coroutines.CoroutineDispatcher
        )
        gateway.start()
        
        val source = TdLibPagingSource(gateway, 123L)
        
        val deferredResult = async {
            source.load(PagingSource.LoadParams.Refresh(null, 10, false))
        }

        yield() // Allow gateway to send request

        val requestStr = native.requests.lastOrNull { it.contains("getChatHistory") }
        assertNotNull(requestStr)
        val requestObj = Json.parseToJsonElement(requestStr!!).jsonObject
        val extra = requestObj["@extra"]!!.jsonPrimitive.content

        native.responses.trySend("""{"@type": "messages", "total_count": 0, "messages": [], "@extra": "$extra"}""")

        val result = deferredResult.await()
        assertTrue(result is PagingSource.LoadResult.Page)
        val page = result as PagingSource.LoadResult.Page
        assertTrue(page.data.isEmpty())
        assertNull(page.nextKey)
    }

    @Test
    fun testPagingSourceLoadMultiPage() = runTest {
        val native = FakeNative()
        val gateway = TdLibJsonGateway(
            configuration = TelegramApiConfiguration(1, "hash"),
            native = native,
            libraryLoader = object : NativeLibraryLoader { override fun load() {} },
            dispatcher = coroutineContext[kotlin.coroutines.ContinuationInterceptor] as kotlinx.coroutines.CoroutineDispatcher
        )
        gateway.start()

        val source = TdLibPagingSource(gateway, 123L)

        // First page
        val deferredResult1 = async {
            source.load(PagingSource.LoadParams.Refresh(null, 10, false))
        }
        yield()
        var requestStr = native.requests.last { it.contains("getChatHistory") }
        var requestObj = Json.parseToJsonElement(requestStr).jsonObject
        assertEquals(10, requestObj["limit"]!!.jsonPrimitive.int)
        assertEquals(0L, requestObj["from_message_id"]!!.jsonPrimitive.long)
        var extra = requestObj["@extra"]!!.jsonPrimitive.content

        val messageJson = """
        {
            "id": 1000,
            "chat_id": 123,
            "content": {
                "@type": "messageDocument",
                "document": {
                    "file_name": "test.pdf",
                    "mime_type": "application/pdf",
                    "document": {
                        "id": 1,
                        "size": 1024,
                        "local": { "is_downloading_completed": false }
                    }
                }
            }
        }
        """.trimIndent()
        native.responses.trySend("""{"@type": "messages", "total_count": 1, "messages": [$messageJson], "@extra": "$extra"}""")

        val result1 = deferredResult1.await()
        assertTrue(result1 is PagingSource.LoadResult.Page)
        val page1 = result1 as PagingSource.LoadResult.Page
        assertEquals(1, page1.data.size)
        assertEquals(1000L, page1.nextKey)

        // Second page
        val deferredResult2 = async {
            source.load(PagingSource.LoadParams.Append(1000L, 10, false))
        }
        yield()
        requestStr = native.requests.last { it.contains("getChatHistory") }
        requestObj = Json.parseToJsonElement(requestStr).jsonObject
        
        // limit = loadSize + 1
        assertEquals(11, requestObj["limit"]!!.jsonPrimitive.int)
        assertEquals(1000L, requestObj["from_message_id"]!!.jsonPrimitive.long)
        extra = requestObj["@extra"]!!.jsonPrimitive.content

        native.responses.trySend("""{"@type": "messages", "total_count": 1, "messages": [$messageJson], "@extra": "$extra"}""")

        val result2 = deferredResult2.await()
        assertTrue(result2 is PagingSource.LoadResult.Page)
        val page2 = result2 as PagingSource.LoadResult.Page
        // The duplicate message should be filtered out
        assertTrue(page2.data.isEmpty())
        assertNull(page2.nextKey)
    }
}
