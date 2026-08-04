package com.example.crewsync.util

import androidx.compose.runtime.Composable

@Composable
expect fun rememberContactPickerLauncher(onContactPicked: (PickedContact) -> Unit): () -> Unit

data class PickedContact(
    val name: String,
    val phone: String,
    val email: String
)
