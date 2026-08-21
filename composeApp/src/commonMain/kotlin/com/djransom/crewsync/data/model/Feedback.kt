package com.djransom.crewsync.data.model

import kotlinx.serialization.Serializable

@Serializable
data class Feedback(
    val id: String = "",
    val userId: String = "",
    val userEmail: String = "",
    val title: String = "",
    val description: String = "",
    val appVersion: String = "",
    val timestamp: Long = 0L
)
