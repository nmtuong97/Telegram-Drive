package com.nmtuong.telegramdrive.feature.preview

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.content.Context

fun toggleOrientation(context: Context) {
    val activity = context.findActivity() ?: return
    val currentOrientation = activity.resources.configuration.orientation
    if (currentOrientation == Configuration.ORIENTATION_LANDSCAPE) {
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
    } else {
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE
    }
}
