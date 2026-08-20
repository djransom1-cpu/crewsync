package com.example.crewsync.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import com.example.crewsync.MainActivity
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.auth.auth
import dev.gitlive.firebase.firestore.firestore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock

private const val ALERT_CHANNEL_ID = "crewsync_alerts"
private const val CHAT_CHANNEL_ID = "crewsync_chat"
private const val KEY_TEXT_REPLY = "key_text_reply"

fun showInAppNotification(context: Context, title: String, message: String) {
    val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val channel = NotificationChannel(ALERT_CHANNEL_ID, "Project Alerts", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Critical site updates and project alerts"
            enableLights(true)
            enableVibration(true)
        }
        notificationManager.createNotificationChannel(channel)
    }

    val intent = Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
    }
    val pendingIntent = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    val notification = NotificationCompat.Builder(context, ALERT_CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_dialog_alert)
        .setContentTitle(title)
        .setContentText(message)
        .setStyle(NotificationCompat.BigTextStyle().bigText(message))
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setCategory(NotificationCompat.CATEGORY_ALARM)
        .setContentIntent(pendingIntent)
        .setAutoCancel(true)
        .build()

    notificationManager.notify(Clock.System.now().toEpochMilliseconds().toInt(), notification)
}

fun showChatNotification(context: Context, projectId: String, senderName: String, message: String) {
    val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val channel = NotificationChannel(CHAT_CHANNEL_ID, "Crew Chat", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "New messages from your crew"
        }
        notificationManager.createNotificationChannel(channel)
    }

    val remoteInput = RemoteInput.Builder(KEY_TEXT_REPLY).setLabel("Reply...").build()
    val replyIntent = Intent(context, ChatReplyReceiver::class.java).apply {
        putExtra("projectId", projectId)
    }
    
    val replyPendingIntent = PendingIntent.getBroadcast(
        context,
        projectId.hashCode(),
        replyIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
    )

    val action = NotificationCompat.Action.Builder(
        android.R.drawable.ic_menu_send,
        "Reply",
        replyPendingIntent
    ).addRemoteInput(remoteInput).build()

    val intent = Intent(context, MainActivity::class.java)
    val pendingIntent = PendingIntent.getActivity(context, 1, intent, PendingIntent.FLAG_IMMUTABLE)

    val notification = NotificationCompat.Builder(context, CHAT_CHANNEL_ID)
        .setSmallIcon(android.R.drawable.stat_notify_chat)
        .setContentTitle(senderName)
        .setContentText(message)
        .setSubText("Project: $projectId")
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setCategory(NotificationCompat.CATEGORY_MESSAGE)
        .setContentIntent(pendingIntent)
        .addAction(action)
        .setAutoCancel(true)
        .build()

    notificationManager.notify(projectId.hashCode(), notification)
}

class ChatReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent == null) return
        val results = RemoteInput.getResultsFromIntent(intent) ?: return
        val replyText = results.getCharSequence(KEY_TEXT_REPLY)?.toString() ?: return
        val projectId = intent.getStringExtra("projectId") ?: return

        CoroutineScope(Dispatchers.IO).launch {
            val auth = Firebase.auth
            val firestore = Firebase.firestore
            val user = auth.currentUser
            if (user != null) {
                val msg = mapOf(
                    "senderId" to user.uid,
                    "senderEmail" to (user.email ?: "Anonymous"),
                    "text" to replyText,
                    "timestamp" to Clock.System.now().toEpochMilliseconds()
                )
                firestore.collection("projects").document(projectId).collection("messages").add(msg)
                val notificationManager = context?.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                notificationManager.cancel(projectId.hashCode())
            }
        }
    }
}
