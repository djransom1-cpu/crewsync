package com.djransom.crewsync.ui.screens

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.Today
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.djransom.crewsync.data.model.Appointment
import com.djransom.crewsync.data.model.DailyLog
import com.djransom.crewsync.data.model.PhaseStatus
import com.djransom.crewsync.data.model.SchedulePhase
import com.djransom.crewsync.data.model.Task
import com.djransom.crewsync.i18n.dayShort
import com.djransom.crewsync.i18n.t
import com.djransom.crewsync.util.FullScreenHost
import com.djransom.crewsync.util.ScheduleRow
import com.djransom.crewsync.util.ScheduleTotals
import com.djransom.crewsync.util.WorkCalendar
import com.djransom.crewsync.util.formatScheduleDay
import com.djransom.crewsync.util.scheduleRows
import com.djransom.crewsync.util.scheduleToday
import com.djransom.crewsync.util.scheduleTotals
import com.djransom.crewsync.util.buildTodaySnapshot
import kotlin.time.Clock
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber

// What presentation mode opens on: two weeks back to twelve weeks ahead.
private const val PRESENTATION_WEEKS_BACK = 2
private const val PRESENTATION_WEEKS_AHEAD = 12
// The height, in layout units, the big-screen layout is designed for (a full-HD display).
private const val PRESENTATION_DESIGN_HEIGHT = 1080f

/**
 * Presentation mode: the schedule on its own, filling the whole display, for the meeting-room
 * screen. No menus, tabs or edit controls - a header with the project, today's date and three
 * numbers, and the timeline drawn large enough to read from across the room.
 *
 * This wrapper takes the display over (a borderless full-screen window on the laptop, landscape
 * with the system bars hidden on a phone or tablet) and listens for the ways out: the Close
 * button, Esc, or Back. [SchedulePresentationContent] is the screen itself.
 */
@Composable
fun SchedulePresentationScreen(
    projectName: String,
    phases: List<SchedulePhase>,
    tasks: List<Task>,
    appointments: List<Appointment>,
    calendar: WorkCalendar,
    onClose: () -> Unit,
    logs: List<DailyLog> = emptyList(),
    location: String = ""
) {
    FullScreenHost(onExit = onClose) {
        val focus = remember { FocusRequester() }
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }   // so Esc reaches us without a click first
        Box(
            Modifier.fillMaxSize()
                .onPreviewKeyEvent { event ->
                    if (event.key == Key.Escape && event.type == KeyEventType.KeyDown) { onClose(); true } else false
                }
                .focusRequester(focus)
                .focusable()
        ) {
            SchedulePresentationContent(projectName, phases, tasks, appointments, calendar, onClose, logs = logs, location = location)
        }
    }
}

/** The presentation screen itself. [today] and [zone] can be pinned so a screenshot comes out the same every day. */
@Composable
fun SchedulePresentationContent(
    projectName: String,
    phases: List<SchedulePhase>,
    tasks: List<Task>,
    appointments: List<Appointment>,
    calendar: WorkCalendar,
    onClose: () -> Unit,
    zone: TimeZone = TimeZone.currentSystemDefault(),
    today: LocalDate = scheduleToday(zone),
    timelineState: ScheduleTimelineState = remember { ScheduleTimelineState().apply { showAroundToday(PRESENTATION_WEEKS_BACK, PRESENTATION_WEEKS_AHEAD) } },
    logs: List<DailyLog> = emptyList(),
    location: String = "",
    initialShowToday: Boolean = false,
    lookUpWeather: Boolean = true
) {
    val totals = remember(phases, calendar, zone) { scheduleTotals(phases, calendar, zone) }
    // The meeting-room screen shows either the timeline or Today (TodaySnapshotView.kt).
    var showToday by rememberSaveable { mutableStateOf(initialShowToday) }
    val snapshot = remember(phases, tasks, appointments, logs, calendar, today) {
        buildTodaySnapshot(phases, tasks, appointments, logs, calendar, today, Clock.System.now().toEpochMilliseconds(), zone)
    }
    val rows = remember(phases) { scheduleRows(phases) }
    var openPhaseId by rememberSaveable { mutableStateOf<String?>(null) }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            // A phone on its side is far shorter than a TV: the header shrinks to one tight line there.
            val compact = maxHeight < 520.dp
            // On anything bigger the screen is laid out as if it were 1080 units tall, whatever the
            // display really is. A full-HD TV, a 4K one and a laptop set to 150% scaling then all show
            // the same picture, filling the screen - rather than a laptop's scaling setting deciding
            // how many phases fit. (It won't shrink below half the display's normal size, so a
            // small, dense screen keeps readable lettering and scrolls instead.)
            val system = LocalDensity.current
            val design = if (compact) system else Density(
                density = (constraints.maxHeight / PRESENTATION_DESIGN_HEIGHT).coerceAtLeast(system.density * 0.5f),
                fontScale = system.fontScale
            )
            CompositionLocalProvider(LocalDensity provides design) {
                Column(Modifier.fillMaxSize()) {
                    PresentationHeader(
                        projectName = projectName, today = today, totals = totals, compact = compact,
                        onShowAhead = { timelineState.showAroundToday(PRESENTATION_WEEKS_BACK, PRESENTATION_WEEKS_AHEAD) },
                        onFit = { timelineState.fit() },
                        onClose = onClose,
                        showToday = showToday, onShowToday = { showToday = it }
                    )
                    HorizontalDivider(thickness = 2.dp, color = MaterialTheme.colorScheme.outline)
                    if (showToday) {
                        TodaySnapshotBig(snapshot, location, zone, Modifier.weight(1f), onPhaseClick = { openPhaseId = it.id }, lookUpWeather = lookUpWeather)
                    } else BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                        // Rows are made as tall as they can be while every phase still fits on the screen,
                        // within limits: never smaller than the normal timeline, never comically large.
                        val phaseRows = rows.count { it is ScheduleRow.PhaseLine }.coerceAtLeast(1)
                        val groupRows = rows.count { it is ScheduleRow.GroupHead }
                        val calendarHeader = 82.dp // month band + week band + marker strip of the presentation style
                        val rowHeight = ((maxHeight - calendarHeader) / (phaseRows + 0.72f * groupRows)).coerceIn(40.dp, 64.dp)
                        val nameWidth = (maxWidth * 0.2f).coerceIn(200.dp, 400.dp)
                        ScheduleTimeline(
                            phases = phases, tasks = tasks, calendar = calendar, state = timelineState,
                            onPhaseClick = { openPhaseId = it.id },
                            style = TimelineStyle.presentation(rowHeight, nameWidth), zone = zone, today = today
                        )
                    }
                }
                // The same read-only card as on the Schedule tab, drawn bigger for the big screen.
                phases.firstOrNull { it.id == openPhaseId }?.let { phase ->
                    PhaseCardDialog(phase, calendar, tasks, appointments, today, zone, onDismiss = { openPhaseId = null }, large = !compact)
                }
            }
        }
    }
}

@Composable
private fun PresentationHeader(
    projectName: String, today: LocalDate, totals: ScheduleTotals, compact: Boolean,
    onShowAhead: () -> Unit, onFit: () -> Unit, onClose: () -> Unit,
    showToday: Boolean = false, onShowToday: (Boolean) -> Unit = {}
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = if (compact) 16.dp else 28.dp, vertical = if (compact) 6.dp else 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f).padding(end = 24.dp)) {
            Text(
                projectName.trim().ifEmpty { t("Schedule") },
                fontSize = if (compact) 22.sp else 40.sp, lineHeight = if (compact) 26.sp else 46.sp,
                fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Text(
                "${t("Schedule")}  ·  ${dayShort(today.dayOfWeek.isoDayNumber)} ${formatScheduleDay(today)}",
                fontSize = if (compact) 13.sp else 22.sp, lineHeight = if (compact) 16.sp else 28.sp,
                color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
        // The three figures share a top edge, so a label that wraps to two lines does not push its number up.
        Row(verticalAlignment = Alignment.Top) {
        PresentationStat(totals.done.toString(), t("Phases done"), phaseStatusColor(PhaseStatus.DONE), compact)
        PresentationStat(totals.inProgress.toString(), t("In progress"), phaseStatusColor(PhaseStatus.IN_PROGRESS), compact)
        val slip = totals.finishSlipDays
        PresentationStat(
            value = when {
                slip == null -> "–"
                slip > 0 -> "+$slip"
                else -> slip.toString()
            },
            label = t("Working days of slip at finish"),
            // Red when the finish has slipped, green when it's on or ahead of plan.
            color = when {
                slip == null -> muted
                slip > 0 -> MaterialTheme.colorScheme.error
                else -> phaseStatusColor(PhaseStatus.DONE)
            },
            compact = compact
        )
        }
        Spacer(Modifier.width(if (compact) 4.dp else 16.dp))
        val iconSize = if (compact) 24.dp else 32.dp
        // Switch between the timeline and Today.
        FilterChip(selected = !showToday, onClick = { onShowToday(false) }, label = { Text(t("Timeline")) })
        Spacer(Modifier.width(8.dp))
        FilterChip(selected = showToday, onClick = { onShowToday(true) }, label = { Text(t("Today")) })
        Spacer(Modifier.width(8.dp))
        if (!showToday) {
            IconButton(onClick = onShowAhead) { Icon(Icons.Default.Today, contentDescription = t("Back to today"), modifier = Modifier.size(iconSize)) }
            IconButton(onClick = onFit) { Icon(Icons.Default.FitScreen, contentDescription = t("Fit"), modifier = Modifier.size(iconSize)) }
        }
        IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = t("Close"), modifier = Modifier.size(iconSize)) }
    }
}

/** One of the header's three numbers: the figure large, what it counts underneath. */
@Composable
private fun PresentationStat(value: String, label: String, color: Color, compact: Boolean) {
    Column(
        modifier = Modifier.padding(horizontal = if (compact) 10.dp else 24.dp).widthIn(max = if (compact) 120.dp else 230.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(value, fontSize = if (compact) 24.sp else 52.sp, lineHeight = if (compact) 26.sp else 56.sp, fontWeight = FontWeight.Bold, color = color, maxLines = 1)
        Text(
            label, fontSize = if (compact) 11.sp else 17.sp, lineHeight = if (compact) 13.sp else 21.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}
