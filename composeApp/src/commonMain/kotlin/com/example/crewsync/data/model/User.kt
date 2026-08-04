package com.example.crewsync.data.model

import kotlinx.serialization.Serializable

@Serializable
data class User(
    val uid: String = "",
    val email: String = "",
    val name: String = "",
    val phone: String = "",
    val trade: String = "", // e.g., Carpenter, Electrician, Plumber
    val role: String = "Member", // SuperAdmin, Admin, or Member
    val profilePictureUrl: String? = null,
    val fcmToken: String? = null,
    val projectOrder: List<String> = emptyList(),
    val dashboardViewMode: String = "Cards" // "Cards" or "List"
)
