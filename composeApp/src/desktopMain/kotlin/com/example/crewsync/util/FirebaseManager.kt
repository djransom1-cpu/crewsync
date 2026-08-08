package com.example.crewsync.util

import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.FirebaseOptions
import dev.gitlive.firebase.initialize

actual fun initializeFirebase() {
    try {
        // Reflective Android Context Proxy for Desktop JVM
        try {
            val contextClass = Class.forName("android.content.Context")
            val dummyContext = java.lang.reflect.Proxy.newProxyInstance(
                contextClass.classLoader,
                arrayOf(contextClass)
            ) { _, method, _ ->
                when (method.name) {
                    "getApplicationContext" -> null
                    "getPackageName" -> "com.example.crewsync"
                    else -> null
                }
            }

            val firebaseAppClass = Class.forName("com.google.firebase.FirebaseApp")
            val firebaseOptionsBuilderClass = Class.forName("com.google.firebase.FirebaseOptions\$Builder")
            val builderObj = firebaseOptionsBuilderClass.getDeclaredConstructor().newInstance()

            firebaseOptionsBuilderClass.getMethod("setApplicationId", String::class.java).invoke(builderObj, "1:516480819680:web:221b188f2a1a12a2b14b85")
            firebaseOptionsBuilderClass.getMethod("setApiKey", String::class.java).invoke(builderObj, "AIzaSyD7VnuipzkUGy3aQ6Pg0jhIfw24IjjsayI")
            firebaseOptionsBuilderClass.getMethod("setProjectId", String::class.java).invoke(builderObj, "gen-lang-client-0438127279")
            firebaseOptionsBuilderClass.getMethod("setStorageBucket", String::class.java).invoke(builderObj, "gen-lang-client-0438127279.firebasestorage.app")

            val optionsObj = firebaseOptionsBuilderClass.getMethod("build").invoke(builderObj)
            val initMethod = firebaseAppClass.getMethod("initializeApp", contextClass, optionsObj.javaClass)
            initMethod.invoke(null, dummyContext, optionsObj)
            println("DESKTOP: Reflective FirebaseApp default instance initialized.")
        } catch (e: Throwable) {
            println("Reflective context init note: ${e.message}")
        }

        Firebase.initialize(
            options = FirebaseOptions(
                applicationId = "1:516480819680:web:221b188f2a1a12a2b14b85",
                apiKey = "AIzaSyD7VnuipzkUGy3aQ6Pg0jhIfw24IjjsayI",
                projectId = "gen-lang-client-0438127279",
                storageBucket = "gen-lang-client-0438127279.firebasestorage.app"
            )
        )
        println("DESKTOP: Connection established successfully.")
    } catch (e: Throwable) {
        if (e.message?.contains("already exists") == false) {
            System.err.println("FIREBASE STARTUP ERROR: ${e.message}")
        }
    }
}
