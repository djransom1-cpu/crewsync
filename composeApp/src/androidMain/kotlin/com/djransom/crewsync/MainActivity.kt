package com.djransom.crewsync

import android.os.Bundle
import android.os.Build
import android.Manifest
import androidx.activity.enableEdgeToEdge
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.djransom.crewsync.util.ContextHolder
import com.google.firebase.appdistribution.FirebaseAppDistribution
import com.google.firebase.messaging.FirebaseMessaging
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.auth.auth
import dev.gitlive.firebase.firestore.firestore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        
        // FORCED HANDSHAKE: This fixes the "null" error on Samsung DeX
        try {
            val options = FirebaseOptions.Builder()
                .setApplicationId("1:516480819680:android:221b188f2a1a12a2b14b85")
                .setApiKey("AIzaSyD7VnuipzkUGy3aQ6Pg0jhIfw24IjjsayI")
                .setProjectId("gen-lang-client-0438127279")
                .setStorageBucket("gen-lang-client-0438127279.firebasestorage.app")
                .build()
            
            if (FirebaseApp.getApps(this).isEmpty()) {
                FirebaseApp.initializeApp(this, options)
            }
        } catch (e: Exception) {
            android.util.Log.e("Crewsync", "Manual Init Failed: ${e.message}")
        }

        ContextHolder.context = applicationContext
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        try {
            FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    val token = task.result
                    saveFcmToken(token)
                }
            }
        } catch (e: Exception) {}
        
        try {
            FirebaseAppDistribution.getInstance().updateIfNewReleaseAvailable()
        } catch (e: Exception) {}

        setContent {
            App()
        }
    }

    private fun saveFcmToken(token: String) {
        CoroutineScope(Dispatchers.IO).launch {
            val auth = Firebase.auth
            val firestore = Firebase.firestore
            auth.currentUser?.uid?.let { uid ->
                try {
                    firestore.collection("users").document(uid).update("fcmToken" to token)
                } catch (e: Exception) {}
            }
        }
    }
}
