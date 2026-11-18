package com.djransom.crewsync.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.djransom.crewsync.data.model.Appointment
import com.djransom.crewsync.data.model.SchedulePhase
import com.djransom.crewsync.data.model.Task
import com.djransom.crewsync.i18n.clockTime
import com.djransom.crewsync.i18n.dayShort
import com.djransom.crewsync.i18n.t
import com.djransom.crewsync.util.BehindReason
import com.djransom.crewsync.util.LookAheadEntry
import com.djransom.crewsync.util.LookAheadGroup
import com.djransom.crewsync.util.PhaseRole
import com.djransom.crewsync.util.buildLookAhead
import com.djransom.crewsync.util.byDay
import com.djransom.crewsync.util.byTrade
import com.djransom.crewsync.util.formatPhaseDates
import com.djransom.crewsync.util.formatScheduleDay
import com.djransom.crewsync.util.phaseOfAppointment
import com.djransom.crewsync.util.phaseOfTask
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber

/**
 * The look-ahead (see ScheduleLookAhead.kt for the rules): a Behind section on top - phases that
 * should have started or finished, and overdue task cards - then what's coming up over the next
 * [weeks] weeks, grouped by day or by trade. Tapping a phase opens its card.
 */
@Composable
fun ScheduleLookAheadView(
    phases: List<SchedulePhase>,
    tasks: List<Task>,
    appointments: List<Appointment>,
    weeks: Int,
    byTrade: Boolean,
    today: LocalDate,
    zone: TimeZone,
    onPhaseClick: (SchedulePhase) -> Unit,
    modifier: Modifier = Modifier
) {
    val look = remember(phases, tasks, appointments, today, weeks, zone) { buildLookAhead(phases, tasks, appointments, today, weeks, zone) }
    val groups = remember(look, byTrade, phases) { if (byTrade) look.byTrade(phases) else look.byDay() }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    fun dayText(day: LocalDate) = "${dayShort(day.dayOfWeek.isoDayNumber)} ${formatScheduleDay(day)}"

    LazyColumn(modifier = modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item(key = "window") {
            Text(
                t("{0} to {1}", dayText(look.window.first), dayText(look.window.last)),
                style = MaterialTheme.typography.labelLarge, color = muted,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
            )
        }

        // ---- behind ----
        if (look.behindPhases.isNotEmpty() || look.overdueTasks.isNotEmpty()) {
            item(key = "behind") {
                GroupHeading(t("Behind"), color = MaterialTheme.colorScheme.error, background = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f))
            }
            items(look.behindPhases, key = { "behind:" + it.phase.id }) { behind ->
                EntryRow(
                    icon = Icons.Default.Warning, iconTint = MaterialTheme.colorScheme.error,
                    title = behind.phase.name.ifBlank { t("(unnamed)") },
                    detail = when (behind.reason) {
                        BehindReason.SHOULD_HAVE_STARTED -> t("Should have started {0}", formatScheduleDay(behind.since))
                        BehindReason.SHOULD_HAVE_FINISHED -> t("Should have finished {0}", formatScheduleDay(behind.since))
                    },
                    status = behind.phase.status,
                    onClick = { onPhaseClick(behind.phase) }
                )
            }
            items(look.overdueTasks, key = { "overdue:" + it.task.id }) { due ->
                EntryRow(
                    icon = Icons.Default.CheckCircle, iconTint = MaterialTheme.colorScheme.error,
                    title = due.task.title,
                    detail = listOfNotNull(t("Was due {0}", formatScheduleDay(due.due)), t(due.task.status), phaseOfTask(phases, due.task.id)?.name).joinToString("  ·  ")
                )
            }
        }

        // ---- coming up ----
        if (groups.isEmpty()) {
            item(key = "nothing") {
                Text(
                    t("Nothing scheduled in this window."),
                    style = MaterialTheme.typography.bodyMedium, color = muted,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 24.dp)
                )
            }
        }
        groups.forEachIndexed { index, group ->
            item(key = "group:$index") {
                GroupHeading(
                    when {
                        group.day != null -> dayText(group.day)
                        group.trade != null -> group.trade.ifEmpty { t("No trade") }
                        else -> t("Under way")
                    }
                )
            }
            items(group.entries.size, key = { "group:$index:$it" }) { i ->
                LookAheadEntryRow(group.entries[i], group, phases, onPhaseClick, ::dayText)
            }
        }
    }
}

@Composable
private fun LookAheadEntryRow(
    entry: LookAheadEntry,
    group: LookAheadGroup,
    phases: List<SchedulePhase>,
    onPhaseClick: (SchedulePhase) -> Unit,
    dayText: (LocalDate) -> String
) {
    // Grouped by trade the lines come from many days, so each says which; grouped by day the heading does.
    val showDay = group.day == null && group.trade != null
    when (entry) {
        is LookAheadEntry.Phase -> {
            val p = entry.item
            EntryRow(
                icon = Icons.Default.Timeline, iconTint = phaseStatusColor(p.phase.status),
                title = p.phase.name.ifBlank { t("(unnamed)") },
                detail = listOfNotNull(
                    when (entry.role) {
                        PhaseRole.STARTS -> t("Starts")
                        PhaseRole.FINISHES -> t("Finishes")
                        PhaseRole.UNDER_WAY, PhaseRole.RUNNING -> null
                    },
                    formatPhaseDates(p.start, p.end),
                    p.phase.trade.trim().takeIf { it.isNotEmpty() && group.trade == null },
                    if (p.isLate) t("Late") else null
                ).joinToString("  ·  "),
                status = p.phase.status,
                onClick = { onPhaseClick(p.phase) }
            )
        }
        is LookAheadEntry.Visit -> {
            val o = entry.item
            val time = o.minutes?.let { clockTime(it / 60, it % 60) } ?: t("All Day")
            EntryRow(
                icon = Icons.Default.DateRange, iconTint = MaterialTheme.colorScheme.primary,
                title = o.appointment.title,
                detail = listOfNotNull(
                    if (showDay) "${dayText(o.day)}, $time" else time,
                    o.appointment.location.takeIf { it.isNotBlank() },
                    if (group.trade == null) phaseOfAppointment(phases, o.appointment.id)?.name else null
                ).joinToString("  ·  ")
            )
        }
        is LookAheadEntry.Due -> {
            val d = entry.item
            val items = d.task.allChecklistItems()
            EntryRow(
                icon = Icons.Default.CheckCircle, iconTint = MaterialTheme.colorScheme.secondary,
                title = d.task.title,
                detail = listOfNotNull(
                    if (showDay) t("Due {0}", dayText(d.due)) else t("Task due"),
                    t(d.task.status),
                    if (items.isNotEmpty()) "${items.count { it.isDone }}/${items.size}" else null,
                    if (group.trade == null) phaseOfTask(phases, d.task.id)?.name else null
                ).joinToString("  ·  ")
            )
        }
    }
}

@Composable
private fun GroupHeading(text: String, color: Color = MaterialTheme.colorScheme.onSurface, background: Color = MaterialTheme.colorScheme.surfaceVariant) {
    Text(
        text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = color,
        maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth().background(background).padding(horizontal = 16.dp, vertical = 8.dp)
    )
}

@Composable
private fun EntryRow(icon: ImageVector, iconTint: Color, title: String, detail: String, status: String? = null, onClick: (() -> Unit)? = null) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (detail.isNotEmpty()) Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (status != null) {
                Spacer(Modifier.width(8.dp))
                PhaseStatusPill(status)
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    }
}
