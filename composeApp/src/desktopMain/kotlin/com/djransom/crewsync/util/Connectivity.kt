package com.example.crewsync.util

import androidx.compose.runtime.*

@Composable
actual fun rememberConnectivityState(): State<Boolean> {
    return remember { mutableStateOf(true) } // Placeholder for Desktop
}
