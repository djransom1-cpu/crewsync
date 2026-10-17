package com.djransom.crewsync.util

import com.djransom.crewsync.i18n.t
import android.content.Intent
import android.provider.CalendarContract
import com.djransom.crewsync.data.model.Appointment

actual fun addToExternalCalendar(appointment: Appointment) {
    val context = ContextHolder.context ?: return
    
    val intent = Intent(Intent.ACTION_INSERT)
        .setData(CalendarContract.Events.CONTENT_URI)
        .putExtra(CalendarContract.Events.TITLE, appointment.title)
        .putExtra(CalendarContract.Events.DESCRIPTION, appointment.description + t("\n\nNotes: {0}", appointment.notes))
        .putExtra(CalendarContract.Events.EVENT_LOCATION, appointment.location)
        .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, appointment.startDate)
        .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, appointment.endDate)
        .putExtra(CalendarContract.Events.ALL_DAY, appointment.isAllDay)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    // Handle basic recurrence (Daily/Weekly/Monthly)
    val rrule = when (appointment.recurrence) {
        "Daily" -> "FREQ=DAILY"
        "Weekly" -> "FREQ=WEEKLY"
        "Monthly" -> "FREQ=MONTHLY"
        else -> null
    }
    if (rrule != null) {
        intent.putExtra(CalendarContract.Events.RRULE, rrule)
    }

    context.startActivity(intent)
}
