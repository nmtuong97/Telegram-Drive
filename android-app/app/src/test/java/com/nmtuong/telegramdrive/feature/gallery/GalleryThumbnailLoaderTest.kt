package com.nmtuong.telegramdrive.feature.gallery

import com.nmtuong.telegramdrive.domain.AccountSessionIdentity
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.asCoroutineDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GalleryThumbnailLoaderTest {
  private val firstAccount = AccountSessionIdentity(1L, 1L)
  private val secondAccount = AccountSessionIdentity(2L, 1L)

  @Test
  fun downsampleSampleSizeNeverDecodesAtOriginalCellSize() {
    assertEquals(1, calculateGalleryInSampleSize(200, 200, 200, 200))
    assertEquals(4, calculateGalleryInSampleSize(800, 800, 200, 200))
    assertEquals(8, calculateGalleryInSampleSize(1_600, 1_600, 200, 200))
  }

  @Test
  fun boundedCacheEvictsOldestEntriesAndHonorsByteLimit() {
    val cache = BoundedThumbnailCache<String, String>(
      maxEntries = 2,
      maxBytes = 5L,
      sizeOf = { it.length.toLong() },
    )
    cache.put("one", "12")
    cache.put("two", "34")
    cache.get("one")
    cache.put("three", "56")

    assertEquals(2, cache.entryCountForTests())
    assertEquals(null, cache.get("two"))
    assertEquals("12", cache.get("one"))
    assertEquals("56", cache.get("three"))
    assertTrue(cache.byteCountForTests() <= 5L)
  }

  @Test
  fun duplicateDecodeRequestsAreDeduplicatedOnBackgroundDispatcher() = runBlocking {
    val executor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "gallery-thumbnail-test") }
    val dispatcher = executor.asCoroutineDispatcher()
    val calls = AtomicInteger(0)
    val threadName = AtomicReference<String>()
    val loader = GalleryThumbnailLoader(
      dispatcher = dispatcher,
      decoder = GalleryThumbnailDecoder { _, _, _ ->
        calls.incrementAndGet()
        threadName.set(Thread.currentThread().name)
        delay(25L)
        null
      },
    )
    val source = GalleryThumbnailSource(firstAccount, "thumb-1", null, null)

    val first = async { loader.load(source, 200, 200) }
    val second = async { loader.load(source, 200, 200) }
    first.await()
    second.await()
    loader.close()
    dispatcher.close()
    executor.shutdownNow()

    assertEquals(1, calls.get())
    assertTrue(threadName.get().startsWith("gallery-thumbnail-test"))
  }

  @Test
  fun accountClearRemovesOnlyThatAccountAndClearRemovesAll() {
    val cache = BoundedThumbnailCache<AccountSessionIdentity, String>(10, 100) { it.length.toLong() }
    cache.put(firstAccount, "a")
    cache.put(secondAccount, "b")
    cache.removeIf { it == firstAccount }
    assertEquals(null, cache.get(firstAccount))
    assertEquals("b", cache.get(secondAccount))
    cache.clear()
    assertEquals(0, cache.entryCountForTests())
    assertTrue(secondAccount != firstAccount)
  }

  @Test
  fun cancellationDoesNotPublishAStaleDecodeResult() = runBlocking {
    val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
    val cancelled = AtomicBoolean(false)
    val loader = GalleryThumbnailLoader(
      dispatcher = Dispatchers.Default,
      decoder = GalleryThumbnailDecoder { _, _, _ ->
        try {
          gate.await()
          null
        } finally {
          cancelled.set(true)
        }
      },
    )
    val source = GalleryThumbnailSource(firstAccount, "thumb-cancel", null, "invalid")
    val request = async { loader.load(source, 200, 200) }
    delay(25L)
    request.cancel()
    assertTrue(request.isCancelled)
    delay(25L)
    assertTrue(cancelled.get())
    loader.close()
  }

}
