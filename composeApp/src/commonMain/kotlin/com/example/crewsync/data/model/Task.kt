package com.example.crewsync.data.model

import kotlinx.serialization.Serializable

@Serializable
data class Task(
    val id: String = "",
    val projectId: String = "",
    val title: String = "",
    val description: String = "",
    val status: String = "Not Started",
    val assignedTo: String? = null, // Email of the assigned member
    val color: String = "#FFFFFF", // Hex color for the card
    val startDate: Long? = null,
    val dueDate: Long? = null,
    val checklist: List<ChecklistItem> = emptyList(),
    val attachments: List<ProjectFile> = emptyList()
)

@Serializable
data class ChecklistItem(
    val id: String = "",
    val text: String = "",
    val isDone: Boolean = false
)
