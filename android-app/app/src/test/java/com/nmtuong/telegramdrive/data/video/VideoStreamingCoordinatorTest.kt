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
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoStreamingCoordinatorTest {
  @Test
  fun diagnosticsTrackRangeReadAndResourceCleanupWithoutIdentifiers() = runTest {
    VideoStreamingDiagnostics.resetForTests()
    val root = Files.createTempDirectory("tdlib-stream-diagnostics-").toFile()
    val gateway = RangeGateway(root, ByteArray(4_096) { 7 })
    val coordinator = VideoStreamingCoordinator(
      gateway = gateway,
      fileId = 77,
      stableFileIdentity = "remote-unique:video-77",
      rangeSizeBytes = 256L,
      waitTimeoutMs = 500L,
    )
    val reader = coordinator.openReader(128L, -1L)

    assertEquals(32, coordinator.readAt(reader, 128L, ByteArray(32), 0, 32))
    coordinator.closeReader(reader)
    coordinator.close()

    val metrics = VideoStreamingDiagnostics.snapshot()
    assertEquals(1, metrics.coordinatorCreateCount)
    assertEquals(1, metrics.coordinatorCloseCount)
    assertEquals(0, metrics.activeCoordinatorCount)
    assertEquals(1, metrics.readerOpenCount)
    assertEquals(1, metrics.readerCloseCount)
    assertEquals(0, metrics.activeReaderCount)
    assertEquals(1, metrics.rangeRequestCount)
    assertEquals(128L, metrics.rangeOffset)
    assertEquals(32L, metrics.bytesRead)
    root.deleteRecursively()
  }

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

  @Test
  fun sharedCoordinatorKeepsIndependentReaderPositions() = runTest {
    val root = Files.createTempDirectory("tdlib-stream-readers-").toFile()
    val content = ByteArray(2 * 1024 * 1024) { index -> (index % 251).toByte() }
    val gateway = RangeGateway(root, content)
    val coordinator = VideoStreamingCoordinator(
      gateway = gateway,
      fileId = 77,
      stableFileIdentity = "remote-unique:video-77",
      rangeSizeBytes = 4096L,
      waitTimeoutMs = 2_000L,
    )

    coordinator.openReader(0L, -1L)
    coordinator.openReader(1_048_576L, -1L)
    val first = ByteArray(64)
    val second = ByteArray(64)

    assertEquals(64, coordinator.readAt(0L, first, 0, first.size))
    assertEquals(64, coordinator.readAt(1_048_576L, second, 0, second.size))
    assertArrayEquals(content.copyOfRange(0, 64), first)
    assertArrayEquals(content.copyOfRange(1_048_576, 1_048_640), second)

    coordinator.close()
    root.deleteRecursively()
  }

  @Test
  fun rejectsCompletedSnapshotWhenLocalFileIsShorterThanExpected() = runTest {
    val root = Files.createTempDirectory("tdlib-stream-short-").toFile()
    val path = root.resolve("video.partial").also {
      it.writeBytes(byteArrayOf(1, 2, 3, 4))
    }
    val gateway = ShortCompletedFileGateway(path)
    val coordinator = VideoStreamingCoordinator(
      gateway = gateway,
      fileId = 78,
      stableFileIdentity = "remote-unique:short-video",
      waitTimeoutMs = 100L,
    )

    coordinator.open(0L, -1L)
    val result = runCatching { coordinator.readAt(ByteArray(4), 0, 4) }

    assertTrue(result.isFailure)
    assertEquals(1, gateway.rangeRequests)
    coordinator.close()
    root.deleteRecursively()
  }

  @Test
  fun returnsEofAtCompletedFileEndWithoutMovingCursorBackwards() = runTest {
    val root = Files.createTempDirectory("tdlib-stream-eof-").toFile()
    val content = byteArrayOf(1, 2, 3, 4)
    val gateway = RangeGateway(root, content)
    val coordinator = VideoStreamingCoordinator(
      gateway = gateway,
      fileId = 80,
      stableFileIdentity = "remote-unique:video-77",
      rangeSizeBytes = content.size.toLong(),
      waitTimeoutMs = 500L,
    )

    coordinator.open(0L, -1L)
    assertEquals(4, coordinator.readAt(ByteArray(4), 0, 4))
    assertEquals(androidx.media3.common.C.RESULT_END_OF_INPUT, coordinator.readAt(ByteArray(4), 0, 4))
    assertEquals(4L, coordinator.status.value.positionBytes)

    coordinator.close()
    root.deleteRecursively()
  }

  @Test
  fun seekSupersedesAWaitingRangeWithoutWaitingForTimeout() = runTest {
    val gateway = BlockingRangeGateway()
    val coordinator = VideoStreamingCoordinator(
      gateway = gateway,
      fileId = 79,
      stableFileIdentity = "remote-unique:blocking-video",
      waitTimeoutMs = 2_000L,
    )

    coordinator.open(0L, -1L)
    val read = async { coordinator.readAt(ByteArray(32), 0, 32) }
    withTimeout(1_000L) {
      while (gateway.rangeRequests == 0) delay(10L)
    }

    coordinator.seek(4_096L)
    val result = withTimeout(1_000L) { runCatching { read.await() } }

    assertTrue(result.isFailure)
    assertTrue(gateway.cancelCalls > 0)
    coordinator.close()
  }

  @Test
  fun seekSupersedesAWaitingReaderNotOwnedByDefaultCursor() = runTest {
    val gateway = BlockingRangeGateway()
    val coordinator = VideoStreamingCoordinator(
      gateway = gateway,
      fileId = 81,
      stableFileIdentity = "remote-unique:reader-seek",
      waitTimeoutMs = 2_000L,
    )
    val reader = coordinator.openReader(0L, -1L)
    val read = async { coordinator.readAt(reader, 0L, ByteArray(32), 0, 32) }
    withTimeout(1_000L) {
      while (gateway.rangeRequests == 0) delay(10L)
    }

    coordinator.seek(8_192L)

    val result = withTimeout(1_000L) { runCatching { read.await() } }
    assertTrue(result.isFailure)
    assertTrue(gateway.cancelCalls > 0)
    coordinator.close()
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

private class ShortCompletedFileGateway(
  private val path: File,
) : SavedMediaGateway {
  private val events = MutableSharedFlow<TdLibFileSnapshot>(replay = 1, extraBufferCapacity = 1)
  var rangeRequests: Int = 0

  override val fileUpdates: Flow<TdLibFileSnapshot> = events.asSharedFlow()
  override val savedMessageUpdates: Flow<SavedMessageUpdate> = MutableSharedFlow<SavedMessageUpdate>().asSharedFlow()
  override suspend fun getSavedMessagesChatId(): Long? = null
  override suspend fun loadHistoryPage(chatId: Long, fromMessageId: Long, limit: Int): HistoryPage = HistoryPage.empty()
  override suspend fun getFileSnapshot(fileId: Int): TdLibFileSnapshot = TdLibFileSnapshot(
    fileId = fileId,
    stableFileIdentity = "remote-unique:short-video",
    localPath = path.absolutePath,
    expectedSizeBytes = 10L,
    downloadedSizeBytes = 10L,
    downloadedPrefixSizeBytes = 10L,
    downloadOffsetBytes = 0L,
    isDownloadingCompleted = true,
    isReadable = true,
  )

  override fun requestFileRange(fileId: Int, offsetBytes: Long, limitBytes: Long, priority: Int): ActionResult {
    rangeRequests++
    return ActionResult.INVALID_STATE
  }

  override fun cancelFileRange(fileId: Int): ActionResult = ActionResult.ACCEPTED
  override fun deleteTemporaryFile(fileId: Int): ActionResult = ActionResult.ACCEPTED
}

private class BlockingRangeGateway : SavedMediaGateway {
  private val events = MutableSharedFlow<TdLibFileSnapshot>(extraBufferCapacity = 1)
  var rangeRequests: Int = 0
  var cancelCalls: Int = 0

  override val fileUpdates: Flow<TdLibFileSnapshot> = events.asSharedFlow()
  override val savedMessageUpdates: Flow<SavedMessageUpdate> = MutableSharedFlow<SavedMessageUpdate>().asSharedFlow()
  override suspend fun getSavedMessagesChatId(): Long? = null
  override suspend fun loadHistoryPage(chatId: Long, fromMessageId: Long, limit: Int): HistoryPage = HistoryPage.empty()
  override suspend fun getFileSnapshot(fileId: Int): TdLibFileSnapshot? = null

  override fun requestFileRange(fileId: Int, offsetBytes: Long, limitBytes: Long, priority: Int): ActionResult {
    rangeRequests++
    return ActionResult.ACCEPTED
  }

  override fun cancelFileRange(fileId: Int): ActionResult {
    cancelCalls++
    return ActionResult.ACCEPTED
  }

  override fun deleteTemporaryFile(fileId: Int): ActionResult = ActionResult.ACCEPTED
}
