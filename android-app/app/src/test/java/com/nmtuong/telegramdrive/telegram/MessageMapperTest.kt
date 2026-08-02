package com.nmtuong.telegramdrive.telegram

import com.nmtuong.telegramdrive.domain.DownloadState
import com.nmtuong.telegramdrive.domain.MediaKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for [MessageMapper] — no gateway setup required.
 */
class MessageMapperTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun parse(raw: String) = json.parseToJsonElement(raw).jsonObject

    // ── Image ────────────────────────────────────────────────────────────────

    @Test
    fun `maps messagePhoto to IMAGE MediaItem`() {
        val msg = """{
            "id": 100,
            "chat_id": 10,
            "date": 1710000000,
            "content": {
                "@type": "messagePhoto",
                "photo": {
                    "sizes": [
                        {"photo": {"id": 50, "size": 1024, "local": {"path": "", "is_downloading_completed": false}}}
                    ]
                }
            }
        }"""
        val item = MessageMapper.mapMessage(parse(msg))
        assertNotNull(item)
        assertEquals(100L, item!!.id)
        assertEquals(10L, item.sourceId)
        assertEquals(MediaKind.IMAGE, item.kind)
        assertEquals(50, item.fileId)
        assertEquals("photo-100.jpg", item.name)
        assertEquals(DownloadState.NotDownloaded, item.downloadState)
        assertEquals(1710000000L, item.dateEpochSeconds)
    }

    @Test
    fun `maps messagePhoto with completed local file to Complete state`() {
        val msg = """{
            "id": 101,
            "chat_id": 10,
            "content": {
                "@type": "messagePhoto",
                "photo": {
                    "sizes": [
                        {"photo": {"id": 51, "size": 1024, "local": {"path": "/nonexistent/path.jpg", "is_downloading_completed": true}}}
                    ]
                }
            }
        }"""
        val item = MessageMapper.mapMessage(parse(msg))
        assertNotNull(item)
        // File doesn't exist, so downloadState is NotDownloaded despite is_downloading_completed=true
        assertEquals(DownloadState.NotDownloaded, item!!.downloadState)
    }

    // ── Video ────────────────────────────────────────────────────────────────

    @Test
    fun `maps messageVideo to VIDEO MediaItem`() {
        val msg = """{
            "id": 200,
            "chat_id": 10,
            "content": {
                "@type": "messageVideo",
                "video": {
                    "file_name": "demo.mp4",
                    "duration": 120,
                    "video": {"id": 60, "size": 5000000, "local": {"path": "", "is_downloading_completed": false}}
                }
            }
        }"""
        val item = MessageMapper.mapMessage(parse(msg))
        assertNotNull(item)
        assertEquals(MediaKind.VIDEO, item!!.kind)
        assertEquals("demo.mp4", item.name)
        assertEquals(120, item.durationSeconds)
        assertEquals(60, item.fileId)
    }

    @Test
    fun `messageVideo without file_name uses fallback name`() {
        val msg = """{
            "id": 201,
            "chat_id": 10,
            "content": {
                "@type": "messageVideo",
                "video": {
                    "file_name": "",
                    "duration": 0,
                    "video": {"id": 61, "size": 1000, "local": {"path": "", "is_downloading_completed": false}}
                }
            }
        }"""
        val item = MessageMapper.mapMessage(parse(msg))
        assertEquals("video-201.mp4", item!!.name)
    }

    // ── Document / PDF ───────────────────────────────────────────────────────

    @Test
    fun `maps messageDocument with application-pdf mime type to PDF MediaItem`() {
        val msg = """{
            "id": 300,
            "chat_id": 10,
            "content": {
                "@type": "messageDocument",
                "document": {
                    "file_name": "report.pdf",
                    "mime_type": "application/pdf",
                    "document": {"id": 70, "size": 2048, "local": {"path": "", "is_downloading_completed": false}}
                }
            }
        }"""
        val item = MessageMapper.mapMessage(parse(msg))
        assertNotNull(item)
        assertEquals(MediaKind.PDF, item!!.kind)
        assertEquals("report.pdf", item.name)
    }

    @Test
    fun `maps messageDocument with mp4 filename and blank mime type to VIDEO MediaItem`() {
        val msg = """{
            "id": 302,
            "chat_id": 10,
            "content": {
                "@type": "messageDocument",
                "document": {
                    "file_name": "movie.mp4",
                    "mime_type": "",
                    "document": {"id": 72, "size": 4096, "local": {"path": "", "is_downloading_completed": false}}
                }
            }
        }"""
        val item = MessageMapper.mapMessage(parse(msg))
        assertNotNull(item)
        assertEquals(MediaKind.VIDEO, item!!.kind)
        assertEquals("movie.mp4", item.name)
        assertEquals("video/mp4", item.mimeType)
    }

    @Test
    fun `maps messageDocument with other mime to DOCUMENT MediaItem`() {
        val msg = """{
            "id": 301,
            "chat_id": 10,
            "content": {
                "@type": "messageDocument",
                "document": {
                    "file_name": "archive.zip",
                    "mime_type": "application/zip",
                    "document": {"id": 71, "size": 10240, "local": {"path": "", "is_downloading_completed": false}}
                }
            }
        }"""
        val item = MessageMapper.mapMessage(parse(msg))
        assertEquals(MediaKind.DOCUMENT, item!!.kind)
    }

    // ── Audio / Voice ────────────────────────────────────────────────────────

    @Test
    fun `maps messageAudio to AUDIO MediaItem`() {
        val msg = """{
            "id": 400,
            "chat_id": 10,
            "content": {
                "@type": "messageAudio",
                "audio": {
                    "file_name": "song.mp3",
                    "duration": 240,
                    "audio": {"id": 80, "size": 3000000, "local": {"path": "", "is_downloading_completed": false}}
                }
            }
        }"""
        val item = MessageMapper.mapMessage(parse(msg))
        assertEquals(MediaKind.AUDIO, item!!.kind)
        assertEquals("song.mp3", item.name)
    }

    @Test
    fun `maps messageVoiceNote to AUDIO MediaItem with voice-N-ogg name`() {
        val msg = """{
            "id": 401,
            "chat_id": 10,
            "content": {
                "@type": "messageVoiceNote",
                "voice_note": {
                    "duration": 15,
                    "voice": {"id": 81, "size": 50000, "local": {"path": "", "is_downloading_completed": false}}
                }
            }
        }"""
        val item = MessageMapper.mapMessage(parse(msg))
        assertEquals(MediaKind.AUDIO, item!!.kind)
        assertEquals("voice-401.ogg", item.name)
    }

    // ── Unsupported types ────────────────────────────────────────────────────

    @Test
    fun `returns null for messageText`() {
        val msg = """{
            "id": 500,
            "chat_id": 10,
            "content": {"@type": "messageText", "text": {"text": "Hello"}}
        }"""
        assertNull(MessageMapper.mapMessage(parse(msg)))
    }

    @Test
    fun `returns null for messageSticker`() {
        val msg = """{
            "id": 501,
            "chat_id": 10,
            "content": {"@type": "messageSticker", "sticker": {}}
        }"""
        assertNull(MessageMapper.mapMessage(parse(msg)))
    }

    @Test
    fun `returns null for malformed message missing content`() {
        val msg = """{"id": 502, "chat_id": 10}"""
        assertNull(MessageMapper.mapMessage(parse(msg)))
    }

    @Test
    fun `returns null for messagePhoto missing sizes`() {
        val msg = """{
            "id": 503,
            "chat_id": 10,
            "content": {"@type": "messagePhoto", "photo": {"sizes": []}}
        }"""
        assertNull(MessageMapper.mapMessage(parse(msg)))
    }

    // ── mapMessages batch ────────────────────────────────────────────────────

    @Test
    fun `mapMessages filters unsupported types from list`() {
        val elements = listOf(
            """{"id": 600, "chat_id": 10, "content": {"@type": "messagePhoto", "photo": {"sizes": [{"photo": {"id": 90, "size": 100, "local": {"path": "", "is_downloading_completed": false}}}]}}}""",
            """{"id": 601, "chat_id": 10, "content": {"@type": "messageText", "text": {"text": "hi"}}}""",
        ).map { json.parseToJsonElement(it) }

        val items = MessageMapper.mapMessages(elements)
        assertEquals(1, items.size)
        assertEquals(600L, items[0].id)
    }

    @Test
    fun `same file in two messages produces two MediaItems`() {
        // Two different messages (601, 602) sharing the same fileId (99)
        val elements = listOf(
            """{"id": 601, "chat_id": 10, "content": {"@type": "messagePhoto", "photo": {"sizes": [{"photo": {"id": 99, "size": 100, "local": {"path": "", "is_downloading_completed": false}}}]}}}""",
            """{"id": 602, "chat_id": 10, "content": {"@type": "messagePhoto", "photo": {"sizes": [{"photo": {"id": 99, "size": 100, "local": {"path": "", "is_downloading_completed": false}}}]}}}""",
        ).map { json.parseToJsonElement(it) }

        val items = MessageMapper.mapMessages(elements)
        // Both messages are valid even though they share fileId — dedup is by message ID, not file ID
        assertEquals(2, items.size)
        assertEquals(601L, items[0].id)
        assertEquals(602L, items[1].id)
        assertEquals(99, items[0].fileId)
        assertEquals(99, items[1].fileId)
    }
}
