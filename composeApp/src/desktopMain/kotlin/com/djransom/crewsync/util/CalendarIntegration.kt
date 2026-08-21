package com.djransom.crewsync.util

import com.djransom.crewsync.data.model.Appointment

actual fun addToExternalCalendar(appointment: Appointment) {
    // Open Google Calendar web URL as fallback for Desktop
    val url = "https://www.google.com/calendar/render?action=TEMPLATE&text=${appointment.title}&details=${appointment.description}&location=${appointment.location}"
    openUrl(url)
}
