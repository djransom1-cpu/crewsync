package com.example.crewsync.data.model

import kotlinx.serialization.Serializable

@Serializable
data class Appointment(
    val id: String = "",
    val projectId: String = "",
    val title: String = "",
    val description: String = "",
    val notes: String = "",
    val location: String = "",
    val color: String = "#2196F3", // Default Blue
    val isAllDay: Boolean = false,
    val startDate: Long = 0L, // Timestamp of start (includes time)
    val endDate: Long = 0L,   // Timestamp of end (includes time)
    val recurrence: String = "None", // None, Daily, Weekly, Monthly
    val createdBy: String = ""
)
