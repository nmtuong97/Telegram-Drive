package com.nmtuong.telegramdrive.feature.preview

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.content.Context
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.WindowInsetsCompat

fun toggleOrientation(context: Context) {
    val activity = context.findActivity() ?: return
    val currentOrientation = activity.resources.configuration.orientation
    if (currentOrientation == Configuration.ORIENTATION_LANDSCAPE) {
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
    } else {
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE
    }
}

fun applyFullscreen(context: Context, isFullscreen: Boolean) {
    val activity = context.findActivity() ?: return
    val window = activity.window ?: return
    val insetsController = WindowInsetsControllerCompat(window, window.decorView)

    if (isFullscreen) {
        insetsController.hide(WindowInsetsCompat.Type.systemBars())
        insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    } else {
        insetsController.show(WindowInsetsCompat.Type.systemBars())
    }
}
