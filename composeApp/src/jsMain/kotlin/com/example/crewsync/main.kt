package com.example.crewsync

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.CanvasBasedWindow
import com.example.crewsync.util.initializeFirebase
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLCanvasElement

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    try {
        initializeFirebase()
    } catch (_: Throwable) {
    }

    val canvas = document.getElementById("ComposeTarget") as? HTMLCanvasElement
    if (canvas != null) {
        fun updateCanvasSize() {
            val dpr = window.devicePixelRatio
            canvas.width = (window.innerWidth * dpr).toInt()
            canvas.height = (window.innerHeight * dpr).toInt()
        }
        updateCanvasSize()
        window.addEventListener("resize", { updateCanvasSize() })
        window.addEventListener("orientationchange", { updateCanvasSize() })
    }

    CanvasBasedWindow(title = "Crewsync Web", canvasElementId = "ComposeTarget") {
        App()
    }
}
