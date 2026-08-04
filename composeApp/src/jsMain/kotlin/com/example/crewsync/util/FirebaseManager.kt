package com.example.crewsync.util

import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.FirebaseOptions
import dev.gitlive.firebase.initialize

actual fun initializeFirebase() {
    try {
        // Standard Web Handshake for Browsers
        Firebase.initialize(
            options = FirebaseOptions(
                applicationId = "1:516480819680:web:221b188f2a1a12a2b14b85",
                apiKey = "AIzaSyD7VnuipzkUGy3aQ6Pg0jhIfw24IjjsayI",
                projectId = "gen-lang-client-0438127279",
                storageBucket = "gen-lang-client-0438127279.firebasestorage.app"
            )
        )
    } catch (e: Exception) {
        // Ignore if already initialized
    }
}
