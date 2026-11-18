package com.djransom.crewsync.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.djransom.crewsync.data.model.Appointment
import com.djransom.crewsync.data.model.Contact
import com.djransom.crewsync.data.model.SchedulePhase
import com.djransom.crewsync.data.model.Task
import com.djransom.crewsync.i18n.clockTime
import com.djransom.crewsync.i18n.dayShort
import com.djransom.crewsync.i18n.t
import com.djransom.crewsync.util.WorkCalendar
import com.djransom.crewsync.util.endDate
import com.djransom.crewsync.util.formatPhaseDates
import com.djransom.crewsync.util.formatScheduleDay
import com.djransom.crewsync.util.linkedTasks
import com.djransom.crewsync.util.makePhoneCall
import com.djransom.crewsync.util.millisToLocalDate
import com.djransom.crewsync.util.nextLinkedAppointment
import com.djransom.crewsync.util.percentShown
import com.djransom.crewsync.util.slipDays
import com.djransom.crewsync.util.startDate
import com.djransom.crewsync.util.workingDays
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.toLocalDateTime

/**
 * The read-only card that opens when a phase is tapped, on the timeline or in the list: its
 * dates, status and percent, how it stands against the original plan, who's doing it, the tasks
 * linked to it with their checklist progress, and its next site-calendar appointment.
 */
@Composable
fun PhaseCardDialog(
    phase: SchedulePhase,
    calendar: WorkCalendar,
    tasks: List<Task>,
    appointments: List<Appointment>,
    today: LocalDate,
    zone: TimeZone,
    onDismiss: () -> Unit,
    large: Boolean = false,
    contacts: List<Contact> = emptyList(),
    onEdit: (() -> Unit)? = null,       // null: no Edit button (read-only, or presentation mode)
    onShift: (() -> Unit)? = null       // null: no Shift button
) {
    // On the meeting-room screen the same card is simply drawn bigger: the lettering, the spacing
    // and the dialog itself are all scaled up together.
    val density = LocalDensity.current
    val scale = if (large) PHASE_CARD_LARGE_SCALE else 1f
    CompositionLocalProvider(LocalDensity provides Density(density.density * scale, density.fontScale)) {
        AlertDialog(
            onDismissRequest = onDismiss,
            confirmButton = { TextButton(onClick = onDismiss) { Text(t("Close")) } },
            dismissButton = if (onEdit == null && onShift == null) null else ({
                Row {
                    if (onShift != null) TextButton(onClick = onShift) { Text(t("Shift")) }
                    if (onEdit != null) TextButton(onClick = onEdit) { Text(t("Edit")) }
                }
            }),
            title = { PhaseCardTitle(phase) },
            text = { PhaseCardBody(phase, calendar, tasks, appointments, today, zone, Modifier.verticalScroll(rememberScrollState()), contacts) }
        )
    }
}

/** How much bigger the card is drawn in presentation mode. */
const val PHASE_CARD_LARGE_SCALE = 1.6f

@Composable
fun PhaseCardTitle(phase: SchedulePhase) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (phase.isMilestone) {
            Box(Modifier.padding(end = 12.dp).size(12.dp).rotate(45f).background(phaseStatusColor(phase.status)))
        }
        Text(phase.name.ifBlank { t("(unnamed)") })
    }
}

/** The card's contents on their own, so presentation mode can show the same thing at a bigger size. */
@Composable
fun PhaseCardBody(
    phase: SchedulePhase,
    calendar: WorkCalendar,
    tasks: List<Task>,
    appointments: List<Appointment>,
    today: LocalDate,
    zone: TimeZone,
    modifier: Modifier = Modifier,
    contacts: List<Contact> = emptyList()
) {
    val linked = remember(phase.taskIds, tasks) { phase.linkedTasks(tasks.associateBy { it.id }) }
    val next = remember(phase.appointmentIds, appointments, today, zone) { phase.nextLinkedAppointment(appointments.associateBy { it.id }, today, zone) }
    val percent = phase.percentShown(linked)
    val start = phase.startDate(zone)
    val end = phase.endDate(zone)
    val plannedStart = phase.baselineStart?.let { millisToLocalDate(it, zone) }
    val plannedEnd = phase.baselineEnd?.let { millisToLocalDate(it, zone) }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PhaseStatusPill(phase.status)
            Spacer(Modifier.width(12.dp))
            LinearProgressIndicator(
                progress = { percent / 100f },
                modifier = Modifier.weight(1f).height(8.dp).clip(CircleShape),
                color = phaseStatusColor(phase.status),
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                gapSize = 0.dp,
                drawStopIndicator = {}
            )
            Text("$percent%", style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.End, modifier = Modifier.width(52.dp))
        }

        PhaseFact(t("Dates"), formatPhaseDates(start, end).ifEmpty { t("No dates yet") }, phaseLengthText(phase, calendar, zone))
        // The original plan, shown only when the phase has moved off it.
        if ((plannedStart != null || plannedEnd != null) && (plannedStart != start || plannedEnd != end)) {
            val slip = phase.slipDays(calendar, zone) ?: 0
            PhaseFact(
                t("Planned"), formatPhaseDates(plannedStart, plannedEnd),
                detail = when {
                    slip > 0 -> t("{0} working day(s) behind plan", slip)
                    slip < 0 -> t("{0} working day(s) ahead of plan", -slip)
                    else -> ""
                },
                detailColor = if (slip > 0) MaterialTheme.colorScheme.error else Color(0xFF2E7D32)
            )
        }
        if (phase.trade.isNotBlank()) PhaseFact(t("Trade"), phase.trade)
        if (phase.contactName.isNotBlank()) {
            // A contact picked from the contacts list has a number to hand: show it, with a Call button.
            val phone = phaseContact(phase, contacts)?.let { it.phone.ifBlank { it.secondaryPhone } }.orEmpty()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { PhaseFact(t("Contact"), phase.contactName, detail = phone) }
                if (phone.isNotBlank()) {
                    IconButton(onClick = { makePhoneCall(phone) }) {
                        Icon(Icons.Default.Call, contentDescription = t("Call"), tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }

        HorizontalDivider()
        Text(t("Linked tasks"), style = MaterialTheme.typography.titleSmall)
        if (linked.isEmpty()) {
            Text(t("No linked tasks"), style = MaterialTheme.typography.bodyMedium, color = muted)
        } else {
            linked.forEach { task ->
                val items = task.allChecklistItems()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(task.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(end = 12.dp))
                    // A task with a checklist shows how far through it is; one without shows its bucket.
                    Text(
                        if (items.isEmpty()) t(task.status) else "${items.count { it.isDone }}/${items.size}",
                        style = MaterialTheme.typography.labelLarge, color = muted
                    )
                }
            }
        }

        Text(t("Next appointment"), style = MaterialTheme.typography.titleSmall)
        if (next == null) {
            Text(t("No upcoming appointments"), style = MaterialTheme.typography.bodyMedium, color = muted)
        } else {
            val (appointment, day) = next
            Column {
                Text(appointment.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(appointmentWhen(appointment, day, zone), style = MaterialTheme.typography.bodySmall, color = muted)
            }
        }
    }
}

/** "Dates   Sep 21 – Dec 11, 2026 / 58 working days": a short label, the value, and an optional second line under it. */
@Composable
private fun PhaseFact(label: String, value: String, detail: String = "", detailColor: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Row {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(84.dp).padding(top = 2.dp))
        Column(Modifier.weight(1f)) {
            Text(value, style = MaterialTheme.typography.bodyLarge)
            if (detail.isNotEmpty()) Text(detail, style = MaterialTheme.typography.bodySmall, color = detailColor)
        }
    }
}

/** "Tue Nov 24, 2026 · 9:00 AM", or "· All Day". */
private fun appointmentWhen(appointment: Appointment, day: LocalDate, zone: TimeZone): String {
    val date = "${dayShort(day.dayOfWeek.isoDayNumber)} ${formatScheduleDay(day)}"
    if (appointment.isAllDay) return "$date  ·  ${t("All Day")}"
    val time = Instant.fromEpochMilliseconds(appointment.startDate).toLocalDateTime(zone)
    return "$date  ·  ${clockTime(time.hour, time.minute)}"
}

/** A phase's length in words: "Milestone", "1 working day", "58 working days", or "" when it has no dates or length yet. */
fun phaseLengthText(phase: SchedulePhase, calendar: WorkCalendar, zone: TimeZone): String = when {
    phase.isMilestone -> t("Milestone")
    else -> when (val days = phase.workingDays(calendar, zone)) {
        null -> ""
        1 -> t("1 working day")
        else -> t("{0} working days", days)
    }
}
