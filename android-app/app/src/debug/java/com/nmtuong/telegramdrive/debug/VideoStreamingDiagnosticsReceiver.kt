package com.nmtuong.telegramdrive.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.nmtuong.telegramdrive.data.video.VideoStreamingDiagnostics
import com.nmtuong.telegramdrive.data.video.toDebugLogLine

/** Debug-only ADB entry point; it never emits media, account, identity, or path data. */
class VideoStreamingDiagnosticsReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    when (intent.action) {
      ACTION_RESET -> {
        VideoStreamingDiagnostics.resetForDebugScenario()
        Log.i(TAG, "event=reset ${VideoStreamingDiagnostics.snapshot().toDebugLogLine()}")
      }
      ACTION_DUMP -> Log.i(TAG, "event=snapshot ${VideoStreamingDiagnostics.snapshot().toDebugLogLine()}")
    }
  }

  companion object {
    const val ACTION_DUMP = "com.nmtuong.telegramdrive.debug.action.DUMP_VIDEO_STREAMING_DIAGNOSTICS"
    const val ACTION_RESET = "com.nmtuong.telegramdrive.debug.action.RESET_VIDEO_STREAMING_DIAGNOSTICS"
    private const val TAG = "VideoStreamDiagnostics"
  }
}
