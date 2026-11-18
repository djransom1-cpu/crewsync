package com.djransom.crewsync.util

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

@Composable
actual fun FullScreenHost(onExit: () -> Unit, content: @Composable () -> Unit) {
    BackHandler(onBack = onExit)
    val activity = LocalContext.current.findActivity()
    DisposableEffect(activity) {
        val before = activity?.requestedOrientation
        val bars = activity?.window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        bars?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        bars?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            // Turning to landscape rebuilds the Activity, which disposes this once on the way.
            // That is not the person leaving presentation mode, so leave everything as it is.
            if (activity != null && !activity.isChangingConfigurations) {
                bars?.show(WindowInsetsCompat.Type.systemBars())
                activity.requestedOrientation = before ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }
    }
    content()
}

private fun Context.findActivity(): Activity? {
    var context: Context? = this
    while (context is ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    return null
}
