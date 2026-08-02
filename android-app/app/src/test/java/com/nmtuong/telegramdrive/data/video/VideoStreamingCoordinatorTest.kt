package com.nmtuong.telegramdrive.data.video

import com.nmtuong.telegramdrive.domain.ActionResult
import com.nmtuong.telegramdrive.domain.HistoryPage
import com.nmtuong.telegramdrive.domain.SavedMediaGateway
import com.nmtuong.telegramdrive.domain.SavedMessageUpdate
import com.nmtuong.telegramdrive.domain.TdLibFileSnapshot
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoStreamingCoordinatorTest {
  @Test
  fun readsInitialRangeThenSeeksWithoutFullDownload() = runTest {
    val root = Files.createTempDirectory("tdlib-stream-test-").toFile()
    val content = ByteArray(2 * 1024 * 1024) { index -> (index % 251).toByte() }
    val gateway = RangeGateway(root, content)
    val coordinator = VideoStreamingCoordinator(
      gateway = gateway,
      fileId = 77,
      stableFileIdentity = "remote-unique:video-77",
      rangeSizeBytes = 4096L,
      waitTimeoutMs = 2_000L,
    )

    coordinator.open(0L, -1L)
    val first = ByteArray(256)
    assertEquals(256, coordinator.readAt(first, 0, first.size))
    assertArrayEquals(content.copyOfRange(0, 256), first)
    assertTrue(gateway.lastRequestedLimit < content.size.toLong())
    assertTrue(gateway.lastRequestedOffset == 0L)

    coordinator.seek(1_048_576L)
    val sought = ByteArray(128)
    assertEquals(128, coordinator.readAt(sought, 0, sought.size))
    assertArrayEquals(content.copyOfRange(1_048_576, 1_048_704), sought)
    assertEquals(1_048_576L, gateway.lastRequestedOffset)
    assertTrue(gateway.deleteCalls == 0)

    coordinator.close()
    assertEquals(1, gateway.deleteCalls)
    assertEquals(VideoStreamingState.CLOSED, coordinator.status.value.state)
    root.deleteRecursively()
  }
}

private class RangeGateway(
  private val root: File,
  private val content: ByteArray,
) : SavedMediaGateway {
  private val events = MutableSharedFlow<TdLibFileSnapshot>(replay = 1, extraBufferCapacity = 4)
  private var snapshot: TdLibFileSnapshot? = null
  var lastRequestedOffset: Long = -1L
  var lastRequestedLimit: Long = -1L
  var deleteCalls: Int = 0

  override val fileUpdates: Flow<TdLibFileSnapshot> = events.asSharedFlow()
  override val savedMessageUpdates: Flow<SavedMessageUpdate> = MutableSharedFlow<SavedMessageUpdate>().asSharedFlow()
  override suspend fun getSavedMessagesChatId(): Long? = null
  override suspend fun loadHistoryPage(chatId: Long, fromMessageId: Long, limit: Int): HistoryPage = HistoryPage.empty()
  override suspend fun getFileSnapshot(fileId: Int): TdLibFileSnapshot? = snapshot

  override fun requestFileRange(fileId: Int, offsetBytes: Long, limitBytes: Long, priority: Int): ActionResult {
    lastRequestedOffset = offsetBytes
    lastRequestedLimit = limitBytes
    val end = (offsetBytes + limitBytes).coerceAtMost(content.size.toLong())
    val path = root.resolve("video.partial")
    root.mkdirs()
    RandomAccessFile(path, "rw").use { file ->
      file.seek(offsetBytes)
      file.write(content, offsetBytes.toInt(), (end - offsetBytes).toInt())
    }
    snapshot = TdLibFileSnapshot(
      fileId = fileId,
      stableFileIdentity = "remote-unique:video-77",
      localPath = path.absolutePath,
      expectedSizeBytes = content.size.toLong(),
      downloadedSizeBytes = end,
      downloadedPrefixSizeBytes = if (offsetBytes == 0L) end else 0L,
      downloadOffsetBytes = offsetBytes,
      isDownloadingCompleted = end == content.size.toLong() && offsetBytes == 0L,
      isReadable = true,
    )
    events.tryEmit(checkNotNull(snapshot))
    return ActionResult.ACCEPTED
  }

  override fun cancelFileRange(fileId: Int): ActionResult = ActionResult.ACCEPTED

  override fun deleteTemporaryFile(fileId: Int): ActionResult {
    deleteCalls++
    snapshot = null
    return ActionResult.ACCEPTED
  }
}
