package com.djransom.crewsync.util

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.auth.auth
import dev.gitlive.firebase.firestore.firestore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class CrewsyncMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d("CrewsyncFCM", "New token: $token")
        
        // Save token to Firestore if user is logged in
        CoroutineScope(Dispatchers.IO).launch {
            val auth = Firebase.auth
            val firestore = Firebase.firestore
            auth.currentUser?.uid?.let { uid ->
                try {
                    firestore.collection("users").document(uid).update("fcmToken" to token)
                } catch (e: Exception) {
                    Log.e("CrewsyncFCM", "Error updating token", e)
                }
            }
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)

        val title = message.notification?.title ?: message.data["title"] ?: "Crewsync"
        val body = message.notification?.body ?: message.data["body"] ?: ""

        if (message.data["type"] == "chat") {
            val projectId = message.data["projectId"] ?: ""
            showChatNotification(applicationContext, projectId, title, body)
        } else {
            showInAppNotification(applicationContext, title, body)
        }
    }
}
