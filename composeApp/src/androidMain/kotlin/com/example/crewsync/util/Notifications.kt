package com.example.crewsync.util

actual fun notifyTaskUpdate(title: String, message: String) {
    ContextHolder.context?.let { ctx ->
        showInAppNotification(ctx, title, message)
    }
}

actual fun notifyChatMessage(projectId: String, senderName: String, message: String) {
    ContextHolder.context?.let { ctx ->
        showChatNotification(ctx, projectId, senderName, message)
    }
}
