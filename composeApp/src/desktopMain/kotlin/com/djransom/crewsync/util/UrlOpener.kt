package com.djransom.crewsync.util

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.awt.Desktop
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.net.URI
import java.net.URLEncoder

actual fun openUrl(url: String) {
    // Firebase Storage download URLs for known file types get handed to the OS's own default
    // app for that type (PDF viewer, image viewer, etc.) instead of the browser - Desktop.browse
    // on a raw storage URL just hands off to the system browser, and browsers generally save
    // Firebase Storage's octet-stream response as a download rather than displaying it inline,
    // so "Open" was silently behaving like "Download". Mirrors how the Android actual already
    // special-cases these extensions via an explicit ACTION_VIEW mime type instead of a browser.
    val lowerUrl = url.lowercase()
    val ext = when {
        lowerUrl.contains(".pdf") -> "pdf"
        lowerUrl.contains(".png") -> "png"
        lowerUrl.contains(".jpeg") -> "jpeg"
        lowerUrl.contains(".jpg") -> "jpg"
        else -> null
    }
    if (ext != null) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val client = OkHttpClient()
                val request = Request.Builder().url(url).build()
                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    val tempFile = File.createTempFile("crewsync_open_", ".$ext")
                    tempFile.deleteOnExit()
                    response.body?.byteStream()?.use { input ->
                        tempFile.outputStream().use { output -> input.copyTo(output) }
                    }
                    if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                        Desktop.getDesktop().open(tempFile)
                    }
                }
            } catch (_: Exception) {}
        }
        return
    }

    if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
        Desktop.getDesktop().browse(URI(url))
    }
}

actual fun shareFile(url: String, name: String) {
    val desktop = if (Desktop.isDesktopSupported()) Desktop.getDesktop() else return
    try {
        if (desktop.isSupported(Desktop.Action.MAIL)) {
            val subject = URLEncoder.encode(name, "UTF-8").replace("+", "%20")
            val body = URLEncoder.encode("Here's the file: $url", "UTF-8").replace("+", "%20")
            desktop.mail(URI("mailto:?subject=$subject&body=$body"))
        } else if (desktop.isSupported(Desktop.Action.BROWSE)) {
            desktop.browse(URI(url))
        }
    } catch (_: Exception) {}
}

actual fun downloadFile(url: String, suggestedName: String) {
    val fileDialog = FileDialog(null as Frame?, "Save File", FileDialog.SAVE)
    fileDialog.file = suggestedName
    fileDialog.isVisible = true
    val dir = fileDialog.directory ?: return
    val name = fileDialog.file ?: return
    val destFile = File(dir, name)

    CoroutineScope(Dispatchers.IO).launch {
        try {
            val client = OkHttpClient()
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                destFile.outputStream().use { output ->
                    response.body?.byteStream()?.use { input -> input.copyTo(output) }
                }
                // Reveal the saved file so there's a visible confirmation it worked.
                if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE_FILE_DIR)) {
                    Desktop.getDesktop().browseFileDirectory(destFile)
                } else if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                    Desktop.getDesktop().open(destFile.parentFile)
                }
            }
        } catch (_: Exception) {}
    }
}
