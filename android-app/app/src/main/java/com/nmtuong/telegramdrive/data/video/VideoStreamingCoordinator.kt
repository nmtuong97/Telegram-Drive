package com.nmtuong.telegramdrive.data.video

import com.nmtuong.telegramdrive.domain.SavedMediaGateway
import androidx.media3.common.util.UnstableApi
import androidx.annotation.OptIn
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

private const val DEFAULT_RANGE_SIZE_BYTES = 512L * 1024L
private const val DEFAULT_WAIT_TIMEOUT_MS = 30_000L

enum class VideoStreamingState { IDLE, BUFFERING, PLAYING, SEEKING, COMPLETE, ERROR, CLOSED }

data class VideoStreamingStatus(
  val state: VideoStreamingState = VideoStreamingState.IDLE,
  val positionBytes: Long = 0L,
  val requestedBytes: Long = 0L,
  val bufferedPrefixBytes: Long = 0L,
  val expectedSizeBytes: Long = 0L,
  val error: String? = null,
)

/**
 * Serializes range requests for one stable Telegram file identity and exposes only
 * contiguous readable data to Media3. A partial file is intentionally retained only
 * for the current player lifecycle.
 */
@OptIn(UnstableApi::class)
class VideoStreamingCoordinator(
  private val gateway: SavedMediaGateway,
  private val fileId: Int,
  private val stableFileIdentity: String,
  private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
  private val rangeSizeBytes: Long = DEFAULT_RANGE_SIZE_BYTES,
  private val waitTimeoutMs: Long = DEFAULT_WAIT_TIMEOUT_MS,
  private val onClosed: () -> Unit = {},
) : Closeable {
  private val mutex = Mutex()
  private val closed = AtomicBoolean(false)
  private val requestGeneration = AtomicLong(0L)
  private val generationState = MutableStateFlow(0L)
  private val _status = MutableStateFlow(VideoStreamingStatus())
  val status: StateFlow<VideoStreamingStatus> = _status.asStateFlow()

  @Volatile private var positionBytes = 0L
  @Volatile private var lengthBytes = 0L

  fun open(position: Long, length: Long): Long {
    check(!closed.get()) { "Video streaming coordinator is closed" }
    positionBytes = position.coerceAtLeast(0L)
    lengthBytes = length.coerceAtLeast(0L)
    val generation = requestGeneration.incrementAndGet()
    generationState.value = generation
    _status.value = VideoStreamingStatus(positionBytes = positionBytes, requestedBytes = lengthBytes)
    return lengthBytes.takeIf { it > 0L } ?: androidx.media3.common.C.LENGTH_UNSET.toLong()
  }

  /**
   * Opens a Media3 reader without changing the coordinator's compatibility
   * cursor. Transfer coordination is shared per stable file, but each data
   * source owns its playback position.
   */
  fun openReader(position: Long, length: Long): Long {
    check(!closed.get()) { "Video streaming coordinator is closed" }
    return length.coerceAtLeast(0L).takeIf { it > 0L } ?: androidx.media3.common.C.LENGTH_UNSET.toLong()
  }

  suspend fun readAt(buffer: ByteArray, offset: Int, length: Int): Int {
    if (closed.get()) return androidx.media3.common.C.RESULT_END_OF_INPUT
    if (length == 0) return 0
    val read = readAt(positionBytes, buffer, offset, length)
    positionBytes += read.coerceAtLeast(0)
    return read
  }

  /** Reads at an explicit reader position without mutating another reader's cursor. */
  suspend fun readAt(readPosition: Long, buffer: ByteArray, offset: Int, length: Int): Int {
    if (closed.get()) return androidx.media3.common.C.RESULT_END_OF_INPUT
    if (length == 0) return 0
    return mutex.withLock {
      val generation = requestGeneration.get()
      val available = awaitReadableRange(generation, readPosition, length.toLong())
      if (closed.get() || generation != requestGeneration.get()) throw CancellationException("Video range superseded")
      val path = available.localPath ?: throw IllegalStateException("TDLib did not expose a readable partial path")
      val read = RandomAccessFile(path, "r").use { file ->
        file.seek(readPosition)
        file.read(buffer, offset, length)
      }
      if (read <= 0) {
        if (available.isDownloadingCompleted) return@withLock androidx.media3.common.C.RESULT_END_OF_INPUT
        throw IllegalStateException("Partial file ended before requested range")
      }
      _status.value = _status.value.copy(
        state = if (available.isDownloadingCompleted) VideoStreamingState.COMPLETE else VideoStreamingState.PLAYING,
        positionBytes = readPosition + read,
        bufferedPrefixBytes = available.downloadedPrefixSizeBytes,
        expectedSizeBytes = available.expectedSizeBytes,
        error = null,
      )
      read
    }
  }

  fun seek(position: Long) {
    if (closed.get()) return
    positionBytes = position.coerceAtLeast(0L)
    val generation = requestGeneration.incrementAndGet()
    generationState.value = generation
    gateway.cancelFileRange(fileId)
    _status.value = _status.value.copy(
      state = VideoStreamingState.SEEKING,
      positionBytes = positionBytes,
      error = null,
    )
  }

  private suspend fun awaitReadableRange(generation: Long, position: Long, length: Long): com.nmtuong.telegramdrive.domain.TdLibFileSnapshot {
    return try {
      _status.value = _status.value.copy(
        state = VideoStreamingState.BUFFERING,
        positionBytes = position,
        requestedBytes = length,
        error = null,
      )
      val desiredEnd = position + length
      val existing = gateway.getFileSnapshot(fileId)
      if (existing != null && isReadableFor(existing, position, desiredEnd)) return existing

      if (closed.get() || generation != requestGeneration.get()) throw CancellationException("Video range superseded")
      val request = gateway.requestFileRange(fileId, position, rangeSizeBytes.coerceAtLeast(length), priority = 32)
      if (request != com.nmtuong.telegramdrive.domain.ActionResult.ACCEPTED) {
        throw IllegalStateException("TDLib rejected video range request")
      }
      // A fake gateway or a fast TDLib response may publish the snapshot before
      // the collector is attached; re-read once before waiting for the update.
      gateway.getFileSnapshot(fileId)?.let { snapshot ->
        if (isReadableFor(snapshot, position, desiredEnd)) return snapshot
      }
      withTimeout(waitTimeoutMs) {
        when (val result = merge(
          gateway.fileUpdates
            .filter { snapshot ->
              snapshot.fileId == fileId &&
                !closed.get() &&
                generation == requestGeneration.get() &&
                isReadableFor(snapshot, position, desiredEnd)
            }
            .map(AwaitResult::Snapshot),
          generationState
            .filter { it != generation }
            .map { AwaitResult.Superseded },
        ).first()) {
          is AwaitResult.Snapshot -> result.value
          AwaitResult.Superseded -> throw CancellationException("Video range superseded")
        }
      }
    } catch (error: Exception) {
      if (error is CancellationException) throw error
      _status.value = _status.value.copy(state = VideoStreamingState.ERROR, error = error.message ?: "Video buffering failed")
      throw error
    }
  }

  private fun isReadableFor(
    snapshot: com.nmtuong.telegramdrive.domain.TdLibFileSnapshot,
    startInclusive: Long,
    endExclusive: Long,
  ): Boolean {
    if (snapshot.stableFileIdentity != null && snapshot.stableFileIdentity != stableFileIdentity) return false
    val path = snapshot.localPath?.let(::File) ?: return false
    if (!path.isFile || !path.canRead()) return false
    val localSize = path.length()
    if (localSize < endExclusive) return false
    if (snapshot.isDownloadingCompleted) {
      return snapshot.expectedSizeBytes <= 0L || localSize >= snapshot.expectedSizeBytes
    }
    val contiguousPrefix = startInclusive <= snapshot.downloadedPrefixSizeBytes && snapshot.downloadedPrefixSizeBytes >= endExclusive
    val requestedRangeEnd = snapshot.downloadOffsetBytes + snapshot.downloadedSizeBytes
    val requestedRange = snapshot.downloadOffsetBytes <= startInclusive && requestedRangeEnd >= endExclusive
    return contiguousPrefix || requestedRange
  }

  override fun close() {
    if (!closed.compareAndSet(false, true)) return
    val generation = requestGeneration.incrementAndGet()
    generationState.value = generation
    gateway.cancelFileRange(fileId)
    gateway.deleteTemporaryFile(fileId)
    _status.value = _status.value.copy(state = VideoStreamingState.CLOSED)
    onClosed()
  }

  private sealed interface AwaitResult {
    data class Snapshot(val value: com.nmtuong.telegramdrive.domain.TdLibFileSnapshot) : AwaitResult
    data object Superseded : AwaitResult
  }

}
