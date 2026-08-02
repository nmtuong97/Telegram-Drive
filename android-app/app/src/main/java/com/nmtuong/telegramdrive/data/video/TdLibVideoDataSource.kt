package com.nmtuong.telegramdrive.data.video

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.annotation.OptIn
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import java.io.IOException
import kotlinx.coroutines.runBlocking

/** Media3 bridge for TDLib partial/range data. */
@OptIn(UnstableApi::class)
class TdLibVideoDataSource(
  private val coordinatorFactory: (DataSpec) -> VideoStreamingCoordinator,
  private val releaseFactory: (DataSpec) -> (() -> Unit) = { {} },
) : BaseDataSource(false) {
  private var coordinator: VideoStreamingCoordinator? = null
  private var release: (() -> Unit)? = null
  private var opened = false
  private var remainingBytes = C.LENGTH_UNSET.toLong()
  private var uri: Uri? = null

  @Throws(IOException::class)
  override fun open(dataSpec: DataSpec): Long {
    val activeCoordinator = coordinatorFactory(dataSpec)
    coordinator = activeCoordinator
    release = releaseFactory(dataSpec)
    uri = dataSpec.uri
    remainingBytes = activeCoordinator.open(dataSpec.position, dataSpec.length)
    opened = true
    transferStarted(dataSpec)
    return remainingBytes
  }

  @Throws(IOException::class)
  override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
    if (length == 0) return 0
    if (remainingBytes == 0L) return C.RESULT_END_OF_INPUT
    return try {
      val requested = if (remainingBytes == C.LENGTH_UNSET.toLong()) length else minOf(length.toLong(), remainingBytes).toInt()
      val count = runBlocking { checkNotNull(coordinator).readAt(buffer, offset, requested) }
      if (remainingBytes != C.LENGTH_UNSET.toLong()) remainingBytes -= count
      bytesTransferred(count)
      count
    } catch (error: Exception) {
      throw IOException("TDLib video range read failed", error)
    }
  }

  override fun getUri(): Uri? = uri

  @Throws(IOException::class)
  override fun close() {
    if (!opened) return
    opened = false
    coordinator?.close()
    coordinator = null
    release?.invoke()
    release = null
    transferEnded()
  }

  class Factory(
    private val coordinatorFactory: (DataSpec) -> VideoStreamingCoordinator,
    private val releaseFactory: (DataSpec) -> (() -> Unit) = { {} },
  ) : DataSource.Factory {
    override fun createDataSource(): DataSource = TdLibVideoDataSource(coordinatorFactory, releaseFactory)
  }
}
