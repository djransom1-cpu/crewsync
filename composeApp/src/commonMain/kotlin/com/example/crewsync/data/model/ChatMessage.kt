package com.example.crewsync.data.model

import kotlinx.serialization.Serializable

@Serializable
data class ChatMessage(
    val id: String = "",
    val senderId: String = "",
    val senderEmail: String = "",
    val text: String = "",
    val attachmentUrl: String? = null,
    val attachmentName: String? = null,
    val timestamp: Long = 0L
)
