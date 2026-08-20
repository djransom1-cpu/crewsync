package com.example.crewsync.util

import android.content.Intent
import android.net.Uri

actual fun makePhoneCall(phoneNumber: String) {
    val context = ContextHolder.context ?: return
    try {
        val intent = Intent(Intent.ACTION_DIAL).apply {
            data = Uri.parse("tel:$phoneNumber")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        // Handle error
    }
}
