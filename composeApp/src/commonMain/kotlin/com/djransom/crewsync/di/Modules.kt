package com.djransom.crewsync.di

import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.auth.auth
import dev.gitlive.firebase.firestore.firestore
import dev.gitlive.firebase.storage.storage
import org.koin.dsl.module

val appModule = module {
    // These are now 'factory' instead of 'single' to ensure 
    // they fetch the latest state from the initialized Firebase instance.
    factory { Firebase.auth }
    factory { Firebase.firestore }
    factory { Firebase.storage }
}
