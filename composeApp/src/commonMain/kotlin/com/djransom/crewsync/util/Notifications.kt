package com.djransom.crewsync.util

expect fun notifyTaskUpdate(title: String, message: String)

expect fun notifyChatMessage(projectId: String, senderName: String, message: String)
