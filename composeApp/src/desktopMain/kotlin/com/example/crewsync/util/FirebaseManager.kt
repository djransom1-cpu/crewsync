package com.example.crewsync.util

import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.FirebaseOptions
import dev.gitlive.firebase.initialize

actual fun initializeFirebase() {
    try {
        // For Windows Desktop, we perform a manual handshake.
        // This avoids the 'Context' error because it doesn't look for a phone environment.
        Firebase.initialize(
            options = FirebaseOptions(
                applicationId = "1:516480819680:web:221b188f2a1a12a2b14b85",
                apiKey = "AIzaSyD7VnuipzkUGy3aQ6Pg0jhIfw24IjjsayI",
                projectId = "gen-lang-client-0438127279",
                storageBucket = "gen-lang-client-0438127279.firebasestorage.app"
            )
        )
        println("DESKTOP: Connection established successfully.")
    } catch (e: Exception) {
        // Log errors to the Windows console
        if (e.message?.contains("already exists") == false) {
            System.err.println("FIREBASE STARTUP ERROR: ${e.message}")
        }
    }
}
