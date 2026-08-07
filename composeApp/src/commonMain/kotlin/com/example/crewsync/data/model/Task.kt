package com.example.crewsync.data.model

import kotlinx.serialization.Serializable

@Serializable
data class Task(
    val id: String = "",
    val projectId: String = "",
    val title: String = "",
    val description: String = "",
    val status: String = "Not Started",
    val assignedTo: String? = null,
    val assignedMembers: List<String> = emptyList(),
    val color: String = "#FFFFFF",
    val startDate: Long? = null,
    val dueDate: Long? = null,
    val checklist: List<ChecklistItem> = emptyList(),
    val attachments: List<ProjectFile> = emptyList()
) {
    fun getAllAssignedEmails(): List<String> {
        val list = assignedMembers.toMutableList()
        if (assignedTo != null && assignedTo.isNotBlank() && !list.contains(assignedTo)) {
            list.add(assignedTo)
        }
        return list
    }
}

@Serializable
data class ChecklistItem(
    val id: String = "",
    val text: String = "",
    val isDone: Boolean = false
)
