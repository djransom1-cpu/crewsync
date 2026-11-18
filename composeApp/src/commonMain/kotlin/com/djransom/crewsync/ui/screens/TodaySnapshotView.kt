package com.djransom.crewsync.ui.screens

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.djransom.crewsync.data.model.Appointment
import com.djransom.crewsync.data.model.DailyLog
import com.djransom.crewsync.data.model.Project
import com.djransom.crewsync.data.model.SchedulePhase
import com.djransom.crewsync.data.model.Task
import com.djransom.crewsync.i18n.clockTime
import com.djransom.crewsync.i18n.dayShort
import com.djransom.crewsync.i18n.t
import com.djransom.crewsync.util.AppointmentOccurrence
import com.djransom.crewsync.util.BehindReason
import com.djransom.crewsync.util.TodaySnapshot
import com.djransom.crewsync.util.WorkCalendar
import com.djransom.crewsync.util.buildTodaySnapshot
import com.djransom.crewsync.util.fetchLocalWeather
import com.djransom.crewsync.util.formatPhaseDates
import com.djransom.crewsync.util.formatScheduleDay
import com.djransom.crewsync.util.scheduleToday
import com.djransom.crewsync.util.startDate
import com.djransom.crewsync.util.endDate
import com.djransom.crewsync.util.toDoShown
import com.djransom.crewsync.util.toFirestoreMap
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.firestore.firestore
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber
import kotlin.time.Clock

// ---- Today: where we are and what's next (see ScheduleSnapshot.kt for the rules) ----------------

/**
 * The Today view as reached from the Schedule and Daily Log tabs: always live, worked out from
 * what's on the project right now. Ticking a To do item ticks it on its task card; tapping a card's
 * name opens its checklist.
 */
@Composable
fun TodaySnapshotTab(
    project: Project,
    phases: List<SchedulePhase>,
    tasks: List<Task>,
    appointments: List<Appointment>,
    logs: List<DailyLog>,
    calendar: WorkCalendar,
    modifier: Modifier = Modifier,
    onPhaseClick: ((SchedulePhase) -> Unit)? = null
) {
    val zone = TimeZone.currentSystemDefault()
    val today = scheduleToday(zone)
    val snapshot = remember(phases, tasks, appointments, logs, calendar, today) {
        buildTodaySnapshot(phases, tasks, appointments, logs, calendar, today, Clock.System.now().toEpochMilliseconds(), zone)
    }
    val firestore = Firebase.firestore
    // Writes wait a frame, so none runs while a dialog is closing (see pendingSaveTask in PlannerScreen).
    var pendingSave by remember { mutableStateOf<Task?>(null) }
    LaunchedEffect(pendingSave) {
        val task = pendingSave ?: return@LaunchedEffect
        withFrameNanos { }
        runCatching { firestore.collection("tasks").document(task.id).set(task.toFirestoreMap()) }
        pendingSave = null
    }
    TodaySnapshotView(
        projectName = project.name, location = project.location, snapshot = snapshot, zone = zone, modifier = modifier,
        onToggleItem = { task, groupId, itemId, done ->
            pendingSave = task.copy(checklistGroups = task.checklistGroups.map { g ->
                if (g.id == groupId) g.copy(items = g.items.map { if (it.id == itemId) it.copy(isDone = done) else it }) else g
            })
        },
        onSaveTask = { pendingSave = it },
        onPhaseClick = onPhaseClick
    )
}

/** Today's weather: what the daily log has, otherwise a live look-up for the job site (as the Daily Log tab does). */
@Composable
private fun rememberTodayWeather(snapshot: TodaySnapshot, location: String, lookUp: Boolean): String {
    var fetched by remember(location) { mutableStateOf("") }
    LaunchedEffect(location, snapshot.weather, lookUp) {
        if (lookUp && snapshot.weather.isBlank() && location.isNotBlank() && fetched.isBlank()) {
            fetchLocalWeather(location)?.let { w ->
                val hl = if (w.highF != null && w.lowF != null) " (H ${w.highF}° / L ${w.lowF}°)" else ""
                fetched = "${w.currentTempF}°F, ${w.shortForecast}$hl"
            }
        }
    }
    return snapshot.weather.ifBlank { fetched }
}

/** The phone and tablet layout: one column of cards, each of which folds away. */
@Composable
fun TodaySnapshotView(
    projectName: String,
    location: String,
    snapshot: TodaySnapshot,
    zone: TimeZone,
    modifier: Modifier = Modifier,
    onToggleItem: ((Task, String, String, Boolean) -> Unit)? = null,
    onSaveTask: ((Task) -> Unit)? = null,
    onPhaseClick: ((SchedulePhase) -> Unit)? = null,
    lookUpWeather: Boolean = true
) {
    val weather = rememberTodayWeather(snapshot, location, lookUpWeather)
    var folded by rememberSaveable { mutableStateOf(setOf<String>()) }
    var openTask by remember { mutableStateOf<Task?>(null) }
    fun card(key: String): Pair<Boolean, () -> Unit> = (key !in folded) to { folded = if (key in folded) folded - key else folded + key }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Column {
            Text(projectName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("${dayShort(snapshot.date.dayOfWeek.isoDayNumber)} ${formatScheduleDay(snapshot.date)}", style = MaterialTheme.typography.titleSmall)
            if (weather.isNotBlank()) Text(weather, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        card("stand").let { (open, toggle) -> SnapshotCard(t("Where we stand"), open, toggle) { StandSection(snapshot, onPhaseClick) } }
        card("behind").let { (open, toggle) -> SnapshotCard(t("Behind"), open, toggle, count = snapshot.behindPhases.size + snapshot.overdueTasks.size, alert = true) { BehindSection(snapshot, onPhaseClick) } }
        card("next").let { (open, toggle) -> SnapshotCard(t("Next up"), open, toggle) { NextUpSection(snapshot, zone, onPhaseClick) } }
        card("site").let { (open, toggle) -> SnapshotCard(t("On site"), open, toggle) { OnSiteSection(snapshot) } }
        card("todo").let { (open, toggle) ->
            SnapshotCard(t("To do"), open, toggle, count = snapshot.toDo.size) {
                ToDoSection(snapshot, expandable = true, onToggleItem = onToggleItem, onOpenTask = if (onSaveTask != null) ({ openTask = it }) else null)
            }
        }
        card("report").let { (open, toggle) -> SnapshotCard(t("From the last daily report"), open, toggle) { LastReportSection(snapshot) } }
        Spacer(Modifier.height(24.dp))
    }
    openTask?.let { task ->
        ChecklistEditorDialog(task = task, onDismiss = { openTask = null }, onSave = { saved -> openTask = null; onSaveTask?.invoke(saved) })
    }
}

/**
 * The meeting-room layout, for presentation mode: three columns sized for a 1920x1080 screen
 * without scrolling, larger lettering, nothing to edit. [scrollStates] are the three columns'
 * (they scroll only if a project has far more than fits).
 */
@Composable
fun TodaySnapshotBig(
    snapshot: TodaySnapshot,
    location: String,
    zone: TimeZone,
    modifier: Modifier = Modifier,
    onPhaseClick: ((SchedulePhase) -> Unit)? = null,
    lookUpWeather: Boolean = true,
    scrollStates: List<ScrollState> = List(3) { rememberScrollState() }
) {
    val weather = rememberTodayWeather(snapshot, location, lookUpWeather)
    val density = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(density.density, density.fontScale * 1.3f)) {
        Row(modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            BigColumn(scrollStates[0]) {
                if (weather.isNotBlank()) BigPanel(t("Weather")) { Text(weather, style = MaterialTheme.typography.bodyLarge) }
                BigPanel(t("Where we stand")) { StandSection(snapshot, onPhaseClick) }
                BigPanel(t("Next up")) { NextUpSection(snapshot, zone, onPhaseClick) }
            }
            BigColumn(scrollStates[1]) {
                BigPanel(t("Behind"), alert = snapshot.behindPhases.isNotEmpty() || snapshot.overdueTasks.isNotEmpty()) { BehindSection(snapshot, onPhaseClick) }
                BigPanel(t("On site")) { OnSiteSection(snapshot) }
            }
            BigColumn(scrollStates[2]) {
                BigPanel(t("To do")) { ToDoSection(snapshot, expandable = false, limit = 10, onToggleItem = null, onOpenTask = null) }
                BigPanel(t("From the last daily report")) { LastReportSection(snapshot) }
            }
        }
    }
}

@Composable
private fun RowScope.BigColumn(state: ScrollState, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(state), verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
}

@Composable
private fun BigPanel(title: String, alert: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title.uppercase(), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = if (alert) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
        HorizontalDivider(thickness = 2.dp, color = MaterialTheme.colorScheme.outlineVariant)
        content()
    }
}

@Composable
private fun SnapshotCard(title: String, open: Boolean, onToggle: () -> Unit, count: Int? = null, alert: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (count != null && count > 0) {
                Text(
                    count.toString(), style = MaterialTheme.typography.labelLarge,
                    color = if (alert) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.clip(CircleShape).background(if (alert) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary).padding(horizontal = 8.dp, vertical = 2.dp)
                )
            }
            Icon(if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = null)
        }
        if (open) Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp), content = content)
    }
}

@Composable private fun Muted(text: String) = Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
@Composable private fun SubHeading(text: String) = Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))

private fun dayText(day: LocalDate) = "${dayShort(day.dayOfWeek.isoDayNumber)} ${formatScheduleDay(day)}"

@Composable
private fun StandSection(s: TodaySnapshot, onPhaseClick: ((SchedulePhase) -> Unit)?) {
    if (!s.hasSchedule) { Muted(t("No schedule yet.")); return }
    if (s.inProgress.isEmpty()) Muted(t("No phases in progress."))
    s.inProgress.forEach { p ->
        Column(Modifier.fillMaxWidth().then(if (onPhaseClick != null) Modifier.clickable { onPhaseClick(p.phase) } else Modifier).padding(vertical = 3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(p.phase.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${p.percent}%", style = MaterialTheme.typography.labelLarge)
            }
            LinearProgressIndicator(
                progress = { p.percent / 100f }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
                color = phaseStatusColor(p.phase.status), trackColor = MaterialTheme.colorScheme.surfaceVariant, gapSize = 0.dp, drawStopIndicator = {}
            )
            p.slipDays?.let { slip -> SlipText(slip) }
        }
    }
    if (s.recentlyFinished.isNotEmpty()) {
        SubHeading(t("Finished this past week"))
        Text(s.recentlyFinished.joinToString(", ") { it.name }, style = MaterialTheme.typography.bodyMedium)
    }
    s.currentFinish?.let { finish ->
        SubHeading(t("Finish"))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(formatScheduleDay(finish), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(end = 8.dp))
            s.finishSlipDays?.let { SlipText(it) }
        }
    }
}

@Composable
private fun SlipText(slip: Int) = Text(
    when {
        slip > 0 -> t("{0} working day(s) behind plan", slip)
        slip < 0 -> t("{0} working day(s) ahead of plan", -slip)
        else -> t("On plan")
    },
    style = MaterialTheme.typography.bodySmall,
    color = if (slip > 0) MaterialTheme.colorScheme.error else Color(0xFF2E7D32)
)

@Composable
private fun BehindSection(s: TodaySnapshot, onPhaseClick: ((SchedulePhase) -> Unit)?) {
    if (!s.hasSchedule) Muted(t("No schedule yet."))
    else if (s.behindPhases.isEmpty() && s.overdueTasks.isEmpty()) Muted(t("Nothing is behind."))
    s.behindPhases.forEach { b ->
        Column(Modifier.fillMaxWidth().then(if (onPhaseClick != null) Modifier.clickable { onPhaseClick(b.phase) } else Modifier).padding(vertical = 2.dp)) {
            Text(b.phase.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(
                if (b.reason == BehindReason.SHOULD_HAVE_STARTED) t("Should have started {0}", formatScheduleDay(b.since)) else t("Should have finished {0}", formatScheduleDay(b.since)),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error
            )
        }
    }
    if (s.overdueTasks.isNotEmpty()) {
        SubHeading(t("Overdue task cards"))
        s.overdueTasks.forEach { d ->
            Text("• ${d.task.title}  ·  ${t("Was due {0}", formatScheduleDay(d.due))}", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun NextUpSection(s: TodaySnapshot, zone: TimeZone, onPhaseClick: ((SchedulePhase) -> Unit)?) {
    if (!s.hasSchedule) { Muted(t("No schedule yet.")); return }
    if (s.nextUp.isEmpty()) Muted(t("Nothing starting in the next {0} working days.", 10))
    s.nextUp.forEach { p ->
        Row(Modifier.fillMaxWidth().then(if (onPhaseClick != null) Modifier.clickable { onPhaseClick(p) } else Modifier).padding(vertical = 2.dp)) {
            Text(p.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(formatPhaseDates(p.startDate(zone), p.endDate(zone)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun OnSiteSection(s: TodaySnapshot) {
    SubHeading(t("Today"))
    if (s.onSiteToday.isEmpty()) Muted(t("Nothing on the site calendar today."))
    s.onSiteToday.forEach { VisitLine(it, withDay = false) }
    SubHeading(t("Next 7 days"))
    if (s.onSiteNextWeek.isEmpty()) Muted(t("Nothing on the site calendar."))
    s.onSiteNextWeek.forEach { VisitLine(it, withDay = true) }
}

@Composable
private fun VisitLine(o: AppointmentOccurrence, withDay: Boolean) {
    val time = o.minutes?.let { clockTime(it / 60, it % 60) } ?: t("All Day")
    Column(Modifier.padding(vertical = 2.dp)) {
        Text(o.appointment.title, style = MaterialTheme.typography.bodyLarge)
        Text(if (withDay) "${dayText(o.day)}, $time" else time, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ToDoSection(
    s: TodaySnapshot,
    expandable: Boolean,
    limit: Int = com.djransom.crewsync.util.SNAPSHOT_TODO_SHOWN,
    onToggleItem: ((Task, String, String, Boolean) -> Unit)?,
    onOpenTask: ((Task) -> Unit)?
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val (shown, more) = s.toDoShown(expanded && expandable, limit)
    if (s.toDo.isEmpty()) Muted(t("No open checklist items."))
    shown.forEach { todo ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (onToggleItem != null) {
                Checkbox(checked = todo.item.isDone, onCheckedChange = { onToggleItem(todo.task, todo.groupId, todo.item.id, it) })
            } else {
                Text("☐", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(end = 8.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(todo.item.text, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    todo.task.title + if (todo.priority) "  ·  " + t("priority") else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (todo.priority) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = if (onOpenTask != null) Modifier.clickable { onOpenTask(todo.task) } else Modifier
                )
            }
        }
    }
    if (more > 0) {
        if (expandable) TextButton(onClick = { expanded = true }) { Text(t("+{0} more", more)) }
        else Muted(t("+{0} more", more))
    } else if (expandable && expanded && s.toDo.size > limit) {
        TextButton(onClick = { expanded = false }) { Text(t("Show fewer")) }
    }
}

@Composable
private fun LastReportSection(s: TodaySnapshot) {
    val log = s.lastReport
    if (log == null) { Muted(t("No daily logs yet.")); return }
    Text(com.djransom.crewsync.util.formatLogDate(log.date), style = MaterialTheme.typography.titleSmall)
    SubHeading(t("Issues & Delays"))
    if (log.issuesDelays.isEmpty()) Muted(t("None")) else log.issuesDelays.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
    SubHeading(t("Plan for Tomorrow"))
    if (log.tomorrowPlan.isEmpty()) Muted(t("None")) else log.tomorrowPlan.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
}
