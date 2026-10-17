package com.djransom.crewsync.util

import com.djransom.crewsync.i18n.t
import android.content.Intent
import android.net.Uri

actual fun sendEmail(address: String, subject: String, body: String) {
    val context = ContextHolder.context ?: return
    try {
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("mailto:$address")
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, body)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(intent, t("Send Email...")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    } catch (e: Exception) {
        // Handle error
    }
}
