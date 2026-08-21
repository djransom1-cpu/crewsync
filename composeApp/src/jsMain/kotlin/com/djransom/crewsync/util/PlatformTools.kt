package com.djransom.crewsync.util

import androidx.compose.runtime.*
import kotlinx.browser.window

import com.djransom.crewsync.data.model.Appointment

actual fun openUrl(url: String) {
    window.open(url, "_blank")
}

actual fun makePhoneCall(phoneNumber: String) {
    window.location.href = "tel:$phoneNumber"
}

actual fun sendEmail(address: String, subject: String, body: String) {
    val mailto = "mailto:$address?subject=${subject.replace(" ", "%20")}&body=${body.replace(" ", "%20")}"
    window.location.href = mailto
}

@Composable
actual fun rememberConnectivityState(): State<Boolean> {
    return remember { mutableStateOf(true) }
}

actual fun getAppVersion(): String = "1.4-Web"

actual fun notifyTaskUpdate(title: String, message: String) {
    try {
        js("""
            if ('Notification' in window) {
                if (Notification.permission === 'granted') {
                    new Notification(title, { body: message });
                } else if (Notification.permission !== 'denied') {
                    Notification.requestPermission().then(function (permission) {
                        if (permission === 'granted') {
                            new Notification(title, { body: message });
                        }
                    });
                }
            }
        """)
    } catch (_: Throwable) {}
}

actual fun notifyChatMessage(projectId: String, senderName: String, message: String) {
    try {
        val notifTitle = "Crew Chat - " + senderName
        val notifBody = message
        js("""
            if ('Notification' in window) {
                if (Notification.permission === 'granted') {
                    new Notification(notifTitle, { body: notifBody });
                } else if (Notification.permission !== 'denied') {
                    Notification.requestPermission().then(function (permission) {
                        if (permission === 'granted') {
                            new Notification(notifTitle, { body: notifBody });
                        }
                    });
                }
            }
        """)
    } catch (_: Throwable) {}
}

actual fun addToExternalCalendar(appointment: Appointment) {
    val url = "https://www.google.com/calendar/render?action=TEMPLATE&text=${appointment.title}&details=${appointment.description}&location=${appointment.location}"
    window.open(url, "_blank")
}
