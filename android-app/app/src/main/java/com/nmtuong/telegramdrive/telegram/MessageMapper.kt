package com.nmtuong.telegramdrive.telegram

import com.nmtuong.telegramdrive.domain.DownloadState
import com.nmtuong.telegramdrive.domain.MediaItem
import com.nmtuong.telegramdrive.domain.MediaKind
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File

/**
 * Maps TDLib JSON message objects to domain [MediaItem].
 * Extracted from TdLibJsonGateway for independent testability.
 * Contains no side effects — pure mapping logic.
 */
object MessageMapper {

    /**
     * Maps a TDLib message [JsonObject] to a [MediaItem].
     * Returns null for unsupported content types (text, sticker, etc.).
     */
    fun mapMessage(message: JsonObject): MediaItem? {
        val content = message.obj("content") ?: return null
        val messageId = message.long("id")
        val chatId = message.long("chat_id")
        val type = content.string("@type")
        val media: JsonObject
        val file: JsonObject
        val kind: MediaKind
        val name: String
        val duration: Int
        when (type) {
            "messagePhoto" -> {
                media = content.obj("photo") ?: return null
                val size = media["sizes"]?.jsonArray?.lastOrNull()?.jsonObject ?: return null
                file = size.obj("photo") ?: return null
                kind = MediaKind.IMAGE; name = "photo-$messageId.jpg"; duration = 0
            }
            "messageVideo" -> {
                media = content.obj("video") ?: return null; file = media.obj("video") ?: return null
                kind = MediaKind.VIDEO
                name = media.string("file_name").orEmpty().ifBlank { "video-$messageId.mp4" }
                duration = media.int("duration")
            }
            "messageAnimation" -> {
                media = content.obj("animation") ?: return null; file = media.obj("animation") ?: return null
                kind = MediaKind.ANIMATION
                name = media.string("file_name").orEmpty().ifBlank { "animation-$messageId" }
                duration = media.int("duration")
            }
            "messageAudio" -> {
                media = content.obj("audio") ?: return null; file = media.obj("audio") ?: return null
                kind = MediaKind.AUDIO
                name = media.string("file_name").orEmpty().ifBlank { "audio-$messageId.mp3" }
                duration = media.int("duration")
            }
            "messageVoiceNote" -> {
                media = content.obj("voice_note") ?: return null; file = media.obj("voice") ?: return null
                kind = MediaKind.AUDIO; name = "voice-$messageId.ogg"; duration = media.int("duration")
            }
            "messageDocument" -> {
                media = content.obj("document") ?: return null; file = media.obj("document") ?: return null
                val mimeType = media.string("mime_type").orEmpty()
                kind = if (mimeType == "application/pdf") MediaKind.PDF else MediaKind.DOCUMENT
                name = media.string("file_name").orEmpty().ifBlank { "document-$messageId" }; duration = 0
            }
            else -> return null
        }
        val local = file.obj("local")
        val path: String? = local?.string("path")?.takeIf { it.isNotBlank() }
        val complete = local?.bool("is_downloading_completed") == true && path != null && File(path).isFile
        return MediaItem(
            id = messageId,
            sourceId = chatId,
            name = name,
            kind = kind,
            downloadState = if (complete) DownloadState.Complete else DownloadState.NotDownloaded,
            fileId = file.int("id"),
            sizeBytes = file.long("size"),
            durationSeconds = duration,
            localPath = path.takeIf { complete },
        )
    }

    /**
     * Maps a list of raw TDLib message [JsonElement]s to [MediaItem]s.
     * Unsupported content types are silently dropped.
     */
    fun mapMessages(elements: List<JsonElement>): List<MediaItem> =
        elements.mapNotNull { mapMessage(it.jsonObject) }
}

internal fun JsonObject.string(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull
internal fun JsonObject.int(name: String): Int = this[name]?.jsonPrimitive?.intOrNull ?: 0
internal fun JsonObject.long(name: String): Long = this[name]?.jsonPrimitive?.longOrNull ?: 0L
internal fun JsonObject.bool(name: String): Boolean = this[name]?.jsonPrimitive?.booleanOrNull == true
internal fun JsonObject.obj(name: String): JsonObject? = this[name] as? JsonObject
