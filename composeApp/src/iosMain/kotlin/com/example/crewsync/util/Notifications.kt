package com.example.crewsync.util

import platform.UserNotifications.*
import kotlinx.datetime.Clock

actual fun notifyTaskUpdate(title: String, message: String) {
    val content = UNMutableNotificationContent().apply {
        setTitle(title)
        setBody(message)
        setSound(UNNotificationSound.defaultSound)
    }

    val timestamp = Clock.System.now().toEpochMilliseconds()
    val request = UNNotificationRequest.requestWithIdentifier(
        identifier = "task_update_$timestamp",
        content = content,
        trigger = null
    )

    UNUserNotificationCenter.currentNotificationCenter().addNotificationRequest(request) { error ->
        if (error != null) {
            println("IOS NOTIFICATION ERROR: ${error.localizedDescription}")
        }
    }
}

actual fun notifyChatMessage(projectId: String, senderName: String, message: String) {
    val content = UNMutableNotificationContent().apply {
        setTitle("New Message: $projectId")
        setSubtitle("From: $senderName")
        setBody(message)
        setSound(UNNotificationSound.defaultSound)
    }

    val timestamp = Clock.System.now().toEpochMilliseconds()
    val request = UNNotificationRequest.requestWithIdentifier(
        identifier = "chat_$timestamp",
        content = content,
        trigger = null
    )

    UNUserNotificationCenter.currentNotificationCenter().addNotificationRequest(request) { _ -> }
}
