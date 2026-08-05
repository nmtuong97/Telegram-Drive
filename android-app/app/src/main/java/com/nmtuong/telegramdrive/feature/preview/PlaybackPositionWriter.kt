package com.nmtuong.telegramdrive.feature.preview

import java.io.Closeable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal data class PlaybackPositionSnapshot(
  val positionMs: Long,
  val durationMs: Long,
)

/**
 * Serializes position writes and coalesces pending snapshots so a UI ticker
 * cannot create an unbounded stream of Room jobs.
 */
internal class PlaybackPositionWriter(
  private val scope: CoroutineScope,
  private val onPersisted: () -> Unit = {},
  private val persist: suspend (PlaybackPositionSnapshot) -> Unit,
) : Closeable {
  private val lock = Any()
  private var pending: PlaybackPositionSnapshot? = null
  private var lastPersisted: PlaybackPositionSnapshot? = null
  private var drainJob: Job? = null
  private var closed = false

  fun enqueue(snapshot: PlaybackPositionSnapshot, force: Boolean) {
    synchronized(lock) {
      if (closed) return
      if (!force && (snapshot == pending || snapshot == lastPersisted)) return
      pending = snapshot
      if (drainJob?.isActive != true) {
        drainJob = scope.launch { drain() }
      }
    }
  }

  private suspend fun drain() {
    while (true) {
      val snapshot = synchronized(lock) {
        pending.also { pending = null }
      } ?: break
      try {
        persist(snapshot)
        synchronized(lock) { lastPersisted = snapshot }
        onPersisted()
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        // A failed Room operation must not strand later snapshots or start an
        // unbounded retry loop. A future enqueue can attempt persistence again.
      }
    }

    synchronized(lock) {
      drainJob = null
      if (pending != null) {
        drainJob = scope.launch { drain() }
      }
    }
  }

  override fun close() {
    synchronized(lock) {
      closed = true
      if (pending != null && drainJob?.isActive != true) {
        drainJob = scope.launch { drain() }
      }
    }
  }
}
