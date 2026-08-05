package com.nmtuong.telegramdrive.feature.preview

import androidx.media3.datasource.DataSource
import com.nmtuong.telegramdrive.data.MediaAccessCoordinator
import com.nmtuong.telegramdrive.domain.VideoPlaybackRequest

/** Account-scoped source, position, and cleanup operations needed by playback. */
internal interface VideoPlaybackGateway {
  suspend fun loadPosition(request: VideoPlaybackRequest): Long?
  suspend fun resolveLocalPath(request: VideoPlaybackRequest): String?
  fun streamingDataSourceFactory(request: VideoPlaybackRequest): DataSource.Factory
  suspend fun savePosition(request: VideoPlaybackRequest, positionMs: Long, durationMs: Long)
  fun closePlayback(request: VideoPlaybackRequest)
}

internal class MediaAccessVideoPlaybackGateway(
  private val mediaAccess: MediaAccessCoordinator,
) : VideoPlaybackGateway {
  override suspend fun loadPosition(request: VideoPlaybackRequest): Long? =
    mediaAccess.loadPlaybackPosition(request)

  override suspend fun resolveLocalPath(request: VideoPlaybackRequest): String? =
    mediaAccess.resolveVideoLocalPath(request)

  override fun streamingDataSourceFactory(request: VideoPlaybackRequest): DataSource.Factory =
    mediaAccess.videoDataSourceFactory(request)

  override suspend fun savePosition(request: VideoPlaybackRequest, positionMs: Long, durationMs: Long) {
    mediaAccess.savePlaybackPosition(request, positionMs, durationMs)
  }

  override fun closePlayback(request: VideoPlaybackRequest) {
    mediaAccess.closeVideoPlayback(request)
  }
}
