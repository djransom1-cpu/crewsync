package com.example.crewsync.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State

@Composable
expect fun rememberConnectivityState(): State<Boolean>
