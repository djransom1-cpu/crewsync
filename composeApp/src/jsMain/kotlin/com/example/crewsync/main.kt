package com.example.crewsync

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import com.example.crewsync.util.initializeFirebase
import kotlinx.browser.document

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    try {
        initializeFirebase()
    } catch (_: Throwable) {
    }

    val body = document.body ?: return
    ComposeViewport(body) {
        App()
    }
}
