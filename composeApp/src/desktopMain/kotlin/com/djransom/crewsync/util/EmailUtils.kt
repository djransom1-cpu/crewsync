package com.djransom.crewsync.util

import java.awt.Desktop
import java.net.URI

actual fun sendEmail(address: String, subject: String, body: String) {
    if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.MAIL)) {
        val mailto = "mailto:$address?subject=${subject.replace(" ", "%20")}&body=${body.replace(" ", "%20")}"
        Desktop.getDesktop().mail(URI(mailto))
    }
}
