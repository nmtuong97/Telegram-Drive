package com.nmtuong.telegramdrive.telegram

import com.nmtuong.telegramdrive.domain.MediaKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class PhaseThreeMessageMappingTest {
  private val json = Json { ignoreUnknownKeys = true }

  @Test
  fun preservesCaptionDimensionsAndStableRemoteFileIdentity() {
    val message = json.parseToJsonElement(
      """
      {
        "id": 9001,
        "chat_id": 42,
        "date": 1700000000,
        "content": {
          "@type": "messagePhoto",
          "caption": {"text": "trip"},
          "photo": {
            "minithumbnail": {"width": 2, "height": 2, "data": "AQID"},
            "sizes": [{
              "width": 1200,
              "height": 800,
              "photo": {
                "id": 71,
                "size": 128,
                "remote": {"id": "remote-71", "unique_id": "stable-71"},
                "local": {"path": "", "is_downloading_completed": false}
              }
            }]
          }
        }
      }
      """.trimIndent(),
    ).jsonObject

    val item = MessageMapper.mapMessage(message)
    assertNotNull(item)
    assertEquals(MediaKind.IMAGE, item!!.kind)
    assertEquals("trip", item.caption)
    assertEquals(1200, item.width)
    assertEquals(800, item.height)
    assertEquals("remote-unique:stable-71", item.stableFileIdentity)
    assertEquals("AQID", item.minithumbnailData)
    assertNotEquals("tdlib:71", item.stableFileIdentity)
  }
}
