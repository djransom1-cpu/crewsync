package com.djransom.crewsync.data.model

import kotlinx.serialization.Serializable

@Serializable
data class Broadcast(
    val id: String = "",
    val projectId: String = "", // Added for per-project alerts
    val senderName: String = "",
    val title: String = "",
    val message: String = "",
    val timestamp: Long = 0L
)
