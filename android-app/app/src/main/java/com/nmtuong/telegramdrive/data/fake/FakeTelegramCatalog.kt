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
                MediaItem(107, 10, "voice-note.ogg", MediaKind.AUDIO, DownloadState.NotDownloaded),
                MediaItem(108, 10, "notes.txt", MediaKind.DOCUMENT, DownloadState.NotDownloaded),
                MediaItem(102, 11, "theme.mp3", MediaKind.AUDIO, DownloadState.Complete),
                MediaItem(103, 12, "specification.pdf", MediaKind.PDF, DownloadState.Complete),
                MediaItem(104, 12, "archive.zip", MediaKind.DOCUMENT, DownloadState.Failed("Sample offline failure")),
            )

            val rawMessages = listOf(
                // Page 1 items (Saved Messages, sourceId=10)
                FakeRawMessage(110L, 10L, mediaItem = MediaItem(110L, 10L, "mountain.jpg", MediaKind.IMAGE, DownloadState.Complete, fileId = 100)),
                FakeRawMessage(109L, 10L, text = "Text message on page 1"),
                FakeRawMessage(108L, 10L, mediaItem = MediaItem(108L, 10L, "demo.mp4", MediaKind.VIDEO, DownloadState.Downloading(42), fileId = 101)),

                // Page 2 items (Saved Messages) — Text-only page
                FakeRawMessage(107L, 10L, text = "Pure text message 1"),
                FakeRawMessage(106L, 10L, text = "Pure text message 2"),
                FakeRawMessage(105L, 10L, text = "Pure text message 3"),

                // Page 3 items (Saved Messages)
                FakeRawMessage(104L, 10L, mediaItem = MediaItem(104L, 10L, "mountain-duplicate.jpg", MediaKind.IMAGE, DownloadState.NotDownloaded, fileId = 100)),
                FakeRawMessage(103L, 10L, text = "Text message before video"),
                FakeRawMessage(102L, 10L, mediaItem = MediaItem(102L, 10L, "trailer.mp4", MediaKind.VIDEO, DownloadState.NotDownloaded, fileId = 102)),
                FakeRawMessage(101L, 10L, mediaItem = MediaItem(101L, 10L, "notes.txt", MediaKind.DOCUMENT, DownloadState.NotDownloaded, fileId = 108)),
            )

            return FakeTelegramCatalog(account, sources, media, rawMessages)
        }
    }
}
