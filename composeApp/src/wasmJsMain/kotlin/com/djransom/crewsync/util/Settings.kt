package com.djransom.crewsync.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember

@Composable
actual fun rememberDataStore(): State<Boolean> {
    return remember { mutableStateOf(true) }
}
