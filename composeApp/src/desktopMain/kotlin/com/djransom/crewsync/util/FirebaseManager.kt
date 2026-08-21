package com.djransom.crewsync.util

import android.app.Application
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.FirebaseOptions
import dev.gitlive.firebase.initialize

// GitLive's JVM/Desktop target for firebase-auth/firestore/storage runs on top of
// dev.gitlive:firebase-java-sdk, a pure-Java port of the Android Firebase SDK that is
// pulled in transitively by those libraries' jvm variant. That port ships its own
// android.app.Application / android.content.Context shim classes, and the SDK's own
// documented desktop bootstrap is simply Firebase.initialize(context = Application(), options).
//
// The previous implementation here hand-built a fake FirebaseApp via sun.misc.Unsafe +
// reflection. That's unnecessary (and fragile: jpackage's default jlink runtime image
// only bundles JDK modules it detects via static bytecode analysis, and a
// Class.forName("sun.misc.Unsafe") string reference is invisible to that analysis, so
// jdk.unsupported silently isn't included in the packaged .exe/.msi -> the whole
// reflective block throws, is swallowed by the catch, and nothing ever registers a
// FirebaseApp -> every later Firebase.auth/firestore call fails with
// "Default FirebaseApp is not initialized in this process null.").
actual fun initializeFirebase() {
    try {
        Firebase.initialize(
            context = Application(),
            options = FirebaseOptions(
                applicationId = "1:516480819680:web:221b188f2a1a12a2b14b85",
                apiKey = "AIzaSyD7VnuipzkUGy3aQ6Pg0jhIfw24IjjsayI",
                projectId = "gen-lang-client-0438127279",
                storageBucket = "gen-lang-client-0438127279.firebasestorage.app"
            )
        )
        println("DESKTOP: GitLive Firebase initialized successfully.")
    } catch (e: Throwable) {
        if (e.message?.contains("already exists") != true) {
            println("FIREBASE INIT NOTE: ${e.message}")
        }
    }
}
