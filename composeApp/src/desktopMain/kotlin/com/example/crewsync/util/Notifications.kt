package com.example.crewsync.util

actual fun notifyTaskUpdate(title: String, message: String) {
    println("NOTIFICATION: $title - $message")
}

actual fun notifyChatMessage(projectId: String, senderName: String, message: String) {
    println("CHAT NOTIFICATION: [$projectId] $senderName: $message")
}
