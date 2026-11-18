package com.djransom.crewsync.util

import com.djransom.crewsync.data.model.PercentMode
import com.djransom.crewsync.data.model.PhaseStatus
import com.djransom.crewsync.data.model.SchedulePhase
import com.djransom.crewsync.data.model.Task
import com.djransom.crewsync.i18n.I18n
import com.djransom.crewsync.i18n.monthDay
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.math.roundToInt

// ---- Laying the schedule out as rows -----------------------------------------------------------
// What every schedule view (the list, the timeline, presentation mode) needs before it draws
// anything: the phases in order, banded by group, with the percent and the date text for each.
// Pure, like ScheduleDates.kt, so it can be unit-tested (see ScheduleListTest). The connector
// uses the same rules - functions/schedule.js - so the app and Claude always agree.

/** One band of the schedule: a group ("Interior") and its phases in schedule order. */
data class PhaseGroup(val name: String, val phases: List<SchedulePhase>)

/** Schedule order: the order field, then start date (undated last), then name. */
fun List<SchedulePhase>.inScheduleOrder(): List<SchedulePhase> =
    sortedWith(compareBy<SchedulePhase> { it.order }.thenBy { it.start ?: Long.MAX_VALUE }.thenBy { it.name.lowercase() })

/**
 * The phases banded by group. Groups come in the order of their first phase, and the phases
 * inside each keep schedule order. Group names match ignoring case and stray spaces, so a phase
 * typed as "interior" still lands in "Interior". Phases with no group share one band named "".
 */
fun groupPhases(phases: List<SchedulePhase>): List<PhaseGroup> {
    val bands = LinkedHashMap<String, MutableList<SchedulePhase>>() // keeps first-seen order
    val names = HashMap<String, String>()
    for (phase in phases.inScheduleOrder()) {
        val name = phase.group.trim()
        val key = name.lowercase()
        if (key !in names) names[key] = name
        bands.getOrPut(key) { mutableListOf() }.add(phase)
    }
    return bands.map { (key, list) -> PhaseGroup(names[key] ?: "", list) }
}

/** The tasks linked to this phase (the link lives on the phase - see SchedulePhase). Ids of deleted tasks are skipped. */
fun SchedulePhase.linkedTasks(tasksById: Map<String, Task>): List<Task> = taskIds.mapNotNull { tasksById[it] }

/**
 * Percent complete worked out from a phase's linked tasks: ticked checklist items over all
 * checklist items. A task with no checklist counts as one item, ticked when the task sits in a
 * Done-like bucket. Null when there's nothing to count.
 */
fun autoPhasePercent(tasks: List<Task>): Int? {
    var done = 0
    var total = 0
    for (task in tasks) {
        val items = task.allChecklistItems()
        if (items.isEmpty()) {
            total += 1
            if (isDoneStatus(task.status)) done += 1
        } else {
            total += items.size
            done += items.count { it.isDone }
        }
    }
    // Same arithmetic as autoPercent() in functions/schedule.js, so both round the same way.
    return if (total == 0) null else (done.toDouble() / total * 100).roundToInt()
}

/** The percent to show: 100 once Done; the typed number in manual mode; otherwise worked out from the linked tasks. */
fun SchedulePhase.percentShown(linked: List<Task>): Int = when {
    status == PhaseStatus.DONE -> 100
    percentMode == PercentMode.MANUAL -> percentComplete
    else -> autoPhasePercent(linked) ?: percentComplete
}

/**
 * The phase's length in working days, counted from its dates so a change to the project's
 * holidays shows straight away. A phase with no dates yet falls back to its stored length; null
 * when it has neither.
 */
fun SchedulePhase.workingDays(calendar: WorkCalendar, zone: TimeZone = TimeZone.currentSystemDefault()): Int? {
    val s = startDate(zone)
    val e = endDate(zone)
    return if (s != null && e != null) calendar.durationDays(s, e) else durationDays.takeIf { it > 0 }
}

/** "Sep 21, 2026" in English; "21 wrz 2026", "21 sep 2026", "21 sept. 2026", "21. Sep. 2026" elsewhere. */
fun formatScheduleDay(date: LocalDate): String {
    val md = monthDay(date.monthNumber, date.dayOfMonth)
    return if (I18n.current == "en") "$md, ${date.year}" else "$md ${date.year}"
}

/**
 * A phase's dates as one line: "Sep 21 – Oct 15, 2026". A one-day phase or milestone shows the
 * single day, a phase that runs over New Year shows both years, and a phase with no dates yet
 * gives "".
 */
fun formatPhaseDates(start: LocalDate?, end: LocalDate?): String = when {
    start == null && end == null -> ""
    start == null -> formatScheduleDay(end!!)
    end == null || end == start -> formatScheduleDay(start)
    start.year == end.year -> "${monthDay(start.monthNumber, start.dayOfMonth)} – ${formatScheduleDay(end)}"
    else -> "${formatScheduleDay(start)} – ${formatScheduleDay(end)}"
}
