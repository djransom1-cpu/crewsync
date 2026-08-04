package com.example.crewsync.util

import androidx.compose.runtime.Composable

@Composable
expect fun rememberSettings(): Settings

interface Settings {
    fun getString(key: String, defaultValue: String): String
    fun putString(key: String, value: String)
    fun getBoolean(key: String, defaultValue: Boolean): Boolean
    fun putBoolean(key: String, value: Boolean)
}
