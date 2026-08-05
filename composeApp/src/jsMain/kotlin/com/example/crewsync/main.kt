package com.example.crewsync

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.CanvasBasedWindow
import com.example.crewsync.util.initializeFirebase

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    try {
        initializeFirebase()
    } catch (_: Throwable) {
    }

    CanvasBasedWindow(title = "Crewsync Web", canvasElementId = "ComposeTarget") {
        App()
    }
}
