package com.djransom.crewsync.util

import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.initialize

actual fun initializeFirebase() {
    // On iOS, the GitLive Firebase SDK will automatically initialize 
    // using the GoogleService-Info.plist added to the Xcode project.
    // We call initialize() to ensure the Kotlin wrapper is ready.
    try {
        Firebase.initialize()
    } catch (e: Exception) {
        // Safe to ignore if already initialized
    }
}
