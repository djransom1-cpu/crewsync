package com.djransom.crewsync

import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.djransom.crewsync.util.initializeFirebase

/**
 * The app's own icon for the title bar and taskbar (the installer's icon is icon.ico, set in
 * composeApp/build.gradle.kts). Without it Windows shows the generic Java/Compose icon.
 */
private val appIcon: Painter? = runCatching {
    Thread.currentThread().contextClassLoader.getResourceAsStream("icon.png")?.use { s ->
        BitmapPainter(org.jetbrains.skia.Image.makeFromEncoded(s.readBytes()).toComposeImageBitmap())
    }
}.getOrNull()

fun main() {
    // 1. Force the handshake before the window even starts
    initializeFirebase()
    
    try {
        application {
            Window(
                onCloseRequest = ::exitApplication,
                title = "Crewsync",
                icon = appIcon,
            ) {
                // The main app logic is now protected inside initializeFirebase()
                App()
            }
        }
    } catch (e: Exception) {
        // Log the error to the console for us to see
        System.err.println("CRITICAL STARTUP ERROR: ${e.message}")
    }
}
