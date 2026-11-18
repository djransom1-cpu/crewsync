package com.djransom.crewsync.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.djransom.crewsync.data.model.Appointment
import com.djransom.crewsync.data.model.SchedulePhase
import com.djransom.crewsync.data.model.Task
import com.djransom.crewsync.i18n.t
import com.djransom.crewsync.util.AppointmentMove
import com.djransom.crewsync.util.PhaseMove
import com.djransom.crewsync.util.TaskMove
import com.djransom.crewsync.util.WorkCalendar
import com.djransom.crewsync.util.appointmentMoves
import com.djransom.crewsync.util.formatPhaseDates
import com.djransom.crewsync.util.shiftedDates
import com.djransom.crewsync.util.taskMoves
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone

/**
 * A change to the schedule that's waiting for the person's OK: the phases that would move (the
 * changed one first), and - when it came from the edit screen - that phase before and after, so
 * its other edited fields are saved in the same go. [skipAppointmentId] is an appointment that
 * has already been moved by hand and mustn't be moved again.
 */
class PendingShift(
    val moves: List<PhaseMove>,
    val originBefore: SchedulePhase? = null,
    val originEdited: SchedulePhase? = null,
    val skipAppointmentId: String? = null
)

/**
 * Shows whatever is waiting: the preview for a [pending] shift, or the message for a [loop] in
 * the predecessors. Nothing is saved until Apply; Cancel throws the change away. Shared by the
 * Schedule tab and the Calendar tab ("Move the phase too?").
 */
@Composable
fun ShiftPreviewHost(
    pending: PendingShift?,
    loop: List<String>?,
    tasks: List<Task>,
    appointments: List<Appointment>,
    calendar: WorkCalendar,
    today: LocalDate,
    zone: TimeZone,
    actions: ScheduleActions,
    onDone: () -> Unit
) {
    if (loop != null) {
        AlertDialog(
            onDismissRequest = onDone,
            title = { Text(t("Can't work out what follows")) },
            text = { Text(t("The predecessors go round in a loop: {0}. Nothing was changed. Take one of them out of the loop, then try again.", loop.joinToString(" → "))) },
            confirmButton = { TextButton(onClick = onDone) { Text(t("OK")) } }
        )
    }
    if (pending != null) {
        val shown = remember(appointments, pending.skipAppointmentId) { appointments.filter { it.id != pending.skipAppointmentId } }
        // Which phases' appointments and task dates go along: appointments by default, tasks only if ticked.
        val moveAppointments = remember(pending) { mutableStateMapOf<String, Boolean>() }
        val moveTasks = remember(pending) { mutableStateMapOf<String, Boolean>() }
        AlertDialog(
            onDismissRequest = onDone,
            title = { Text(t("Review the changes"), fontWeight = FontWeight.Bold) },
            text = {
                ShiftPreviewBody(
                    pending.moves, tasks, shown, calendar, today, zone,
                    appointmentsTicked = { moveAppointments[it] ?: true }, onAppointmentsTicked = { id, on -> moveAppointments[id] = on },
                    tasksTicked = { moveTasks[it] ?: false }, onTasksTicked = { id, on -> moveTasks[id] = on },
                    modifier = Modifier.verticalScroll(rememberScrollState())
                )
            },
            confirmButton = {
                Button(onClick = {
                    val apptById = shown.associateBy { it.id }
                    val taskById = tasks.associateBy { it.id }
                    val appts = pending.moves.filter { moveAppointments[it.phase.id] ?: true }.flatMap { appointmentMoves(it, apptById, today, calendar, zone) }
                    val cards = pending.moves.filter { moveTasks[it.phase.id] ?: false }.flatMap { taskMoves(it, taskById, calendar, zone) }
                    actions.applyShift(pending.moves, appts, cards, pending.originBefore, pending.originEdited)
                    onDone()
                }) { Text(t("Apply")) }
            },
            dismissButton = { TextButton(onClick = onDone) { Text(t("Cancel")) } }
        )
    }
}

/**
 * The preview itself: each phase that moves, from when to when and by how many working days,
 * with a tick box for its linked appointments (on) and one for its dated linked tasks (off).
 */
@Composable
fun ShiftPreviewBody(
    moves: List<PhaseMove>,
    tasks: List<Task>,
    appointments: List<Appointment>,
    calendar: WorkCalendar,
    today: LocalDate,
    zone: TimeZone,
    appointmentsTicked: (phaseId: String) -> Boolean,
    onAppointmentsTicked: (phaseId: String, Boolean) -> Unit,
    tasksTicked: (phaseId: String) -> Boolean,
    onTasksTicked: (phaseId: String, Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val apptById = remember(appointments) { appointments.associateBy { it.id } }
    val taskById = remember(tasks) { tasks.associateBy { it.id } }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            t("{0} phase(s) will move. The baseline stays where it is.", moves.size),
            style = MaterialTheme.typography.bodyMedium, color = muted
        )
        moves.forEach { move ->
            val linkedAppointments = remember(move, apptById, today) { appointmentMoves(move, apptById, today, calendar, zone) }
            val linkedTasks = remember(move, taskById) { taskMoves(move, taskById, calendar, zone) }
            Column {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        move.phase.name.ifBlank { t("(unnamed)") },
                        style = MaterialTheme.typography.bodyLarge, fontWeight = if (move.isOrigin) FontWeight.Bold else FontWeight.Medium,
                        modifier = Modifier.weight(1f).padding(end = 8.dp)
                    )
                    Text(shiftWords(move), style = MaterialTheme.typography.labelLarge, color = if (move.startShift > 0 || move.endShift > 0) MaterialTheme.colorScheme.error else Color(0xFF2E7D32))
                }
                Text(
                    "${formatPhaseDates(move.oldStart, move.oldEnd)}  →  ${formatPhaseDates(move.newStart, move.newEnd)}",
                    style = MaterialTheme.typography.bodySmall, color = muted
                )
                if (linkedAppointments.isNotEmpty()) {
                    TickRow(t("Move its {0} linked appointment(s)", linkedAppointments.size), appointmentsTicked(move.phase.id)) { onAppointmentsTicked(move.phase.id, it) }
                }
                if (linkedTasks.isNotEmpty()) {
                    TickRow(t("Move the dates on its {0} linked task(s)", linkedTasks.size), tasksTicked(move.phase.id)) { onTasksTicked(move.phase.id, it) }
                }
            }
        }
    }
}

@Composable
private fun TickRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { onChange(!checked) }) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

/** "3 working day(s) later", or for a phase that only got longer or shorter, "ends 2 working day(s) later". */
private fun shiftWords(move: PhaseMove): String = when {
    move.startShift > 0 -> t("{0} working day(s) later", move.startShift)
    move.startShift < 0 -> t("{0} working day(s) earlier", -move.startShift)
    move.endShift > 0 -> t("ends {0} working day(s) later", move.endShift)
    move.endShift < 0 -> t("ends {0} working day(s) earlier", -move.endShift)
    else -> ""
}

/**
 * The Shift button's question: how many working days, earlier or later. Shows where the phase
 * would land as the number changes; Preview then shows everything that would follow it.
 */
@Composable
fun ShiftAmountDialog(phase: SchedulePhase, calendar: WorkCalendar, zone: TimeZone, onPreview: (days: Int) -> Unit, onDismiss: () -> Unit) {
    var days by remember { mutableStateOf(1) }
    val landing = shiftedDates(phase, days, calendar, zone)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t("Shift \"{0}\"", phase.name), fontWeight = FontWeight.Bold) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilledTonalButton(onClick = { days -= 1 }) { Text("−", style = MaterialTheme.typography.titleLarge) }
                    Text(
                        when {
                            days > 0 -> t("{0} working day(s) later", days)
                            days < 0 -> t("{0} working day(s) earlier", -days)
                            else -> t("No change")
                        },
                        style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp)
                    )
                    FilledTonalButton(onClick = { days += 1 }) { Text("+", style = MaterialTheme.typography.titleLarge) }
                }
                Spacer(Modifier.height(12.dp))
                if (landing != null) Text(formatPhaseDates(landing.first, landing.second), style = MaterialTheme.typography.bodyLarge)
            }
        },
        confirmButton = { Button(onClick = { onPreview(days) }, enabled = days != 0 && landing != null) { Text(t("Preview")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(t("Cancel")) } }
    )
}
