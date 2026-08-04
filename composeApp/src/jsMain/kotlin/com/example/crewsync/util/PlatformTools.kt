package com.example.crewsync.util

import androidx.compose.runtime.*
import kotlinx.browser.window

import com.example.crewsync.data.model.Appointment

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
    return remember { mutableStateOf(true) } // Browser usually has consistent state
}

actual fun getAppVersion(): String = "1.3-Web"

actual fun notifyTaskUpdate(title: String, message: String) {
    // Browser notification placeholder
}

actual fun notifyChatMessage(projectId: String, senderName: String, message: String) {
    // Browser notification placeholder
}

actual fun addToExternalCalendar(appointment: Appointment) {
    val url = "https://www.google.com/calendar/render?action=TEMPLATE&text=${appointment.title}&details=${appointment.description}&location=${appointment.location}"
    window.open(url, "_blank")
}
