package com.djransom.crewsync.util

import com.djransom.crewsync.data.model.Appointment
import kotlinx.cinterop.ExperimentalForeignApi
import platform.EventKit.EKEvent
import platform.EventKit.EKEventStore
import platform.EventKit.EKEntityType
import platform.EventKit.EKSpan
import platform.Foundation.NSDate
import platform.Foundation.dateWithTimeIntervalSince1970

@OptIn(ExperimentalForeignApi::class)
actual fun addToExternalCalendar(appointment: Appointment) {
    val eventStore = EKEventStore()
    eventStore.requestAccessToEntityType(EKEntityType.EKEntityTypeEvent) { granted, error ->
        if (granted && error == null) {
            val event = EKEvent.eventWithEventStore(eventStore).apply {
                title = appointment.title
                notes = appointment.description
                location = appointment.location
                startDate = NSDate.dateWithTimeIntervalSince1970(appointment.startDate / 1000.0)
                endDate = NSDate.dateWithTimeIntervalSince1970(appointment.endDate / 1000.0)
                calendar = eventStore.defaultCalendarForNewEvents
            }

            eventStore.saveEvent(event, EKSpan.EKSpanThisEvent, true, null)
        }
    }
}
