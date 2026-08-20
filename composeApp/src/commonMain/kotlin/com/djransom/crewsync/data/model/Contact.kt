package com.example.crewsync.data.model

import kotlinx.serialization.Serializable

@Serializable
data class Contact(
    val id: String = "",
    val name: String = "",
    val jobTitle: String = "",
    val company: String = "",
    val email: String = "",
    val secondaryEmail: String = "",
    val phone: String = "",
    val secondaryPhone: String = "",
    val address: String = "",
    val notes: String = "",
    val type: String = "Company" // Company or Subcontractor
)
