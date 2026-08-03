package com.nmtuong.telegramdrive.feature.preview

import androidx.media3.common.PlaybackException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoPlaybackRulesTest {
  @Test
  fun shortSavedPositionStartsFromBeginning() {
    assertEquals(0L, resumePositionMs(29_999L, 120_000L))
  }

  @Test
  fun midVideoSavedPositionIsRestored() {
    assertEquals(45_000L, resumePositionMs(45_000L, 120_000L))
  }

  @Test
  fun nearEndSavedPositionStartsFromBeginning() {
    assertEquals(0L, resumePositionMs(114_000L, 120_000L))
  }

  @Test
  fun unknownDurationKeepsMeaningfulSavedPosition() {
    assertEquals(45_000L, resumePositionMs(45_000L, 0L))
  }

  @Test
  fun mapsNetworkFailureToOfflineRecovery() {
    assertEquals(
      VideoPlaybackErrorKind.Offline,
      classifyVideoPlaybackFailure(
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        "network connection failed",
      ),
    )
    assertTrue(isRetryableVideoPlaybackError(VideoPlaybackErrorKind.Offline))
  }

  @Test
  fun mapsExpiredSessionToFatalAccountAction() {
    assertEquals(
      VideoPlaybackErrorKind.TelegramSessionChanged,
      classifyVideoPlaybackFailure(
        PlaybackException.ERROR_CODE_AUTHENTICATION_EXPIRED,
        "authentication expired",
      ),
    )
    assertFalse(isRetryableVideoPlaybackError(VideoPlaybackErrorKind.TelegramSessionChanged))
  }

  @Test
  fun mapsUnsupportedDecoderToFatalError() {
    assertEquals(
      VideoPlaybackErrorKind.UnsupportedFormatOrDecoder,
      classifyVideoPlaybackFailure(
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        "decoder format unsupported",
      ),
    )
    assertFalse(isRetryableVideoPlaybackError(VideoPlaybackErrorKind.UnsupportedFormatOrDecoder))
  }

  @Test
  fun mapsNestedTimeoutCauseWhenTopLevelMessageIsGeneric() {
    val nested = java.net.SocketTimeoutException("remote read timed out")
    val topLevel = IllegalStateException("Playback failed", nested)

    assertEquals(
      VideoPlaybackErrorKind.TimeoutOrSlowNetwork,
      classifyVideoPlaybackFailure(PlaybackException.ERROR_CODE_UNSPECIFIED, "Playback failed", topLevel),
    )
  }

  @Test
  fun mapsNestedSessionChangeCauseWithoutExposingRawCause() {
    val nested = IllegalStateException("Telegram account session changed")
    val topLevel = IllegalStateException("Playback failed", nested)

    assertEquals(
      VideoPlaybackErrorKind.TelegramSessionChanged,
      classifyVideoPlaybackFailure(PlaybackException.ERROR_CODE_UNSPECIFIED, "Playback failed", topLevel),
    )
  }

  @Test
  fun navigationCancellationIsNotAPlaybackError() {
    val cancellation = java.util.concurrent.CancellationException("Video range superseded")

    assertTrue(isExpectedVideoPlaybackCancellation(cancellation))
  }
}
