package com.djransom.crewsync

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import com.djransom.crewsync.util.initializeFirebase
import kotlinx.browser.document

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    try {
        initializeFirebase()
    } catch (_: Throwable) {
    }

    val body = document.body
    if (body != null) {
        ComposeViewport(body) {
            App()
        }
    }
}
