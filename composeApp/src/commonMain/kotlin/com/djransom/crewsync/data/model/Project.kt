package com.djransom.crewsync.data.model

import kotlinx.serialization.Serializable

@Serializable
data class Project(
    val id: String = "",
    val environmentId: String = "", // which workspace this project belongs to - see Environment.kt
    val name: String = "",
    val description: String = "",
    val teamLeaderId: String = "",
    val members: List<String> = emptyList(),
    val location: String = "", // Job-site address, used for the dashboard's weather card
    val ownerName: String = "", // Property owner / client contact
    val ownerPhone: String = "",
    val ownerEmail: String = "",
    val buckets: List<String> = listOf("Not Started", "In Progress", "Paused", "Done"),
    val cardOrder: List<String> = listOf("Weather", "Calendar", "Tasks", "Chat", "Team", "Files", "Notes", "Daily Log"),
    val cardSizes: Map<String, String> = emptyMap(), // cardId to "Small" or "Large"
    val calendarShareEnabled: Boolean = false,
    val calendarShareToken: String = "", // the calendarFeed Cloud Function's only access control - see ShareUtils.kt
    val createdAt: Long = 0L,
    // Archived projects drop off the dashboard (see "Show archived") but keep everything in them.
    // Only the project's creator (teamLeaderId) or an admin is offered Archive/Restore and Delete.
    val archived: Boolean = false,
    // The Schedule tab's working calendar (see ScheduleDates.kt): days nobody works, as
    // "YYYY-MM-DD", and the working week as ISO day numbers (1 = Monday ... 7 = Sunday).
    // Like buckets, these are only ever changed with a targeted update(), never a whole-document
    // save, so a device on an older build can't wipe them.
    val scheduleHolidays: List<String> = emptyList(),
    val workWeek: List<Int> = listOf(1, 2, 3, 4, 5)
)
