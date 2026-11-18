package com.djransom.crewsync.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.Slideshow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.djransom.crewsync.data.model.Appointment
import com.djransom.crewsync.data.model.Contact
import com.djransom.crewsync.data.model.PhaseStatus
import com.djransom.crewsync.data.model.Project
import com.djransom.crewsync.data.model.SchedulePhase
import com.djransom.crewsync.data.model.Task
import com.djransom.crewsync.i18n.t
import com.djransom.crewsync.util.TIMELINE_MONTHS_DAY_WIDTH
import com.djransom.crewsync.util.TIMELINE_WEEKS_DAY_WIDTH
import com.djransom.crewsync.util.WorkCalendar
import com.djransom.crewsync.util.endDate
import com.djransom.crewsync.util.formatPhaseDates
import com.djransom.crewsync.util.groupPhases
import com.djransom.crewsync.util.linkedTasks
import com.djransom.crewsync.util.percentShown
import com.djransom.crewsync.util.phasesReadyToMarkDone
import com.djransom.crewsync.util.scheduleToday
import com.djransom.crewsync.util.startDate
import com.djransom.crewsync.util.toSchedulePhaseSafe
import com.djransom.crewsync.util.workCalendar
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.firestore.firestore
import dev.gitlive.firebase.firestore.where
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone

/**
 * The Schedule tab: the project's phases (Roofing, Insulation, Drywall...) banded by group, in
 * schedule order, as a timeline (Gantt chart - see ScheduleTimelineView.kt) or as a list with
 * each phase's dates, working days, status and percent complete. Tapping a phase in either opens
 * its card (SchedulePhaseCard.kt). The Present button opens presentation mode
 * (SchedulePresentation.kt): the same timeline on its own, full-screen, for the meeting room.
 *
 * With [ScheduleActions] to hand (the project's leader), phases can be added and edited here
 * (SchedulePhaseEditScreen.kt); without, the tab is read-only. The phases themselves are listened
 * to by ProjectDetailsScreen and passed in. [ScheduleContent] and everything under it draw
 * whatever they are handed and know nothing about Firestore, so tests and screenshots can feed
 * them sample data (see ScheduleScreenshotTest).
 */
@Composable
fun ScheduleTab(
    projectId: String,
    project: Project,
    tasks: List<Task>,
    appointments: List<Appointment>,
    phases: List<SchedulePhase>?,       // null while the first answer from Firestore is on its way
    loadError: String?,
    actions: ScheduleActions?,          // null: read-only
    presenting: Boolean = false,
    onPresentingChange: (Boolean) -> Unit = {}
) {
    val calendar = remember(project.workWeek, project.scheduleHolidays) { project.workCalendar() }
    // The workspace's contacts: for picking who's doing a phase, and for tap-to-call.
    val contactsFlow = remember(project.environmentId) {
        if (project.environmentId.isEmpty()) return@remember flowOf(emptyList())
        Firebase.firestore.collection("contacts")
            .where { "environmentId" equalTo project.environmentId }
            .snapshots
            .map { snap -> snap.documents.mapNotNull { doc -> try { doc.data<Contact>().copy(id = doc.id) } catch (_: Exception) { null } } }
            .catch { emit(emptyList()) }
    }
    val contacts by contactsFlow.collectAsState(initial = emptyList())

    // Presentation mode has no menu or back arrow of its own, so never leave someone in it looking at an error.
    LaunchedEffect(loadError, presenting) { if (loadError != null && presenting) onPresentingChange(false) }
    when {
        loadError != null -> ScheduleMessage(t("Couldn't load the schedule: {0}", loadError))
        phases == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        presenting -> SchedulePresentationScreen(
            projectName = project.name, phases = phases, tasks = tasks, appointments = appointments, calendar = calendar,
            onClose = { onPresentingChange(false) }
        )
        else -> ScheduleContent(
            phases = phases, tasks = tasks, appointments = appointments, calendar = calendar,
            onPresent = { onPresentingChange(true) },
            actions = actions, contacts = contacts, projectId = projectId
        )
    }
}

/** The two ways the Schedule tab shows the phases. */
enum class ScheduleView { Timeline, List }

/**
 * The schedule with its toolbar: Timeline or List, and for the timeline the zoom (weeks or
 * months) and Fit. [today] and [zone] can be pinned so a screenshot comes out the same every day.
 */
@Composable
fun ScheduleContent(
    phases: List<SchedulePhase>,
    tasks: List<Task>,
    appointments: List<Appointment>,
    calendar: WorkCalendar,
    modifier: Modifier = Modifier,
    zone: TimeZone = TimeZone.currentSystemDefault(),
    today: LocalDate = scheduleToday(zone),
    initialView: ScheduleView = ScheduleView.Timeline,
    timelineState: ScheduleTimelineState = rememberScheduleTimelineState(),
    onPresent: (() -> Unit)? = null,
    actions: ScheduleActions? = null,      // null: read-only, no Add / Edit
    contacts: List<Contact> = emptyList(),
    projectId: String = ""
) {
    var viewName by rememberSaveable { mutableStateOf(initialView.name) }
    val view = if (viewName == ScheduleView.List.name) ScheduleView.List else ScheduleView.Timeline
    // The card and the edit screen follow the phase by id, so they show the latest if it changes while they're open.
    var openPhaseId by rememberSaveable { mutableStateOf<String?>(null) }
    var editingPhaseId by rememberSaveable { mutableStateOf<String?>(null) }
    var addingPhase by rememberSaveable { mutableStateOf(false) }
    var pendingEditId by remember { mutableStateOf<String?>(null) }
    // Phases the person has said "Not now" to marking done. Only for this visit to the tab: it asks again next time.
    var notNow by remember { mutableStateOf(emptySet<String>()) }
    val tasksById = remember(tasks) { tasks.associateBy { it.id } }
    val askDone = remember(phases, tasksById, notNow, actions) {
        if (actions == null) null else phasesReadyToMarkDone(phases, tasksById).firstOrNull { it.id !in notNow }
    }

    if (phases.isEmpty()) {
        Box(modifier.fillMaxSize()) {
            ScheduleMessage(t("No schedule yet."), t("Phases will show here once the schedule is set up."))
            if (actions != null) {
                Button(onClick = { addingPhase = true }, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 48.dp)) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(t("Add phase"))
                }
            }
        }
    } else {
        Column(modifier = modifier.fillMaxSize()) {
            ScheduleToolbar(
                view, onView = { viewName = it.name }, timeline = timelineState, onPresent = onPresent,
                onAddPhase = if (actions != null) ({ addingPhase = true }) else null
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            actions?.error?.let { problem ->
                ScheduleNotice(t("Couldn't save: {0}", problem), isError = true, primary = t("OK") to { actions.dismissError() })
            }
            // Every linked task is finished: ask. The app never marks a phase done by itself.
            if (actions != null && askDone != null) {
                ScheduleNotice(
                    t("All tasks for \"{0}\" are done. Mark this phase done?", askDone.name),
                    primary = t("Mark done") to { actions.markDone(askDone) },
                    secondary = t("Not now") to { notNow = notNow + askDone.id }
                )
            }
            when (view) {
                ScheduleView.Timeline -> ScheduleTimeline(
                    phases = phases, tasks = tasks, calendar = calendar, state = timelineState,
                    onPhaseClick = { openPhaseId = it.id }, zone = zone, today = today
                )
                ScheduleView.List -> ScheduleList(phases = phases, tasks = tasks, calendar = calendar, zone = zone, onPhaseClick = { openPhaseId = it.id })
            }
        }
    }

    phases.firstOrNull { it.id == openPhaseId }?.let { phase ->
        PhaseCardDialog(
            phase, calendar, tasks, appointments, today, zone, onDismiss = { openPhaseId = null }, contacts = contacts,
            onEdit = if (actions != null) ({ pendingEditId = phase.id; openPhaseId = null }) else null
        )
    }
    // The card has to be fully gone before the edit screen opens: closing one dialog and opening
    // another in the same frame leaves the web build unresponsive (see pendingTaskId in PlannerScreen).
    LaunchedEffect(pendingEditId, openPhaseId) {
        val id = pendingEditId
        if (id != null && openPhaseId == null) {
            withFrameNanos { }
            editingPhaseId = id
            pendingEditId = null
        }
    }
    if (actions != null) {
        val editing = phases.firstOrNull { it.id == editingPhaseId }
        if (editing != null || addingPhase) {
            key(editing?.id ?: "new") {
                PhaseEditDialog(
                    phase = editing, allPhases = phases, tasks = tasks, appointments = appointments, contacts = contacts,
                    calendar = calendar, projectId = projectId, today = today, zone = zone, actions = actions,
                    onDismiss = { editingPhaseId = null; addingPhase = false }
                )
            }
        }
    }
}

/** A strip across the top of the tab for something that needs an answer: a question, or a save that failed. */
@Composable
private fun ScheduleNotice(text: String, primary: Pair<String, () -> Unit>, secondary: Pair<String, () -> Unit>? = null, isError: Boolean = false) {
    val background = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer
    val content = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer
    Row(
        modifier = Modifier.fillMaxWidth().background(background).padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text, color = content, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(end = 8.dp))
        if (secondary != null) TextButton(onClick = secondary.second) { Text(secondary.first) }
        Button(onClick = primary.second) { Text(primary.first) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScheduleToolbar(
    view: ScheduleView, onView: (ScheduleView) -> Unit, timeline: ScheduleTimelineState,
    onPresent: (() -> Unit)?, onAddPhase: (() -> Unit)?
) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FilterChip(selected = view == ScheduleView.Timeline, onClick = { onView(ScheduleView.Timeline) }, label = { Text(t("Timeline")) })
        FilterChip(selected = view == ScheduleView.List, onClick = { onView(ScheduleView.List) }, label = { Text(t("List")) })
        // Presentation mode: the schedule on its own, full-screen, for the meeting-room screen.
        if (onPresent != null) {
            AssistChip(
                onClick = onPresent, label = { Text(t("Present")) },
                leadingIcon = { Icon(Icons.Default.Slideshow, contentDescription = null, modifier = Modifier.size(AssistChipDefaults.IconSize)) }
            )
        }
        if (onAddPhase != null) {
            AssistChip(
                onClick = onAddPhase, label = { Text(t("Add phase")) },
                leadingIcon = { Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(AssistChipDefaults.IconSize)) }
            )
        }
        if (view == ScheduleView.Timeline) {
            Spacer(Modifier.width(12.dp))
            FilterChip(
                selected = timeline.dayWidth == TIMELINE_WEEKS_DAY_WIDTH,
                onClick = { timeline.zoom(TIMELINE_WEEKS_DAY_WIDTH) }, label = { Text(t("Weeks")) }
            )
            FilterChip(
                selected = timeline.dayWidth == TIMELINE_MONTHS_DAY_WIDTH,
                onClick = { timeline.zoom(TIMELINE_MONTHS_DAY_WIDTH) }, label = { Text(t("Months")) }
            )
            // Frames today through the last phase.
            AssistChip(
                onClick = { timeline.fit() }, label = { Text(t("Fit")) },
                leadingIcon = { Icon(Icons.Default.FitScreen, contentDescription = null, modifier = Modifier.size(AssistChipDefaults.IconSize)) }
            )
        }
    }
}

/**
 * The schedule as a list. On a wide window (tablet, desktop, the meeting-room screen) each phase
 * is one line under column headings; on a phone each phase is a short stack.
 */
@Composable
fun ScheduleList(
    phases: List<SchedulePhase>,
    tasks: List<Task>,
    calendar: WorkCalendar,
    modifier: Modifier = Modifier,
    zone: TimeZone = TimeZone.currentSystemDefault(),
    onPhaseClick: ((SchedulePhase) -> Unit)? = null
) {
    if (phases.isEmpty()) {
        ScheduleMessage(t("No schedule yet."), t("Phases will show here once the schedule is set up."), modifier)
        return
    }
    val groups = remember(phases) { groupPhases(phases) }
    val tasksById = remember(tasks) { tasks.associateBy { it.id } }
    // One band with no name means nobody has grouped anything: skip the heading altogether.
    val showGroupHeads = groups.size > 1 || groups.first().name.isNotEmpty()

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val wide = maxWidth >= 720.dp
        LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            if (wide) item(key = "heads") { ScheduleColumnHeads() }
            groups.forEach { group ->
                if (showGroupHeads) {
                    item(key = "group:" + group.name.lowercase()) {
                        ScheduleGroupHead(group.name.ifEmpty { t("Other") }, group.phases.size)
                    }
                }
                items(group.phases, key = { "phase:" + it.id }) { phase ->
                    val start = phase.startDate(zone)
                    val end = phase.endDate(zone)
                    val row = PhaseRowText(
                        name = phase.name.ifBlank { t("(unnamed)") },
                        isMilestone = phase.isMilestone,
                        dates = formatPhaseDates(start, end).ifEmpty { t("No dates yet") },
                        length = phaseLengthText(phase, calendar, zone),
                        status = phase.status,
                        percent = phase.percentShown(phase.linkedTasks(tasksById))
                    )
                    Box(if (onPhaseClick != null) Modifier.clickable { onPhaseClick(phase) } else Modifier) {
                        if (wide) PhaseRowWide(row) else PhaseRowNarrow(row)
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                }
            }
        }
    }
}

/** The colour a phase is drawn in for its status: grey not started, blue in progress, green done, amber on hold. */
fun phaseStatusColor(status: String): Color = when (status) {
    PhaseStatus.DONE -> Color(0xFF2E7D32)
    PhaseStatus.IN_PROGRESS -> Color(0xFF1976D2)
    PhaseStatus.ON_HOLD -> Color(0xFFEF6C00)
    else -> Color(0xFF757575)
}

/** Everything one row shows, already worked out and worded. */
private data class PhaseRowText(
    val name: String,
    val isMilestone: Boolean,
    val dates: String,
    val length: String,
    val status: String,
    val percent: Int
)

// Column widths for the wide layout, shared by the headings and the rows so they line up.
private const val COL_NAME = 0.34f
private const val COL_DATES = 0.26f
private const val COL_LENGTH = 0.14f
private const val COL_STATUS = 0.12f
private const val COL_PERCENT = 0.14f

@Composable
private fun ScheduleColumnHeads() {
    Row(
        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val style = MaterialTheme.typography.labelMedium
        val color = MaterialTheme.colorScheme.onSurfaceVariant
        Text(t("Phase"), Modifier.weight(COL_NAME), style = style, color = color)
        Text(t("Dates"), Modifier.weight(COL_DATES), style = style, color = color)
        Text(t("Working days"), Modifier.weight(COL_LENGTH), style = style, color = color)
        Text(t("Status"), Modifier.weight(COL_STATUS), style = style, color = color)
        Text(t("Progress"), Modifier.weight(COL_PERCENT), style = style, color = color)
    }
}

@Composable
private fun ScheduleGroupHead(name: String, count: Int) {
    Row(
        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(count.toString(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PhaseRowWide(row: PhaseRowText) {
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        PhaseName(row, Modifier.weight(COL_NAME).padding(end = 12.dp))
        Text(row.dates, Modifier.weight(COL_DATES).padding(end = 12.dp), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(row.length, Modifier.weight(COL_LENGTH).padding(end = 12.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Box(Modifier.weight(COL_STATUS).padding(end = 12.dp)) { PhaseStatusPill(row.status) }
        PhasePercent(row, Modifier.weight(COL_PERCENT))
    }
}

@Composable
private fun PhaseRowNarrow(row: PhaseRowText) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PhaseName(row, Modifier.weight(1f).padding(end = 8.dp))
            PhaseStatusPill(row.status)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            listOf(row.dates, row.length).filter { it.isNotEmpty() }.joinToString("  ·  "),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(6.dp))
        PhasePercent(row, Modifier.fillMaxWidth())
    }
}

@Composable
private fun PhaseName(row: PhaseRowText, modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        if (row.isMilestone) {
            // A milestone is drawn as a diamond, here and on the timeline.
            Box(Modifier.padding(end = 10.dp).size(10.dp).rotate(45f).background(phaseStatusColor(row.status)))
        }
        Text(row.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun PhaseStatusPill(status: String) {
    val color = phaseStatusColor(status)
    Text(
        t(status),
        style = MaterialTheme.typography.labelMedium,
        color = color,
        maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.14f)).padding(horizontal = 10.dp, vertical = 4.dp)
    )
}

@Composable
private fun PhasePercent(row: PhaseRowText, modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        LinearProgressIndicator(
            progress = { row.percent / 100f },
            modifier = Modifier.weight(1f).height(6.dp).clip(CircleShape),
            color = phaseStatusColor(row.status),
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
            gapSize = 0.dp,
            drawStopIndicator = {} // a plain bar: no end dot, which reads as progress on a 0% row
        )
        Text(
            "${row.percent}%",
            style = MaterialTheme.typography.labelLarge,
            textAlign = TextAlign.End,
            modifier = Modifier.width(48.dp)
        )
    }
}

/** A centred line or two for when there's nothing to list: no schedule yet, or it couldn't be loaded. */
@Composable
internal fun ScheduleMessage(title: String, detail: String = "", modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Default.DateRange, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        if (detail.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
    }
}
