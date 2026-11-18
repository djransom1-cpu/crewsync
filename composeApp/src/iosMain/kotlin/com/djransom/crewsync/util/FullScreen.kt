package com.djransom.crewsync.util

import androidx.compose.runtime.Composable

/** Nothing to take over on iOS: the app already fills the screen, and rotation is left to the person. */
@Composable
actual fun FullScreenHost(onExit: () -> Unit, content: @Composable () -> Unit) {
    content()
}
