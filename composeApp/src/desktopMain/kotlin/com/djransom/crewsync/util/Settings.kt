package com.djransom.crewsync.util

import androidx.compose.runtime.Composable

@Composable
actual fun rememberSettings(): Settings {
    return DesktopSettings()
}

class DesktopSettings : Settings {
    private val prefs = java.util.prefs.Preferences.userRoot().node("com.djransom.crewsync")
    
    override fun putString(key: String, value: String) {
        prefs.put(key, value)
    }
    
    override fun getString(key: String, defaultValue: String): String {
        return prefs.get(key, defaultValue)
    }

    override fun putBoolean(key: String, value: Boolean) {
        prefs.putBoolean(key, value)
    }

    override fun getBoolean(key: String, defaultValue: Boolean): Boolean {
        return prefs.getBoolean(key, defaultValue)
    }
}
