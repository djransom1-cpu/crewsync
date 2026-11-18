package com.djransom.crewsync.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import kotlinx.browser.document

@Composable
actual fun FullScreenHost(onExit: () -> Unit, content: @Composable () -> Unit) {
    DisposableEffect(Unit) {
        // Best effort: a browser may refuse full-screen (it has to follow a click), and that is fine -
        // presentation mode still fills the page.
        try {
            val request: dynamic = document.documentElement.asDynamic().requestFullscreen()
            if (request != null) request.then({ _: dynamic -> }, { _: dynamic -> })
        } catch (_: Throwable) {
        }
        onDispose {
            try {
                val doc: dynamic = document
                if (doc.fullscreenElement != null) doc.exitFullscreen()
            } catch (_: Throwable) {
            }
        }
    }
    content()
}
