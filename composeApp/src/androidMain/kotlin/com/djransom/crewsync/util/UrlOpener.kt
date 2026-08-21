package com.djransom.crewsync.util

import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent

actual fun openUrl(url: String) {
    val context = ContextHolder.context ?: return
    try {
        val lowerUrl = url.lowercase()
        // If it's a known native file type, try to trigger a more specific intent
        if (lowerUrl.contains(".pdf") || lowerUrl.contains(".jpg") || lowerUrl.contains(".png")) {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(Uri.parse(url), if (lowerUrl.contains(".pdf")) "application/pdf" else "image/*")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(Intent.createChooser(intent, "Open File With").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            return
        }

        // Use Android Custom Tabs for everything else
        val builder = CustomTabsIntent.Builder()
        builder.setShowTitle(true)
        builder.setInstantAppsEnabled(true)
        
        val customTabsIntent = builder.build()
        customTabsIntent.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        customTabsIntent.launchUrl(context, Uri.parse(url))
        
    } catch (e: Exception) {
        // Fallback to regular Browser if Custom Tabs fails
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e2: Exception) {}
    }
}
