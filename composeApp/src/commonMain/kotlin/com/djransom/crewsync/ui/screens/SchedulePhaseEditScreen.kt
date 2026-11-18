package com.djransom.crewsync.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.djransom.crewsync.data.model.Appointment
import com.djransom.crewsync.data.model.Contact
import com.djransom.crewsync.data.model.PercentMode
import com.djransom.crewsync.data.model.PhasePredecessor
import com.djransom.crewsync.data.model.PhaseStatus
import com.djransom.crewsync.data.model.PredecessorType
import com.djransom.crewsync.data.model.SchedulePhase
import com.djransom.crewsync.data.model.Task
import com.djransom.crewsync.i18n.t
import com.djransom.crewsync.util.PhaseDatesEdit
import com.djransom.crewsync.util.WorkCalendar
import com.djransom.crewsync.util.blockedPredecessorIds
import com.djransom.crewsync.util.datesForEdit
import com.djransom.crewsync.util.formatScheduleDay
import com.djransom.crewsync.util.inScheduleOrder
import com.djransom.crewsync.util.linkedTasks
import com.djransom.crewsync.util.makePhoneCall
import com.djransom.crewsync.util.millisToLocalDate
import com.djransom.crewsync.util.nextOccurrence
import com.djransom.crewsync.util.percentShown
import com.djransom.crewsync.util.phaseOfTask
import com.djransom.crewsync.util.toLocalMidnightMillis
import com.djransom.crewsync.util.withDates
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone

/**
 * The edit screen for a phase (or, with [phase] null, for a new one): name, group, dates and
 * working days, status, percent, predecessors, trade and contact, notes - and, once the phase
 * exists, the tasks and site-calendar appointments linked to it.
 *
 * The fields are a draft until Save. Links are different: linking or unlinking a task or a visit
 * happens straight away, because each is its own small change to the phase and has nothing to do
 * with whatever is half-typed in the fields above.
 */
@Composable
fun PhaseEditDialog(
    phase: SchedulePhase?,
    allPhases: List<SchedulePhase>,
    tasks: List<Task>,
    appointments: List<Appointment>,
    contacts: List<Contact>,
    calendar: WorkCalendar,
    projectId: String,
    today: LocalDate,
    zone: TimeZone,
    actions: ScheduleActions,
    onDismiss: () -> Unit
) {
    // The phase as it was when editing began: only what differs from this is saved.
    val original = remember(phase?.id) { phase }
    var draft by remember(phase?.id) { mutableStateOf(phase ?: SchedulePhase()) }
    // Links are read live, so a task linked a moment ago shows up without closing the dialog.
    val live = allPhases.firstOrNull { it.id == original?.id }
    var showAddTask by remember { mutableStateOf(false) }
    var showAddVisit by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val dates = draft.datesForEdit(zone)
    val canSave = draft.name.isNotBlank() && !dates.endsBeforeStart

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (original == null) t("New phase") else t("Edit phase"), fontWeight = FontWeight.Bold) },
        text = {
            PhaseEditForm(
                draft = draft, onDraft = { draft = it },
                live = live, allPhases = allPhases, tasks = tasks, appointments = appointments, contacts = contacts,
                calendar = calendar, today = today, zone = zone,
                onUnlinkTask = { actions.setTaskPhase(it, null) },
                onAddTask = { showAddTask = true },
                onUnlinkAppointment = { actions.setAppointmentPhase(it, null) },
                onAddVisit = { showAddVisit = true },
                onDelete = if (original != null) ({ confirmDelete = true }) else null,
                modifier = Modifier.verticalScroll(rememberScrollState())
            )
        },
        confirmButton = {
            Button(onClick = { actions.savePhase(original, draft); onDismiss() }, enabled = canSave) { Text(t("Save")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(t("Cancel")) } }
    )

    if (showAddTask && live != null) {
        LinkTaskDialog(
            phase = live, allPhases = allPhases, tasks = tasks,
            onLink = { actions.setTaskPhase(it, live.id) },
            onCreate = { actions.createLinkedTask(it, live.id) },
            onDismiss = { showAddTask = false }
        )
    }
    if (showAddVisit && live != null) {
        // The Calendar tab's own appointment dialog, so a visit made here is no different from one made there.
        AppointmentDialog(
            projectId = projectId, appointment = null,
            initialDate = dates.start?.takeIf { it >= today } ?: today,
            onDismiss = { showAddVisit = false },
            onConfirm = { appointment ->
                showAddVisit = false
                actions.createLinkedAppointment(appointment, live.id)
            }
        )
    }
    if (confirmDelete && original != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(t("Delete this phase?")) },
            text = { Text(t("\"{0}\" will be removed from the schedule. Its tasks and appointments are kept.", original.name)) },
            confirmButton = {
                Button(
                    onClick = { confirmDelete = false; actions.deletePhase(original); onDismiss() },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text(t("Delete")) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(t("Cancel")) } }
        )
    }
}

/**
 * The form inside [PhaseEditDialog]. It owns no data: [draft] in, [onDraft] out, and a callback
 * for each thing that happens straight away - which also lets a screenshot test draw it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhaseEditForm(
    draft: SchedulePhase,
    onDraft: (SchedulePhase) -> Unit,
    live: SchedulePhase?,                 // the saved phase, for its links; null while it's new
    allPhases: List<SchedulePhase>,
    tasks: List<Task>,
    appointments: List<Appointment>,
    contacts: List<Contact>,
    calendar: WorkCalendar,
    today: LocalDate,
    zone: TimeZone,
    onUnlinkTask: (String) -> Unit,
    onAddTask: () -> Unit,
    onUnlinkAppointment: (String) -> Unit,
    onAddVisit: () -> Unit,
    onDelete: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    val dates = draft.datesForEdit(zone, calendar)
    fun setDates(next: PhaseDatesEdit) = onDraft(draft.withDates(next, zone))
    val tasksById = remember(tasks) { tasks.associateBy { it.id } }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val words = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, autoCorrectEnabled = true)

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
            value = draft.name, onValueChange = { onDraft(draft.copy(name = it)) },
            label = { Text(t("Phase name")) }, singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = words
        )

        // ---- group: free text, with the groups already in use one tap away ----
        OutlinedTextField(
            value = draft.group, onValueChange = { onDraft(draft.copy(group = it)) },
            label = { Text(t("Group")) }, singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = words
        )
        val groups = remember(allPhases) { allPhases.inScheduleOrder().map { it.group.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() } }
        if (groups.isNotEmpty()) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                groups.forEach { g ->
                    FilterChip(selected = g.equals(draft.group.trim(), ignoreCase = true), onClick = { onDraft(draft.copy(group = g)) }, label = { Text(g) })
                }
            }
        }

        // ---- dates and working days ----
        PhaseDatesEditor(dates, calendar, onChange = ::setDates)

        // ---- status ----
        FormLabel(t("Status"))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PhaseStatus.all.forEach { s ->
                FilterChip(selected = draft.status == s, onClick = { onDraft(draft.copy(status = s)) }, label = { Text(t(s)) })
            }
        }

        // ---- percent: worked out from the linked tasks, or typed in ----
        FormLabel(t("Progress"))
        val manual = draft.percentMode == PercentMode.MANUAL
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = !manual, onClick = { onDraft(draft.copy(percentMode = PercentMode.AUTO)) }, label = { Text(t("Auto")) })
            FilterChip(selected = manual, onClick = { onDraft(draft.copy(percentMode = PercentMode.MANUAL)) }, label = { Text(t("Manual")) })
            if (!manual) {
                val auto = draft.copy(taskIds = live?.taskIds ?: emptyList()).percentShown((live ?: draft).linkedTasks(tasksById))
                Text(t("From linked tasks: {0}%", auto), style = MaterialTheme.typography.bodyMedium, color = muted, modifier = Modifier.padding(start = 6.dp))
            }
        }
        if (manual) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Slider(
                    value = draft.percentComplete.toFloat(), onValueChange = { onDraft(draft.copy(percentComplete = (it / 5f).toInt() * 5)) },
                    valueRange = 0f..100f, modifier = Modifier.weight(1f)
                )
                Text("${draft.percentComplete}%", style = MaterialTheme.typography.titleSmall, modifier = Modifier.width(52.dp).padding(start = 8.dp))
            }
        }

        // ---- predecessors ----
        PredecessorsEditor(draft, allPhases, onChange = { onDraft(draft.copy(predecessors = it)) })

        // ---- who ----
        OutlinedTextField(
            value = draft.trade, onValueChange = { onDraft(draft.copy(trade = it)) },
            label = { Text(t("Trade")) }, singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = words
        )
        PhaseContactEditor(draft, contacts, onChange = onDraft)

        OutlinedTextField(
            value = draft.notes, onValueChange = { onDraft(draft.copy(notes = it)) },
            label = { Text(t("Notes")) }, minLines = 2, maxLines = 6, modifier = Modifier.fillMaxWidth(), keyboardOptions = words
        )

        // ---- links: only once the phase exists, since they're stored on it ----
        HorizontalDivider()
        if (live == null) {
            Text(t("Save the phase first to link tasks and visits."), style = MaterialTheme.typography.bodyMedium, color = muted)
        } else {
            FormLabel(t("Linked tasks"))
            val linked = live.linkedTasks(tasksById)
            if (linked.isEmpty()) Text(t("No linked tasks"), style = MaterialTheme.typography.bodyMedium, color = muted)
            linked.forEach { task ->
                val items = task.allChecklistItems()
                LinkRow(
                    title = task.title,
                    detail = if (items.isEmpty()) t(task.status) else "${items.count { it.isDone }}/${items.size}",
                    onRemove = { onUnlinkTask(task.id) }
                )
            }
            OutlinedButton(onClick = onAddTask) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(t("Add task"))
            }

            FormLabel(t("Linked appointments"))
            val visits = remember(live.appointmentIds, appointments) { live.appointmentIds.mapNotNull { id -> appointments.firstOrNull { it.id == id } } }
            if (visits.isEmpty()) Text(t("No linked appointments"), style = MaterialTheme.typography.bodyMedium, color = muted)
            visits.forEach { visit ->
                LinkRow(
                    title = visit.title,
                    detail = visit.nextOccurrence(today, zone)?.let { formatScheduleDay(it) } ?: "",
                    onRemove = { onUnlinkAppointment(visit.id) }
                )
            }
            OutlinedButton(onClick = onAddVisit) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(t("Add visit"))
            }
        }

        if (onDelete != null) {
            HorizontalDivider()
            TextButton(onClick = onDelete, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text(t("Delete phase")) }
        }
    }
}

@Composable
private fun FormLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
}

/** One linked task or appointment: its name, a short note on the right, and an X that unlinks it (the thing itself is kept). */
@Composable
private fun LinkRow(title: String, detail: String, onRemove: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(end = 8.dp))
        Text(detail, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        IconButton(onClick = onRemove, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Default.Close, contentDescription = t("Unlink"), modifier = Modifier.size(18.dp))
        }
    }
}

/**
 * Start, end and working days, kept in step by [PhaseDatesEdit]: a new start moves the phase, a
 * new end stretches it, a new length moves the end. A milestone shows one date.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PhaseDatesEditor(dates: PhaseDatesEdit, calendar: WorkCalendar, onChange: (PhaseDatesEdit) -> Unit) {
    var picking by remember { mutableStateOf<String?>(null) } // "start" | "end" | null
    // What's typed in the working-days box, kept as text so it can be empty while someone is mid-edit.
    var lengthText by remember(dates.durationDays) { mutableStateOf(if (dates.durationDays > 0) dates.durationDays.toString() else "") }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(t("Milestone (a one-day marker)"), modifier = Modifier.weight(1f))
        Switch(checked = dates.isMilestone, onCheckedChange = { onChange(dates.withMilestone(it, calendar)) })
    }
    if (dates.isMilestone) {
        Button(onClick = { picking = "start" }, modifier = Modifier.fillMaxWidth()) {
            Text(dates.start?.let { t("Date: {0}", formatScheduleDay(it)) } ?: t("Set Date"))
        }
    } else {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { picking = "start" }, modifier = Modifier.weight(1f)) {
                Text(dates.start?.let { t("Start: {0}", formatScheduleDay(it)) } ?: t("Set Start Date"))
            }
            Button(onClick = { picking = "end" }, modifier = Modifier.weight(1f)) {
                Text(dates.end?.let { t("End: {0}", formatScheduleDay(it)) } ?: t("Set End Date"))
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = lengthText,
                onValueChange = { typed ->
                    lengthText = typed.filter { it.isDigit() }.take(4)
                    lengthText.toIntOrNull()?.takeIf { it >= 1 }?.let { onChange(dates.withDuration(it, calendar)) }
                },
                label = { Text(t("Working days")) }, singleLine = true, modifier = Modifier.width(150.dp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
            if (dates.start != null || dates.end != null) TextButton(onClick = { onChange(dates.cleared()) }) { Text(t("Clear dates")) }
        }
    }
    if (dates.endsBeforeStart) Text(t("The end is before the start."), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)

    picking?.let { which ->
        val shown = (if (which == "end") dates.end ?: dates.start else dates.start ?: dates.end)
        // The picker speaks UTC-midnight millis whatever the device's zone (see toPickerMillis in
        // PlannerScreen), so a calendar day goes in and comes out through UTC, never the local zone.
        val state = rememberDatePickerState(initialSelectedDateMillis = shown?.toLocalMidnightMillis(TimeZone.UTC))
        DatePickerDialog(
            onDismissRequest = { picking = null },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { picked ->
                        val day = millisToLocalDate(picked, TimeZone.UTC)
                        onChange(if (which == "end") dates.withEnd(day, calendar) else dates.withStart(day, calendar))
                    }
                    picking = null
                }) { Text(t("OK")) }
            },
            dismissButton = { TextButton(onClick = { picking = null }) { Text(t("Cancel")) } }
        ) { DatePicker(state = state) }
    }
}

/**
 * The phases this one waits for. Each has a kind - it starts after the other FINISHES, or when
 * the other STARTS - and a lag in working days, which may be negative for an overlap. Phases
 * that would make a loop aren't offered.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PredecessorsEditor(draft: SchedulePhase, allPhases: List<SchedulePhase>, onChange: (List<PhasePredecessor>) -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    val blocked = remember(allPhases, draft.id) { blockedPredecessorIds(allPhases, draft.id) }
    val chosen = draft.predecessors.map { it.phaseId }.toSet()
    val candidates = remember(allPhases, blocked, chosen) { allPhases.inScheduleOrder().filter { it.id !in blocked && it.id !in chosen && it.id.isNotEmpty() } }
    fun nameOf(id: String) = allPhases.firstOrNull { it.id == id }?.name?.ifBlank { null } ?: t("(unnamed)")

    FormLabel(t("Predecessors"))
    draft.predecessors.forEach { link ->
        fun replace(next: PhasePredecessor) = onChange(draft.predecessors.map { if (it.phaseId == link.phaseId) next else it })
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(nameOf(link.phaseId), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                // The lag sits up here beside the name so both its buttons are always on screen.
                LagStepper(link.lagDays, onChange = { replace(link.copy(lagDays = it)) })
                IconButton(onClick = { onChange(draft.predecessors.filter { it.phaseId != link.phaseId }) }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.Close, contentDescription = t("Remove"), modifier = Modifier.size(18.dp))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                FilterChip(selected = link.type != PredecessorType.SS, onClick = { replace(link.copy(type = PredecessorType.FS)) }, label = { Text(t("Starts after it finishes")) })
                FilterChip(selected = link.type == PredecessorType.SS, onClick = { replace(link.copy(type = PredecessorType.SS)) }, label = { Text(t("Starts when it starts")) })
            }
        }
    }
    Box {
        OutlinedButton(onClick = { menuOpen = true }, enabled = candidates.isNotEmpty()) {
            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(if (candidates.isEmpty() && draft.predecessors.isEmpty()) t("No other phases to wait for") else t("Add predecessor"))
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            candidates.forEach { other ->
                DropdownMenuItem(
                    text = { Text(other.name.ifBlank { t("(unnamed)") }) },
                    onClick = { menuOpen = false; onChange(draft.predecessors + PhasePredecessor(other.id)) }
                )
            }
        }
    }
}

/** Lag in working days: minus and plus either side of the number, since it's usually 0, 1 or -1. */
@Composable
private fun LagStepper(lagDays: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { onChange(lagDays - 1) }, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(36.dp)) { Text("−", style = MaterialTheme.typography.titleMedium) }
        Text(
            if (lagDays > 0) t("Lag +{0}", lagDays) else t("Lag {0}", lagDays),
            style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 4.dp)
        )
        TextButton(onClick = { onChange(lagDays + 1) }, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(36.dp)) { Text("+", style = MaterialTheme.typography.titleMedium) }
    }
}

/**
 * Who's doing the phase: picked from the workspace's contacts, or just typed. A picked contact
 * keeps its id (so a phone number is to hand - the Call button); a typed name is kept as text.
 */
@Composable
private fun PhaseContactEditor(draft: SchedulePhase, contacts: List<Contact>, onChange: (SchedulePhase) -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    val sorted = remember(contacts) { contacts.filter { it.name.isNotBlank() }.sortedBy { it.name.lowercase() } }
    val picked = phaseContact(draft, contacts)
    val phone = picked?.let { it.phone.ifBlank { it.secondaryPhone } }.orEmpty()

    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) {
            OutlinedTextField(
                value = draft.contactName,
                // Typing a name by hand lets go of any picked contact: it may no longer be the same person.
                onValueChange = { typed -> onChange(draft.copy(contactName = typed, contactId = if (typed == picked?.name) draft.contactId else "")) },
                label = { Text(t("Contact")) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                trailingIcon = {
                    if (sorted.isNotEmpty()) IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.Person, contentDescription = t("Pick a contact")) }
                }
            )
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                // What's typed so far narrows the list; nothing typed shows everyone.
                val typed = draft.contactName.trim()
                val matches = sorted.filter { typed.isEmpty() || picked != null || it.name.contains(typed, ignoreCase = true) || it.company.contains(typed, ignoreCase = true) }
                (matches.ifEmpty { sorted }).take(40).forEach { contact ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(contact.name)
                                val sub = listOf(contact.jobTitle, contact.company).filter { it.isNotBlank() && it != contact.name }.joinToString(" · ")
                                if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        },
                        onClick = { menuOpen = false; onChange(draft.copy(contactId = contact.id, contactName = contact.name)) }
                    )
                }
            }
        }
        if (phone.isNotBlank()) {
            IconButton(onClick = { makePhoneCall(phone) }) { Icon(Icons.Default.Call, contentDescription = t("Call"), tint = MaterialTheme.colorScheme.primary) }
        }
    }
}

/** The contact a phase points at, if it was picked from the contacts list and is still there. */
fun phaseContact(phase: SchedulePhase, contacts: List<Contact>): Contact? =
    if (phase.contactId.isBlank()) null else contacts.firstOrNull { it.id == phase.contactId }

/**
 * "Add task" on a phase: pick one of the project's task cards to link, or type a title to make a
 * new card that's linked from the start. A card that's on another phase says so - picking it
 * moves it here.
 */
@Composable
fun LinkTaskDialog(
    phase: SchedulePhase,
    allPhases: List<SchedulePhase>,
    tasks: List<Task>,
    onLink: (taskId: String) -> Unit,
    onCreate: (title: String) -> Unit,
    onDismiss: () -> Unit
) {
    var search by remember { mutableStateOf("") }
    var newTitle by remember { mutableStateOf("") }
    val available = remember(tasks, phase.taskIds, search) {
        tasks.filter { it.id !in phase.taskIds && (search.isBlank() || it.title.contains(search.trim(), ignoreCase = true)) }
            .sortedBy { it.title.lowercase() }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t("Add task"), fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                FormLabel(t("New task"))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newTitle, onValueChange = { newTitle = it }, label = { Text(t("Title")) }, singleLine = true, modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences)
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { onCreate(newTitle); newTitle = "" }, enabled = newTitle.isNotBlank()) { Text(t("Add")) }
                }
                HorizontalDivider()
                FormLabel(t("Link a task"))
                OutlinedTextField(value = search, onValueChange = { search = it }, label = { Text(t("Search tasks")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (available.isEmpty()) Text(t("No task cards yet."), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                available.take(60).forEach { task ->
                    val elsewhere = phaseOfTask(allPhases, task.id)
                    Column(Modifier.fillMaxWidth().clickable { onLink(task.id) }.padding(vertical = 8.dp)) {
                        Text(task.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOfNotNull(t(task.status), elsewhere?.let { t("On {0}", it.name) }).joinToString("  ·  "),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (elsewhere != null) Color(0xFFEF6C00) else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(t("Close")) } }
    )
}

/**
 * The Phase chip for the task and appointment screens: shows which phase the thing is on and
 * lets it be changed, to another phase or to none. Draws nothing for a project with no schedule.
 */
@Composable
fun PhaseChip(phases: List<SchedulePhase>, selectedPhaseId: String?, onSelect: (String?) -> Unit, modifier: Modifier = Modifier) {
    if (phases.isEmpty()) return
    var menuOpen by remember { mutableStateOf(false) }
    val ordered = remember(phases) { phases.inScheduleOrder() }
    val current = ordered.firstOrNull { it.id == selectedPhaseId }
    Box(modifier) {
        AssistChip(
            onClick = { menuOpen = true },
            label = { Text(t("Phase: {0}", current?.name?.ifBlank { t("(unnamed)") } ?: t("None")), maxLines = 1, overflow = TextOverflow.Ellipsis) }
        )
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(text = { Text(t("No phase")) }, onClick = { menuOpen = false; onSelect(null) })
            ordered.forEach { phase ->
                DropdownMenuItem(
                    text = { Text(phase.name.ifBlank { t("(unnamed)") }, fontWeight = if (phase.id == selectedPhaseId) FontWeight.Bold else null) },
                    onClick = { menuOpen = false; onSelect(phase.id) }
                )
            }
        }
    }
}
