package com.nmtuong.telegramdrive.data.video

import android.net.Uri
import androidx.media3.datasource.DataSpec
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nmtuong.telegramdrive.domain.ActionResult
import com.nmtuong.telegramdrive.domain.HistoryPage
import com.nmtuong.telegramdrive.domain.SavedMediaGateway
import com.nmtuong.telegramdrive.domain.SavedMessageUpdate
import com.nmtuong.telegramdrive.domain.TdLibFileSnapshot
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.file.Files

@RunWith(AndroidJUnit4::class)
class TdLibVideoDataSourceLifecycleTest {
  @Test
  fun mainThreadReadIsRejectedInsteadOfBlockingUi() {
    val coordinator = VideoStreamingCoordinator(
      gateway = CloseTrackingGateway(),
      fileId = 76,
      stableFileIdentity = "remote-unique:main-thread-guard",
    )
    val source = TdLibVideoDataSource.Factory(
      coordinatorFactory = { coordinator },
      releaseFactory = { _, _ -> { coordinator.close() } },
    ).createDataSource()
    source.open(DataSpec(Uri.parse("tdlib://main-thread-guard")))

    val error = runCatching { source.read(ByteArray(8), 0, 8) }.exceptionOrNull()

    assertTrue(error is java.io.IOException)
    source.close()
  }

  @Test
  fun closingOneOfTwoSharedDataSourcesDoesNotCloseTransfer() {
    val gateway = CloseTrackingGateway()
    val coordinator = VideoStreamingCoordinator(
      gateway = gateway,
      fileId = 77,
      stableFileIdentity = "remote-unique:shared-video",
    )
    var references = 0
    val factory = TdLibVideoDataSource.Factory(
      coordinatorFactory = {
        references++
        coordinator
      },
      releaseFactory = { _, _ ->
        {
          references--
          if (references == 0) coordinator.close()
        }
      },
    )
    val first = factory.createDataSource()
    val second = factory.createDataSource()
    val dataSpec = DataSpec(Uri.parse("tdlib://shared-video"))

    first.open(dataSpec)
    second.open(dataSpec)
    first.close()

    assertNotEquals(VideoStreamingState.CLOSED, coordinator.status.value.state)
    assertEquals(0, gateway.deleteCalls)

    second.close()
    assertEquals(VideoStreamingState.CLOSED, coordinator.status.value.state)
    assertEquals(1, gateway.deleteCalls)
  }

  @Test
  fun media3ReopenCancelsOldReadAndPreservesSharedCoordinator() {
    runBlocking {
    val root = Files.createTempDirectory("tdlib-data-source-seek-").toFile()
    val gateway = RepositionGateway(root)
    val coordinator = VideoStreamingCoordinator(
      gateway = gateway,
      fileId = 88,
      stableFileIdentity = "remote-unique:reposition-video",
      rangeSizeBytes = 32L,
      waitTimeoutMs = 2_000L,
    )
    var references = 0
    val factory = TdLibVideoDataSource.Factory(
      coordinatorFactory = { references++; coordinator },
      releaseFactory = { _, _ ->
        {
          references--
          if (references == 0) coordinator.close()
        }
      },
    )

    val first = factory.createDataSource()
    val second = factory.createDataSource()
    first.open(DataSpec(Uri.parse("tdlib://reposition-video"), 0L, -1L))
    val firstRead = async(Dispatchers.IO) {
      runCatching { first.read(ByteArray(32), 0, 32) }
    }
    withTimeout(1_000L) {
      while (gateway.requestedOffsets != listOf(0L)) delay(10L)
    }

    second.open(DataSpec(Uri.parse("tdlib://reposition-video"), 4_096L, -1L))
    first.close()
    assertTrue(firstRead.await().isFailure)
    assertNotEquals(VideoStreamingState.CLOSED, coordinator.status.value.state)

    val secondRead = async(Dispatchers.IO) {
      val bytes = ByteArray(32)
      val count = second.read(bytes, 0, bytes.size)
      count to bytes
    }
    withTimeout(1_000L) {
      while (gateway.requestedOffsets != listOf(0L, 4_096L)) delay(10L)
    }

    gateway.emitRange(offset = 0L, marker = 'A')
    delay(100L)
    assertTrue("late range A must not satisfy range B", !secondRead.isCompleted)
    gateway.emitRange(offset = 4_096L, marker = 'B')
    val (count, bytes) = withTimeout(1_000L) { secondRead.await() }

    assertEquals(32, count)
    assertArrayEquals(ByteArray(32) { 'B'.code.toByte() }, bytes)
    second.close()
    assertEquals(VideoStreamingState.CLOSED, coordinator.status.value.state)
    assertEquals(1, gateway.deleteCalls)
      root.deleteRecursively()
    }
  }

  @Test
  fun eofDoesNotChangeKnownOrUnknownLengthProgress() {
    val root = Files.createTempDirectory("tdlib-data-source-eof-").toFile()
    val knownGateway = CompleteDataSourceGateway(root)
    val knownCoordinator = VideoStreamingCoordinator(
      gateway = knownGateway,
      fileId = 89,
      stableFileIdentity = "remote-unique:complete-video",
    )
    val known = TdLibVideoDataSource.Factory(
      coordinatorFactory = { knownCoordinator },
      releaseFactory = { _, _ -> { knownCoordinator.close() } },
    ).createDataSource()
    val knownBuffer = ByteArray(4)

    assertEquals(4L, known.open(DataSpec(Uri.parse("tdlib://complete-video"), 0L, 4L)))
    assertEquals(4, known.read(knownBuffer, 0, knownBuffer.size))
    assertEquals(androidx.media3.common.C.RESULT_END_OF_INPUT, known.read(knownBuffer, 0, knownBuffer.size))
    known.close()

    val unknownGateway = CompleteDataSourceGateway(root)
    val unknownCoordinator = VideoStreamingCoordinator(
      gateway = unknownGateway,
      fileId = 90,
      stableFileIdentity = "remote-unique:complete-video",
    )
    val unknown = TdLibVideoDataSource.Factory(
      coordinatorFactory = { unknownCoordinator },
      releaseFactory = { _, _ -> { unknownCoordinator.close() } },
    ).createDataSource()

    assertEquals(
      androidx.media3.common.C.LENGTH_UNSET.toLong(),
      unknown.open(DataSpec(Uri.parse("tdlib://complete-video"), 0L, -1L)),
    )
    assertEquals(4, unknown.read(ByteArray(4), 0, 4))
    assertEquals(androidx.media3.common.C.RESULT_END_OF_INPUT, unknown.read(ByteArray(4), 0, 4))
    unknown.close()
    root.deleteRecursively()
  }
}

private class CloseTrackingGateway : SavedMediaGateway {
  var deleteCalls = 0

  override val savedMessageUpdates: Flow<SavedMessageUpdate> = emptyFlow()
  override val fileUpdates: Flow<TdLibFileSnapshot> = emptyFlow()
  override suspend fun getSavedMessagesChatId(): Long? = null
  override suspend fun loadHistoryPage(chatId: Long, fromMessageId: Long, limit: Int): HistoryPage = HistoryPage.empty()
  override fun cancelFileRange(fileId: Int): ActionResult = ActionResult.ACCEPTED
  override fun deleteTemporaryFile(fileId: Int): ActionResult {
    deleteCalls++
    return ActionResult.ACCEPTED
  }
}

private class RepositionGateway(
  private val root: File,
) : SavedMediaGateway {
  private val events = MutableSharedFlow<TdLibFileSnapshot>(extraBufferCapacity = 8)
  val requestedOffsets = CopyOnWriteArrayList<Long>()
  var deleteCalls = 0

  override val savedMessageUpdates: Flow<SavedMessageUpdate> = emptyFlow()
  override val fileUpdates: Flow<TdLibFileSnapshot> = events.asSharedFlow()
  override suspend fun getSavedMessagesChatId(): Long? = null
  override suspend fun loadHistoryPage(chatId: Long, fromMessageId: Long, limit: Int): HistoryPage = HistoryPage.empty()
  override suspend fun getFileSnapshot(fileId: Int): TdLibFileSnapshot? = null

  override fun requestFileRange(fileId: Int, offsetBytes: Long, limitBytes: Long, priority: Int): ActionResult {
    requestedOffsets += offsetBytes
    return ActionResult.ACCEPTED
  }

  fun emitRange(offset: Long, marker: Char) {
    val path = root.resolve("range-$offset.partial")
    root.mkdirs()
    RandomAccessFile(path, "rw").use { file ->
      file.seek(offset)
      file.write(ByteArray(32) { marker.code.toByte() })
    }
    events.tryEmit(
      TdLibFileSnapshot(
        fileId = 88,
        stableFileIdentity = "remote-unique:reposition-video",
        localPath = path.absolutePath,
        expectedSizeBytes = 8_192L,
        downloadedSizeBytes = 32L,
        downloadedPrefixSizeBytes = 0L,
        downloadOffsetBytes = offset,
        isDownloadingCompleted = false,
        isReadable = true,
      ),
    )
  }

  override fun cancelFileRange(fileId: Int): ActionResult = ActionResult.ACCEPTED

  override fun deleteTemporaryFile(fileId: Int): ActionResult {
    deleteCalls++
    return ActionResult.ACCEPTED
  }
}

private class CompleteDataSourceGateway(
  root: File,
) : SavedMediaGateway {
  private val path = root.resolve("complete.video").also {
    root.mkdirs()
    it.writeBytes(byteArrayOf(9, 8, 7, 6))
  }

  override val savedMessageUpdates: Flow<SavedMessageUpdate> = emptyFlow()
  override val fileUpdates: Flow<TdLibFileSnapshot> = emptyFlow()
  override suspend fun getSavedMessagesChatId(): Long? = null
  override suspend fun loadHistoryPage(chatId: Long, fromMessageId: Long, limit: Int): HistoryPage = HistoryPage.empty()
  override suspend fun getFileSnapshot(fileId: Int): TdLibFileSnapshot = TdLibFileSnapshot(
    fileId = fileId,
    stableFileIdentity = "remote-unique:complete-video",
    localPath = path.absolutePath,
    expectedSizeBytes = 4L,
    downloadedSizeBytes = 4L,
    downloadedPrefixSizeBytes = 4L,
    downloadOffsetBytes = 0L,
    isDownloadingCompleted = true,
    isReadable = true,
  )

  override fun requestFileRange(fileId: Int, offsetBytes: Long, limitBytes: Long, priority: Int): ActionResult = ActionResult.ACCEPTED
  override fun cancelFileRange(fileId: Int): ActionResult = ActionResult.ACCEPTED
  override fun deleteTemporaryFile(fileId: Int): ActionResult = ActionResult.ACCEPTED
}
