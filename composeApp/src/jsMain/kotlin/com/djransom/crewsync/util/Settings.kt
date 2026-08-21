package com.djransom.crewsync.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.browser.localStorage

class WebSettings : Settings {
    override fun getString(key: String, defaultValue: String): String {
        return localStorage.getItem(key) ?: defaultValue
    }
    override fun putString(key: String, value: String) {
        localStorage.setItem(key, value)
    }
    override fun getBoolean(key: String, defaultValue: Boolean): Boolean {
        return localStorage.getItem(key)?.toBoolean() ?: defaultValue
    }
    override fun putBoolean(key: String, value: Boolean) {
        localStorage.setItem(key, value.toString())
    }
}

@Composable
actual fun rememberSettings(): Settings = remember { WebSettings() }
