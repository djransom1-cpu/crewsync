package com.djransom.crewsync.data.model

import kotlinx.serialization.Serializable

@Serializable
data class ProjectFolder(
    val id: String = "",
    val projectId: String = "",
    val name: String = "",
    val parentFolderId: String? = null,
    val color: String? = null, // hex string e.g. "#FFC107"; null = default theme color
    val createdAt: Long = 0L
)
