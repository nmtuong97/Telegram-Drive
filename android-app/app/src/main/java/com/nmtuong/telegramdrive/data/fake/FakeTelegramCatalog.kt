package com.nmtuong.telegramdrive.data.fake

import com.nmtuong.telegramdrive.domain.*

data class FakeRawMessage(
    val id: Long,
    val sourceId: Long,
    val text: String? = null,
    val mediaItem: MediaItem? = null,
)

data class FakeTelegramCatalog(
    val account: Account,
    val sources: List<FileSource>,
    val media: List<MediaItem>,
    val rawMessages: List<FakeRawMessage> = emptyList(),
) {
    companion object {
        fun stable(): FakeTelegramCatalog {
            val account = Account(1, "Phase Zero Developer")
            val sources = listOf(
                FileSource(10, "Saved Messages", true),
                FileSource(11, "Design Assets", false),
                FileSource(12, "Project Documents", false),
            )

            val media = listOf(
                MediaItem(100, 10, "mountain.jpg", MediaKind.IMAGE, DownloadState.Complete),
                MediaItem(106, 10, "mountain-duplicate.jpg", MediaKind.IMAGE, DownloadState.NotDownloaded, fileId = 100),
                MediaItem(101, 10, "demo.mp4", MediaKind.VIDEO, DownloadState.Downloading(42)),
                MediaItem(105, 10, "demo.gif", MediaKind.ANIMATION, DownloadState.NotDownloaded),
                MediaItem(107, 10, "voice-note.ogg", MediaKind.AUDIO, DownloadState.NotDownloaded, mimeType = "audio/ogg"),
                MediaItem(108, 10, "notes.txt", MediaKind.DOCUMENT, DownloadState.NotDownloaded, mimeType = "text/plain"),
                MediaItem(109, 10, "specification.pdf", MediaKind.PDF, DownloadState.NotDownloaded, mimeType = "application/pdf"),
                MediaItem(102, 11, "theme.mp3", MediaKind.AUDIO, DownloadState.Complete, mimeType = "audio/mpeg"),
                MediaItem(103, 12, "specification.pdf", MediaKind.PDF, DownloadState.Complete, mimeType = "application/pdf"),
                MediaItem(104, 12, "archive.zip", MediaKind.DOCUMENT, DownloadState.Failed("Sample offline failure"), mimeType = "application/zip"),
            )

            val deepGalleryMessages = (1L..120L).map { index ->
                val isVideo = index % 3L == 0L
                // Positive IDs mirror Telegram's message-ID contract. The sample
                // messages below stay at the history head, while this deep fixture
                // still covers more than one history page.
                val messageId = index
                FakeRawMessage(
                    id = messageId,
                    sourceId = 10L,
                    mediaItem = MediaItem(
                        id = messageId,
                        sourceId = 10L,
                        name = if (isVideo) "fixture-video-$index.mp4" else "fixture-image-$index.jpg",
                        kind = if (isVideo) MediaKind.VIDEO else MediaKind.IMAGE,
                        downloadState = DownloadState.NotDownloaded,
                        fileId = (2_000L + index).toInt(),
                        mimeType = if (isVideo) "video/mp4" else "image/jpeg",
                    ),
                )
            }

            val rawMessages = listOf(
                // Page 1 items (Saved Messages, sourceId=10)
                FakeRawMessage(1_010L, 10L, mediaItem = MediaItem(1_010L, 10L, "mountain.jpg", MediaKind.IMAGE, DownloadState.Complete, fileId = 100)),
                FakeRawMessage(1_009L, 10L, text = "Text message on page 1"),
                FakeRawMessage(1_008L, 10L, mediaItem = MediaItem(1_008L, 10L, "demo.mp4", MediaKind.VIDEO, DownloadState.Downloading(42), fileId = 101)),

                // Page 2 items (Saved Messages) — Text-only page
                FakeRawMessage(1_007L, 10L, text = "Pure text message 1"),
                FakeRawMessage(1_006L, 10L, text = "Pure text message 2"),
                FakeRawMessage(1_005L, 10L, text = "Pure text message 3"),

                // Page 3 items (Saved Messages)
                FakeRawMessage(1_004L, 10L, mediaItem = MediaItem(1_004L, 10L, "mountain-duplicate.jpg", MediaKind.IMAGE, DownloadState.NotDownloaded, fileId = 100)),
                FakeRawMessage(1_003L, 10L, text = "Text message before video"),
                FakeRawMessage(1_002L, 10L, mediaItem = MediaItem(1_002L, 10L, "trailer.mp4", MediaKind.VIDEO, DownloadState.NotDownloaded, fileId = 102)),
                FakeRawMessage(1_001L, 10L, mediaItem = MediaItem(1_001L, 10L, "notes.txt", MediaKind.DOCUMENT, DownloadState.NotDownloaded, fileId = 108, mimeType = "text/plain")),
                FakeRawMessage(1_000L, 10L, mediaItem = MediaItem(1_000L, 10L, "demo.gif", MediaKind.ANIMATION, DownloadState.NotDownloaded, fileId = 105, mimeType = "image/gif")),
                FakeRawMessage(999L, 10L, mediaItem = MediaItem(999L, 10L, "voice-note.ogg", MediaKind.AUDIO, DownloadState.NotDownloaded, fileId = 107, mimeType = "audio/ogg")),
                FakeRawMessage(998L, 10L, mediaItem = MediaItem(998L, 10L, "specification.pdf", MediaKind.PDF, DownloadState.NotDownloaded, fileId = 109, mimeType = "application/pdf")),
                FakeRawMessage(990L, 11L, mediaItem = MediaItem(990L, 11L, "theme.mp3", MediaKind.AUDIO, DownloadState.NotDownloaded, fileId = 102, mimeType = "audio/mpeg")),
                FakeRawMessage(989L, 12L, mediaItem = MediaItem(989L, 12L, "specification.pdf", MediaKind.PDF, DownloadState.NotDownloaded, fileId = 103, mimeType = "application/pdf")),
                FakeRawMessage(988L, 12L, mediaItem = MediaItem(988L, 12L, "archive.zip", MediaKind.DOCUMENT, DownloadState.NotDownloaded, fileId = 104, mimeType = "application/zip")),
            ) + deepGalleryMessages

            return FakeTelegramCatalog(account, sources, media, rawMessages)
        }
    }
}
