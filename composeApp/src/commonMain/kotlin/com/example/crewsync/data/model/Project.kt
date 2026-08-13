package com.example.crewsync.data.model

import kotlinx.serialization.Serializable

@Serializable
data class Project(
    val id: String = "",
    val name: String = "",
    val description: String = "",
    val teamLeaderId: String = "",
    val members: List<String> = emptyList(),
    val location: String = "", // Job-site address, used for the dashboard's weather card
    val ownerName: String = "", // Property owner / client contact
    val ownerPhone: String = "",
    val ownerEmail: String = "",
    val buckets: List<String> = listOf("Not Started", "In Progress", "Paused", "Done"),
    val cardOrder: List<String> = listOf("Weather", "Calendar", "Tasks", "Chat", "Team", "Files"),
    val cardSizes: Map<String, String> = emptyMap(), // cardId to "Small" or "Large"
    val createdAt: Long = 0L
)
