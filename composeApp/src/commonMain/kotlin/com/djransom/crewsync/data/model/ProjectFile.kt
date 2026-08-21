package com.djransom.crewsync.data.model

import kotlinx.serialization.Serializable

@Serializable
data class ProjectFile(
    val id: String = "",
    val name: String = "",
    val url: String = "",
    val folderId: String? = null,
    val uploadedBy: String = "",
    val uploadedAt: Long = 0L
)
