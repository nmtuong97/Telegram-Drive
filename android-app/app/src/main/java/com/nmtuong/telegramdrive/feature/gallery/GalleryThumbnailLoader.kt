package com.nmtuong.telegramdrive.feature.gallery

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.nmtuong.telegramdrive.domain.AccountSessionIdentity
import java.io.Closeable
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext

internal data class GalleryThumbnailSource(
  val accountIdentity: AccountSessionIdentity,
  val stableIdentity: String,
  val filePath: String?,
  val minithumbnailData: String?,
)

internal fun interface GalleryThumbnailDecoder {
  suspend fun decode(source: GalleryThumbnailSource, targetWidthPx: Int, targetHeightPx: Int): Bitmap?
}

/** Small LRU cache with both entry and memory bounds so thumbnails cannot grow without limit. */
internal class BoundedThumbnailCache<K, V>(
  private val maxEntries: Int,
  private val maxBytes: Long,
  private val sizeOf: (V) -> Long,
) {
  private data class Entry<V>(val value: V, val bytes: Long)

  private val entries = LinkedHashMap<K, Entry<V>>(16, 0.75f, true)
  private var bytes = 0L

  @Synchronized
  fun get(key: K): V? = entries[key]?.value

  @Synchronized
  fun put(key: K, value: V) {
    entries.remove(key)?.let { bytes -= it.bytes }
    val valueBytes = sizeOf(value).coerceAtLeast(0L)
    if (maxEntries <= 0 || maxBytes <= 0L || valueBytes > maxBytes) return
    entries[key] = Entry(value, valueBytes)
    bytes += valueBytes
    trimToBounds()
  }

  @Synchronized
  fun removeIf(predicate: (K) -> Boolean) {
    entries.keys.filter(predicate).forEach { key ->
      entries.remove(key)?.let { bytes -= it.bytes }
    }
  }

  @Synchronized
  fun clear() {
    entries.clear()
    bytes = 0L
  }

  @Synchronized
  fun entryCountForTests(): Int = entries.size

  @Synchronized
  fun byteCountForTests(): Long = bytes

  private fun trimToBounds() {
    while (entries.size > maxEntries || bytes > maxBytes) {
      val eldest = entries.entries.iterator().next()
      bytes -= eldest.value.bytes
      entries.remove(eldest.key)
    }
  }
}

internal class GalleryThumbnailLoader(
  private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
  maxCacheEntries: Int = MAX_CACHE_ENTRIES,
  maxCacheBytes: Long = MAX_CACHE_BYTES,
  private val decoder: GalleryThumbnailDecoder = GalleryThumbnailDecoder { source, width, height ->
    decodeGalleryThumbnail(source, width, height)
  },
) : Closeable {
  private val scope = CoroutineScope(SupervisorJob() + dispatcher)
  private val cache = BoundedThumbnailCache<CacheKey, Bitmap>(
    maxEntries = maxCacheEntries,
    maxBytes = maxCacheBytes,
    sizeOf = { bitmap -> bitmap.allocationByteCount.toLong() },
  )
  private val inFlight = ConcurrentHashMap<CacheKey, InFlightDecode>()

  suspend fun load(source: GalleryThumbnailSource, targetWidthPx: Int, targetHeightPx: Int): Bitmap? {
    val key = CacheKey(source, targetWidthPx, targetHeightPx)
    cache.get(key)?.let { return it }
    val inFlightDecode = synchronized(inFlight) {
      inFlight[key]?.also { it.waiters++ } ?: InFlightDecode(
        deferred = scope.async {
          withContext(dispatcher) {
            decoder.decode(source, targetWidthPx, targetHeightPx)
          }
        },
        waiters = 1,
      ).also { inFlight[key] = it }
    }
    return try {
      inFlightDecode.deferred.await()?.also { cache.put(key, it) }
    } finally {
      synchronized(inFlight) {
        inFlightDecode.waiters--
        if (inFlightDecode.waiters == 0) {
          if (!inFlightDecode.deferred.isCompleted) inFlightDecode.deferred.cancel()
          inFlight.remove(key, inFlightDecode)
        } else if (inFlightDecode.deferred.isCompleted) {
          inFlight.remove(key, inFlightDecode)
        }
      }
    }
  }

  fun clearAccount(accountIdentity: AccountSessionIdentity) {
    cache.removeIf { it.accountIdentity == accountIdentity }
    cancelInFlight { it.accountIdentity == accountIdentity }
  }

  fun clear() {
    cache.clear()
    cancelInFlight { true }
  }

  internal fun cacheEntryCountForTests(): Int = cache.entryCountForTests()

  internal fun cacheByteCountForTests(): Long = cache.byteCountForTests()

  override fun close() {
    clear()
    scope.cancel()
  }

  private fun cancelInFlight(predicate: (CacheKey) -> Boolean) {
    synchronized(inFlight) {
      inFlight.entries.filter { predicate(it.key) }.forEach { (key, inFlightDecode) ->
        inFlightDecode.deferred.cancel()
        inFlight.remove(key, inFlightDecode)
      }
    }
  }

  private class InFlightDecode(
    val deferred: Deferred<Bitmap?>,
    var waiters: Int,
  )

  private data class CacheKey(
    val accountIdentity: AccountSessionIdentity,
    val stableIdentity: String,
    val filePath: String?,
    val minithumbnailHash: Int,
    val targetWidthPx: Int,
    val targetHeightPx: Int,
  ) {
    constructor(source: GalleryThumbnailSource, targetWidthPx: Int, targetHeightPx: Int) : this(
      accountIdentity = source.accountIdentity,
      stableIdentity = source.stableIdentity,
      filePath = source.filePath,
      minithumbnailHash = source.minithumbnailData?.hashCode() ?: 0,
      targetWidthPx = targetWidthPx,
      targetHeightPx = targetHeightPx,
    )
  }

  private companion object {
    const val MAX_CACHE_ENTRIES = 120
    const val MAX_CACHE_BYTES = 16L * 1024L * 1024L
  }
}

internal fun calculateGalleryInSampleSize(
  sourceWidth: Int,
  sourceHeight: Int,
  targetWidth: Int,
  targetHeight: Int,
): Int {
  if (sourceWidth <= 0 || sourceHeight <= 0) return 1
  val safeTargetWidth = targetWidth.coerceAtLeast(1)
  val safeTargetHeight = targetHeight.coerceAtLeast(1)
  var sample = 1
  while (
    sourceWidth / (sample * 2) >= safeTargetWidth &&
      sourceHeight / (sample * 2) >= safeTargetHeight
  ) {
    sample *= 2
  }
  return sample
}

internal fun decodeGalleryThumbnail(
  source: GalleryThumbnailSource,
  targetWidthPx: Int,
  targetHeightPx: Int,
): Bitmap? {
  val fileBitmap = source.filePath
    ?.trim()
    ?.takeIf { it.isNotEmpty() }
    ?.let { decodeFileDownsampled(it, targetWidthPx, targetHeightPx) }
  if (fileBitmap != null) return fileBitmap

  return source.minithumbnailData
    ?.takeIf { it.isNotBlank() }
    ?.let { encoded ->
      runCatching {
        val bytes = Base64.decode(encoded, Base64.DEFAULT)
        decodeBytesDownsampled(bytes, targetWidthPx, targetHeightPx)
      }.getOrNull()
    }
}

private fun decodeFileDownsampled(path: String, targetWidthPx: Int, targetHeightPx: Int): Bitmap? {
  val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
  BitmapFactory.decodeFile(path, bounds)
  if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
  val options = BitmapFactory.Options().apply {
    inSampleSize = calculateGalleryInSampleSize(
      bounds.outWidth,
      bounds.outHeight,
      targetWidthPx,
      targetHeightPx,
    )
    inPreferredConfig = Bitmap.Config.ARGB_8888
  }
  return runCatching { BitmapFactory.decodeFile(path, options) }.getOrNull()
}

private fun decodeBytesDownsampled(bytes: ByteArray, targetWidthPx: Int, targetHeightPx: Int): Bitmap? {
  if (bytes.isEmpty()) return null
  val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
  BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
  if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
  val options = BitmapFactory.Options().apply {
    inSampleSize = calculateGalleryInSampleSize(
      bounds.outWidth,
      bounds.outHeight,
      targetWidthPx,
      targetHeightPx,
    )
    inPreferredConfig = Bitmap.Config.ARGB_8888
  }
  return runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) }.getOrNull()
}
