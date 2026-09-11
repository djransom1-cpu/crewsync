package com.djransom.crewsync.data.model

import kotlinx.serialization.Serializable

@Serializable
data class Note(
    val id: String = "",
    val title: String = "",
    val color: String = "#FFF9C4", // sticky-note background tint shown on the gallery card
    val createdBy: String = "",
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L
)
