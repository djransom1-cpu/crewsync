package com.example.crewsync

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import com.example.crewsync.util.initializeFirebase
import kotlinx.browser.document
import kotlinx.browser.window

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    fun launchApp() {
        val body = document.body ?: return
        try {
            initializeFirebase()
        } catch (_: Throwable) {
        }
        ComposeViewport(body) {
            App()
        }
    }

    if (document.body != null) {
        launchApp()
    } else {
        window.addEventListener("DOMContentLoaded", { launchApp() })
    }
}
