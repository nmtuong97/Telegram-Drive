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
        var mimeType: String? = null
        when (type) {
            "messagePhoto" -> {
                media = content.obj("photo") ?: return null
                val size = media["sizes"]?.jsonArray?.lastOrNull()?.jsonObject ?: return null
                file = size.obj("photo") ?: return null
                kind = MediaKind.IMAGE; name = "photo-$messageId.jpg"; duration = 0; mimeType = "image/jpeg"
            }
            "messageVideo" -> {
                media = content.obj("video") ?: return null; file = media.obj("video") ?: return null
                kind = MediaKind.VIDEO
                name = media.string("file_name").orEmpty().ifBlank { "video-$messageId.mp4" }
                duration = media.int("duration")
                mimeType = media.string("mime_type")
            }
            "messageAnimation" -> {
                media = content.obj("animation") ?: return null; file = media.obj("animation") ?: return null
                kind = MediaKind.ANIMATION
                name = media.string("file_name").orEmpty().ifBlank { "animation-$messageId" }
                duration = media.int("duration")
                mimeType = media.string("mime_type")
            }
            "messageAudio" -> {
                media = content.obj("audio") ?: return null; file = media.obj("audio") ?: return null
                kind = MediaKind.AUDIO
                name = media.string("file_name").orEmpty().ifBlank { "audio-$messageId.mp3" }
                duration = media.int("duration")
                mimeType = media.string("mime_type")
            }
            "messageVoiceNote" -> {
                media = content.obj("voice_note") ?: return null; file = media.obj("voice") ?: return null
                kind = MediaKind.AUDIO; name = "voice-$messageId.ogg"; duration = media.int("duration"); mimeType = "audio/ogg"
            }
            "messageDocument" -> {
                media = content.obj("document") ?: return null; file = media.obj("document") ?: return null
                val documentName = media.string("file_name").orEmpty().ifBlank { "document-$messageId" }
                val documentMimeType = media.string("mime_type").orEmpty()
                kind = mediaKindForDocument(documentMimeType, documentName)
                name = documentName; duration = 0
                mimeType = documentMimeType.ifBlank { mimeTypeForDocumentName(documentName) }
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
            mimeType = mimeType,
            dateEpochSeconds = message.long("date"),
        )
    }

    /**
     * Maps a list of raw TDLib message [JsonElement]s to [MediaItem]s.
     * Unsupported content types are silently dropped.
     */
    fun mapMessages(elements: List<JsonElement>): List<MediaItem> =
        elements.mapNotNull { mapMessage(it.jsonObject) }
}

private fun mediaKindForDocument(mimeType: String, name: String): MediaKind {
    val extension = name.substringAfterLast('.', "").lowercase()
    val normalizedMimeType = mimeType.trim().lowercase()
    return when {
        normalizedMimeType == "application/pdf" || extension == "pdf" -> MediaKind.PDF
        normalizedMimeType.startsWith("video/") || extension in DOCUMENT_VIDEO_EXTENSIONS -> MediaKind.VIDEO
        normalizedMimeType.startsWith("audio/") || extension in DOCUMENT_AUDIO_EXTENSIONS -> MediaKind.AUDIO
        normalizedMimeType == "image/gif" || extension == "gif" -> MediaKind.ANIMATION
        else -> MediaKind.DOCUMENT
    }
}

private fun mimeTypeForDocumentName(name: String): String? = when (name.substringAfterLast('.', "").lowercase()) {
    "pdf" -> "application/pdf"
    "mp4" -> "video/mp4"
    "mkv" -> "video/x-matroska"
    "mov" -> "video/quicktime"
    "webm" -> "video/webm"
    "mp3" -> "audio/mpeg"
    "m4a" -> "audio/mp4"
    "ogg", "opus" -> "audio/ogg"
    "wav" -> "audio/wav"
    "flac" -> "audio/flac"
    "gif" -> "image/gif"
    else -> null
}

private val DOCUMENT_VIDEO_EXTENSIONS = setOf("3gp", "avi", "flv", "m4v", "mkv", "mov", "mp4", "webm", "wmv")
private val DOCUMENT_AUDIO_EXTENSIONS = setOf("aac", "amr", "flac", "m4a", "mp3", "oga", "ogg", "opus", "wav", "wma")

internal fun JsonObject.string(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull
internal fun JsonObject.int(name: String): Int = this[name]?.jsonPrimitive?.intOrNull ?: 0
internal fun JsonObject.long(name: String): Long = this[name]?.jsonPrimitive?.longOrNull ?: 0L
internal fun JsonObject.bool(name: String): Boolean = this[name]?.jsonPrimitive?.booleanOrNull == true
internal fun JsonObject.obj(name: String): JsonObject? = this[name] as? JsonObject
