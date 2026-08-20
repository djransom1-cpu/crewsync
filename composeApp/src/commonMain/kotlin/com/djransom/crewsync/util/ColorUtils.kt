package com.example.crewsync.util

import androidx.compose.ui.graphics.Color

fun parseColor(hex: String): Long {
    return try {
        hex.removePrefix("#").toLong(16) or 0xFF000000L
    } catch (e: Exception) {
        0xFFFFFFFFL
    }
}

fun isDarkColor(color: Color): Boolean {
    val luminance = 0.299 * color.red + 0.587 * color.green + 0.114 * color.blue
    return luminance < 0.5
}
