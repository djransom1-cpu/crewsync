package com.example.crewsync.util

import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.FirebaseOptions
import dev.gitlive.firebase.initialize

actual fun initializeFirebase() {
    try {
        Firebase.initialize(
            options = FirebaseOptions(
                applicationId = "gen-lang-client-0438127279",
                apiKey = "AIzaSyD7VnuipzkUGy3aQ6Pg0jhIfw24IjjsayI",
                authDomain = "gen-lang-client-0438127279.firebaseapp.com",
                projectId = "gen-lang-client-0438127279",
                storageBucket = "gen-lang-client-0438127279.firebasestorage.app"
            )
        )
    } catch (_: Throwable) {
        // Ignore if already initialized or running offline
    }
}
