package com.example.crewsync.util

import androidx.compose.runtime.Composable

@Composable
actual fun rememberContactPickerLauncher(onContactPicked: (PickedContact) -> Unit): () -> Unit {
    return {
        // Phone contact picker not available on Desktop
    }
}
