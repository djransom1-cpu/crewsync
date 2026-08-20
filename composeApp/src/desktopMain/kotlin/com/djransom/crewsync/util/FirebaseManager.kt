package com.example.crewsync.util

import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.FirebaseOptions
import dev.gitlive.firebase.initialize

actual fun initializeFirebase() {
    try {
        val contextClass = try {
            Class.forName("android.test.mock.MockContext")
        } catch (_: Throwable) {
            Class.forName("android.content.ContextWrapper")
        }

        val firebaseAppClass = Class.forName("com.google.firebase.FirebaseApp")
        val firebaseOptionsBuilderClass = Class.forName("com.google.firebase.FirebaseOptions\$Builder")

        // 1. Build FirebaseOptions
        val builderObj = firebaseOptionsBuilderClass.getDeclaredConstructor().newInstance()
        firebaseOptionsBuilderClass.getMethod("setApplicationId", String::class.java).invoke(builderObj, "1:516480819680:web:221b188f2a1a12a2b14b85")
        firebaseOptionsBuilderClass.getMethod("setApiKey", String::class.java).invoke(builderObj, "AIzaSyD7VnuipzkUGy3aQ6Pg0jhIfw24IjjsayI")
        firebaseOptionsBuilderClass.getMethod("setProjectId", String::class.java).invoke(builderObj, "gen-lang-client-0438127279")
        firebaseOptionsBuilderClass.getMethod("setStorageBucket", String::class.java).invoke(builderObj, "gen-lang-client-0438127279.firebasestorage.app")
        val optionsObj = firebaseOptionsBuilderClass.getMethod("build").invoke(builderObj)

        // 2. Unsafe allocate dummy context & app
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val unsafeField = unsafeClass.getDeclaredField("theUnsafe")
        unsafeField.isAccessible = true
        val unsafe = unsafeField.get(null)
        val allocateInstanceMethod = unsafeClass.getMethod("allocateInstance", Class::class.java)

        val dummyContext = allocateInstanceMethod.invoke(unsafe, contextClass)
        val appInstance = allocateInstanceMethod.invoke(unsafe, firebaseAppClass)

        // Populate non-static instance fields of FirebaseApp
        for (field in firebaseAppClass.declaredFields) {
            if (!java.lang.reflect.Modifier.isStatic(field.modifiers)) {
                field.isAccessible = true
                if (field.type == String::class.java) {
                    field.set(appInstance, "[DEFAULT]")
                } else if (field.type.name.contains("FirebaseOptions")) {
                    field.set(appInstance, optionsObj)
                } else if (field.type.name.contains("Context")) {
                    field.set(appInstance, dummyContext)
                }
            }
        }

        // 3. Store appInstance in static INSTANCES map
        for (field in firebaseAppClass.declaredFields) {
            if (java.lang.reflect.Modifier.isStatic(field.modifiers) && java.util.Map::class.java.isAssignableFrom(field.type)) {
                field.isAccessible = true
                val map = field.get(null) as? MutableMap<String, Any>
                if (map != null) {
                    map["[DEFAULT]"] = appInstance
                    println("DESKTOP: Registered [DEFAULT] FirebaseApp instance successfully.")
                }
            }
        }
    } catch (e: Throwable) {
        println("Reflective FirebaseApp init note: ${e.message}")
    }

    try {
        Firebase.initialize(
            options = FirebaseOptions(
                applicationId = "1:516480819680:web:221b188f2a1a12a2b14b85",
                apiKey = "AIzaSyD7VnuipzkUGy3aQ6Pg0jhIfw24IjjsayI",
                projectId = "gen-lang-client-0438127279",
                storageBucket = "gen-lang-client-0438127279.firebasestorage.app"
            )
        )
        println("DESKTOP: GitLive Firebase initialized successfully.")
    } catch (e: Throwable) {
        if (e.message?.contains("already exists") == false) {
            println("FIREBASE INIT NOTE: ${e.message}")
        }
    }
}
