package com.nmtuong.telegramdrive.data.fake

import android.graphics.Bitmap
import com.nmtuong.telegramdrive.domain.HistoryPage
import com.nmtuong.telegramdrive.domain.SavedMediaGateway
import com.nmtuong.telegramdrive.domain.SavedMessageUpdate
import com.nmtuong.telegramdrive.domain.ActionResult
import com.nmtuong.telegramdrive.domain.TdLibFileSnapshot
import java.io.File
import java.io.ByteArrayOutputStream
import java.io.RandomAccessFile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** Deterministic Saved Messages source used by the fake runtime and sync tests. */
class FakeSavedMediaGateway(
  private val catalog: FakeTelegramCatalog,
  private val cacheDirectory: File = File(System.getProperty("java.io.tmpdir") ?: "build", "telegram-drive-fake-media"),
  private val videoBytes: () -> ByteArray = { ByteArray(0) },
) : SavedMediaGateway {
  private val updates = MutableSharedFlow<SavedMessageUpdate>(extraBufferCapacity = 128)
  override val savedMessageUpdates: Flow<SavedMessageUpdate> = updates.asSharedFlow()
  private val fileUpdateEvents = MutableSharedFlow<TdLibFileSnapshot>(replay = 1, extraBufferCapacity = 128)
  override val fileUpdates: Flow<TdLibFileSnapshot> = fileUpdateEvents.asSharedFlow()

  private val savedChatId = catalog.sources.firstOrNull { it.savedMessages }?.id ?: 0L
  private val fileSnapshots = mutableMapOf<Int, TdLibFileSnapshot>()
  private val downloadedPrefixes = mutableMapOf<Int, Long>()

  override suspend fun getSavedMessagesChatId(): Long? = savedChatId.takeIf { it != 0L }

  override suspend fun getSavedMessagesHead(chatId: Long): Long? = catalog.rawMessages
    .asSequence()
    .filter { it.sourceId == chatId }
    .maxOfOrNull { it.id }

  override suspend fun loadHistoryPage(chatId: Long, fromMessageId: Long, limit: Int): HistoryPage {
    val messages = catalog.rawMessages
      .asSequence()
      .filter { it.sourceId == chatId && (fromMessageId == 0L || it.id < fromMessageId) }
      .sortedByDescending { it.id }
      .take(limit.coerceIn(1, 100))
      .toList()
    if (messages.isEmpty()) return HistoryPage.empty()
    return HistoryPage(
      items = messages.mapNotNull { it.mediaItem },
      rawLastMessageId = messages.last().id,
      endOfHistory = messages.size < limit,
    )
  }

  suspend fun emit(update: SavedMessageUpdate) = updates.emit(update)

  override suspend fun getFileSnapshot(fileId: Int): TdLibFileSnapshot? = synchronized(this) { fileSnapshots[fileId] }

  override fun requestFileRange(fileId: Int, offsetBytes: Long, limitBytes: Long, priority: Int): ActionResult {
    val content = contentFor(fileId)
    if (content.isEmpty()) return ActionResult.INVALID_STATE
    val offset = offsetBytes.coerceIn(0L, content.size.toLong())
    val requestedEnd = if (limitBytes <= 0L) content.size.toLong() else (offset + limitBytes).coerceAtMost(content.size.toLong())
    val path = cacheDirectory.resolve("$fileId.partial")
    runCatching {
      cacheDirectory.mkdirs()
      RandomAccessFile(path, "rw").use { file ->
        file.seek(offset)
        file.write(content, offset.toInt(), (requestedEnd - offset).toInt())
      }
      synchronized(this) {
        val previousPrefix = downloadedPrefixes[fileId] ?: 0L
        val prefix = if (offset <= previousPrefix) maxOf(previousPrefix, requestedEnd) else previousPrefix
        downloadedPrefixes[fileId] = prefix
        val completed = prefix >= content.size.toLong()
        fileSnapshots[fileId] = TdLibFileSnapshot(
          fileId = fileId,
          stableFileIdentity = "tdlib:$fileId",
          localPath = path.absolutePath,
          expectedSizeBytes = content.size.toLong(),
          downloadedSizeBytes = maxOf(previousPrefix, requestedEnd),
          downloadedPrefixSizeBytes = prefix,
          downloadOffsetBytes = offset,
          isDownloadingCompleted = completed,
          isReadable = path.isFile && path.canRead(),
        )
        fileUpdateEvents.tryEmit(checkSnapshot(fileId))
      }
    }.getOrElse { return ActionResult.INVALID_STATE }
    return ActionResult.ACCEPTED
  }

  override fun cancelFileRange(fileId: Int): ActionResult = ActionResult.ACCEPTED

  override fun deleteTemporaryFile(fileId: Int): ActionResult {
    synchronized(this) {
      fileSnapshots.remove(fileId)
      downloadedPrefixes.remove(fileId)
    }
    cacheDirectory.resolve("$fileId.partial").delete()
    return ActionResult.ACCEPTED
  }

  private fun contentFor(fileId: Int): ByteArray = when (fileId) {
    100 -> onePixelJpeg()
    101, 102 -> videoBytes()
    else -> ByteArray(0)
  }

  private fun checkSnapshot(fileId: Int): TdLibFileSnapshot = checkNotNull(fileSnapshots[fileId])

  private fun onePixelJpeg(): ByteArray {
    val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
    return ByteArrayOutputStream().use { output ->
      bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output)
      bitmap.recycle()
      output.toByteArray()
    }
  }
}
