package com.djransom.crewsync.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import platform.Foundation.NSUserDefaults

class IosSettings : Settings {
    private val defaults = NSUserDefaults.standardUserDefaults
    
    override fun getString(key: String, defaultValue: String): String {
        return defaults.stringForKey(key) ?: defaultValue
    }
    
    override fun putString(key: String, value: String) {
        defaults.setObject(value, forKey = key)
    }
    
    override fun getBoolean(key: String, defaultValue: Boolean): Boolean {
        return if (defaults.objectForKey(key) == null) defaultValue else defaults.boolForKey(key)
    }
    
    override fun putBoolean(key: String, value: Boolean) {
        defaults.setBool(value, forKey = key)
    }
}

@Composable
actual fun rememberSettings(): Settings = remember { IosSettings() }
