package com.nmtuong.telegramdrive.telegram

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TdLibSavedMessageUpdatesTest {
  @Test
  fun buffersMessageUpdatesDuringAListenerBurstWithoutDroppingEvents() = runTest {
    val gateway = TdLibJsonGateway()
    val expected = 256
    val collected = async {
      gateway.savedMessageUpdates.take(expected).toList()
    }
    yield()

    repeat(expected) { index -> gateway.handleResponseForTest(newMessage(index.toLong())) }

    val updates = withTimeout(5_000L) { collected.await() }
    assertEquals(expected, updates.size)
    assertEquals((0L until expected.toLong()).toList(), updates.map { update ->
      check(update is com.nmtuong.telegramdrive.domain.SavedMessageUpdate.Upsert)
      update.message.id
    })
  }

  @Test
  fun accountBoundaryDrainsQueuedMessageUpdates() = runTest {
    val gateway = TdLibJsonGateway()
    gateway.handleResponseForTest(newMessage(9001L))
    gateway.handleResponseForTest("""{"@type":"authorizationStateWaitPhoneNumber"}""")

    assertNull(withTimeoutOrNull(100L) { gateway.savedMessageUpdates.first() })
  }

  private fun newMessage(id: Long): String = """
    {
      "@type": "updateNewMessage",
      "message": {
        "id": $id,
        "chat_id": 42,
        "date": 1700000000,
        "content": {
          "@type": "messageDocument",
          "document": {
            "file_name": "video-$id.mp4",
            "mime_type": "video/mp4",
            "document": {
              "id": ${id + 1000},
              "size": 1024,
              "remote": {"id": "remote-$id", "unique_id": "stable-$id"},
              "local": {"path": "", "is_downloading_completed": false}
            }
          }
        }
      }
    }
  """.trimIndent()
}
