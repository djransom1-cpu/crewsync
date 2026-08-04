package com.example.crewsync

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.example.crewsync.util.initializeFirebase

fun main() {
    // 1. Force the handshake before the window even starts
    initializeFirebase()
    
    try {
        application {
            Window(
                onCloseRequest = ::exitApplication,
                title = "Crewsync",
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
