package com.example.crewsync.util

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

class AndroidSettings(context: Context) : Settings {
    private val prefs = context.getSharedPreferences("crew_sync_prefs", Context.MODE_PRIVATE)

    override fun getString(key: String, defaultValue: String): String = prefs.getString(key, defaultValue) ?: defaultValue
    override fun putString(key: String, value: String) = prefs.edit().putString(key, value).apply()
    override fun getBoolean(key: String, defaultValue: Boolean): Boolean = prefs.getBoolean(key, defaultValue)
    override fun putBoolean(key: String, value: Boolean) = prefs.edit().putBoolean(key, value).apply()
}

@Composable
actual fun rememberSettings(): Settings {
    val context = LocalContext.current
    return remember { AndroidSettings(context) }
}
