package com.djransom.crewsync.util

import android.app.Application
import com.google.firebase.FirebasePlatform
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.FirebaseOptions
import dev.gitlive.firebase.initialize
import java.io.File
import java.util.Properties

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
//
// That FirebaseApp registration alone isn't enough, though: the java-sdk's internal
// android.content.Context shim (used for the auth token cache etc.) reads/writes through
// a separate FirebasePlatform.firebasePlatform singleton that Firebase.initialize() never
// touches. Skipping this step doesn't fail loudly here - it fails later, the first time
// something actually logs or persists a value, as
// "FirebasePlatform.firebasePlatform is null".
private class DesktopFirebasePlatform : FirebasePlatform() {
    private val storeFile = File(System.getProperty("user.home"), ".crewsync/firebase-store.properties").apply {
        parentFile?.mkdirs()
    }
    private val props = Properties().apply {
        if (storeFile.exists()) storeFile.inputStream().use { load(it) }
    }

    override fun store(key: String, value: String) {
        props.setProperty(key, value)
        storeFile.outputStream().use { props.store(it, null) }
    }

    override fun retrieve(key: String): String? = props.getProperty(key)

    override fun clear(key: String) {
        props.remove(key)
        storeFile.outputStream().use { props.store(it, null) }
    }

    override fun log(msg: String) {
        println("FIREBASE: $msg")
    }
}

actual fun initializeFirebase() {
    try {
        FirebasePlatform.initializeFirebasePlatform(DesktopFirebasePlatform())
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
