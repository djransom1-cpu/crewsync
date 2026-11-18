package com.djransom.crewsync.util

import com.djransom.crewsync.data.model.Appointment
import com.djransom.crewsync.data.model.PhaseStatus
import com.djransom.crewsync.data.model.SchedulePhase
import com.djransom.crewsync.data.model.Task
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.daysUntil
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime

// ---- The look-ahead ----------------------------------------------------------------------------
// "What's coming up": the phases running or starting, the site-calendar visits and the task cards
// due over the next week, two or three - with what's already behind on top. Pure, like the rest of
// the schedule's rules, so it can be unit-tested (see ScheduleLookAheadTest). The connector's
// look_ahead tool (functions/mcp.js) applies the same rules and returns the same groups as data.

/** The days the look-ahead covers, [first] through [last], both included. */
data class LookAheadWindow(val first: LocalDate, val last: LocalDate) {
    operator fun contains(day: LocalDate): Boolean = day in first..last
    val days: List<LocalDate> get() = (0..first.daysUntil(last)).map { first.plus(it, DateTimeUnit.DAY) }
}

/**
 * From today through the Sunday that ends week [weeks], counting this week as the first. "This
 * week" on a Wednesday is Wednesday to Sunday; two weeks runs on to the following Sunday.
 */
fun lookAheadWindow(today: LocalDate, weeks: Int): LookAheadWindow =
    LookAheadWindow(today, today.weekStart().plus(7 * weeks.coerceIn(1, 6) - 1, DateTimeUnit.DAY))

/** A phase that's running at some point in the window. */
data class PhaseInWindow(
    val phase: SchedulePhase,
    val start: LocalDate,
    val end: LocalDate,
    val startsInWindow: Boolean,
    val endsInWindow: Boolean,
    val isLate: Boolean
)

/** One occurrence of a site-calendar appointment. [minutes] is its time of day; null for an all-day one. */
data class AppointmentOccurrence(val appointment: Appointment, val day: LocalDate, val minutes: Int?)

/** A task card due on [due]. */
data class TaskDue(val task: Task, val due: LocalDate)

/** Why a phase is in the Behind list: it should have started by [since], or finished by [since]. */
enum class BehindReason { SHOULD_HAVE_STARTED, SHOULD_HAVE_FINISHED }

data class BehindPhase(val phase: SchedulePhase, val reason: BehindReason, val since: LocalDate)

/** Everything the look-ahead shows, before it's grouped. */
data class LookAhead(
    val window: LookAheadWindow,
    val behindPhases: List<BehindPhase>,
    val overdueTasks: List<TaskDue>,
    val phases: List<PhaseInWindow>,
    val appointments: List<AppointmentOccurrence>,
    val tasks: List<TaskDue>
) {
    val isEmpty: Boolean get() = behindPhases.isEmpty() && overdueTasks.isEmpty() && phases.isEmpty() && appointments.isEmpty() && tasks.isEmpty()
}

/**
 * Works out the look-ahead.
 *  - Phases: not Done, dated, and running on at least one day of the window.
 *  - Appointments: every occurrence in the window, repeating ones included (by the same rule as
 *    the Calendar tab). A visit spanning several days is listed once, on its first day in the window.
 *  - Task cards: due in the window and not in a Done-like bucket.
 *  - Behind: phases that should have started and haven't, or should have finished and haven't
 *    (the same "late" rule as the timeline), and task cards whose due date has passed.
 */
fun buildLookAhead(
    phases: List<SchedulePhase>,
    tasks: List<Task>,
    appointments: List<Appointment>,
    today: LocalDate,
    weeks: Int,
    zone: TimeZone = TimeZone.currentSystemDefault()
): LookAhead {
    val window = lookAheadWindow(today, weeks)

    val behind = phases.filter { it.isLate(today, zone) }.mapNotNull { phase ->
        val start = phase.startDate(zone)
        val end = phase.endDate(zone)
        when {
            phase.status == PhaseStatus.NOT_STARTED && start != null && today > start -> BehindPhase(phase, BehindReason.SHOULD_HAVE_STARTED, start)
            end != null -> BehindPhase(phase, BehindReason.SHOULD_HAVE_FINISHED, end)
            else -> null
        }
    }.sortedWith(compareBy<BehindPhase> { it.since }.thenBy { it.phase.name.lowercase() })

    val open = tasks.filter { !isDoneStatus(it.status) }
    val dueTasks = open.mapNotNull { task -> task.dueDate?.let { TaskDue(task, millisToLocalDate(it, zone)) } }
    val overdue = dueTasks.filter { it.due < today }.sortedWith(compareBy<TaskDue> { it.due }.thenBy { it.task.title.lowercase() })
    val dueInWindow = dueTasks.filter { it.due in window }

    val running = phases.mapNotNull { phase ->
        val start = phase.startDate(zone) ?: return@mapNotNull null
        val end = phase.endDate(zone) ?: return@mapNotNull null
        if (phase.status == PhaseStatus.DONE || end < window.first || start > window.last) return@mapNotNull null
        PhaseInWindow(phase, start, end, start in window, end in window, phase.isLate(today, zone))
    }

    val visits = appointments.flatMap { appt ->
        val minutes = if (appt.isAllDay) null else Instant.fromEpochMilliseconds(appt.startDate).toLocalDateTime(zone).let { it.hour * 60 + it.minute }
        appt.occurrenceDaysIn(window, zone).map { AppointmentOccurrence(appt, it, minutes) }
    }

    return LookAhead(window, behind, overdue, running, visits, dueInWindow)
}

/** The days in [window] this appointment happens on: the Calendar tab's rule for repeats (daily, same weekday, same day of the month). */
fun Appointment.occurrenceDaysIn(window: LookAheadWindow, zone: TimeZone = TimeZone.currentSystemDefault()): List<LocalDate> {
    val start = millisToLocalDate(startDate, zone)
    val end = millisToLocalDate(if (endDate >= startDate) endDate else startDate, zone)
    return when (recurrence) {
        "Daily", "Weekly", "Monthly" -> window.days.filter { day ->
            day >= start && when (recurrence) {
                "Daily" -> true
                "Weekly" -> day.dayOfWeek == start.dayOfWeek
                else -> day.dayOfMonth == start.dayOfMonth
            }
        }
        else -> if (end >= window.first && start <= window.last) listOf(maxOf(start, window.first)) else emptyList()
    }
}

// ---- Grouping ----------------------------------------------------------------------------------

/** How a phase appears in a group: starting or finishing that day, under way all through the window, or (by trade) simply running. */
enum class PhaseRole { STARTS, FINISHES, UNDER_WAY, RUNNING }

/** One line of the look-ahead. Every line has the [day] it's listed under. */
sealed class LookAheadEntry {
    abstract val day: LocalDate

    data class Phase(val item: PhaseInWindow, val role: PhaseRole, override val day: LocalDate) : LookAheadEntry()
    data class Visit(val item: AppointmentOccurrence) : LookAheadEntry() { override val day: LocalDate get() = item.day }
    data class Due(val item: TaskDue) : LookAheadEntry() { override val day: LocalDate get() = item.due }
}

/**
 * A heading and its lines. Grouped by day, [day] is set - except for the "under way" group at the
 * top, which has neither. Grouped by trade, [trade] is set, and is "" for the "no trade" group.
 */
data class LookAheadGroup(val day: LocalDate?, val trade: String?, val entries: List<LookAheadEntry>)

/** Visits first (all-day before timed, then by time), then phases (starting before finishing), then task cards; ties by name. */
private val entryOrder: Comparator<LookAheadEntry> = compareBy<LookAheadEntry> { it.day }
    .thenBy { when (it) { is LookAheadEntry.Visit -> 0; is LookAheadEntry.Phase -> 1; is LookAheadEntry.Due -> 2 } }
    .thenBy { (it as? LookAheadEntry.Visit)?.item?.minutes ?: -1 }
    .thenBy { (it as? LookAheadEntry.Phase)?.role?.ordinal ?: 0 }
    .thenBy { entryName(it).lowercase() }

private fun entryName(entry: LookAheadEntry): String = when (entry) {
    is LookAheadEntry.Phase -> entry.item.phase.name
    is LookAheadEntry.Visit -> entry.item.appointment.title
    is LookAheadEntry.Due -> entry.item.task.title
}

/**
 * By day: first the phases under way right through the window (they neither start nor finish in
 * it), then each day that has something on it - its visits, the phases starting or finishing,
 * and the task cards due. A phase that starts and finishes inside the window is listed on both days.
 */
fun LookAhead.byDay(): List<LookAheadGroup> {
    val underWay = phases.filter { !it.startsInWindow && !it.endsInWindow }
        .map { LookAheadEntry.Phase(it, PhaseRole.UNDER_WAY, window.first) }
        .sortedWith(entryOrder)
    val dated = buildList {
        phases.forEach { p ->
            if (p.startsInWindow) add(LookAheadEntry.Phase(p, PhaseRole.STARTS, p.start))
            if (p.endsInWindow) add(LookAheadEntry.Phase(p, PhaseRole.FINISHES, p.end))
        }
        appointments.forEach { add(LookAheadEntry.Visit(it)) }
        tasks.forEach { add(LookAheadEntry.Due(it)) }
    }
    val days = dated.groupBy { it.day }.entries.sortedBy { it.key }.map { (day, entries) -> LookAheadGroup(day, null, entries.sortedWith(entryOrder)) }
    return (if (underWay.isEmpty()) emptyList() else listOf(LookAheadGroup(null, null, underWay))) + days
}

/**
 * By trade: each phase's trade, with the visits and task cards linked to that phase under it. A
 * visit or card on no phase - or on a phase with no trade - goes in the "no trade" group, which
 * comes last; the others come in order of their first day, then by name. Trades match ignoring
 * case and spacing.
 */
fun LookAhead.byTrade(allPhases: List<SchedulePhase>): List<LookAheadGroup> {
    fun tradeOf(phase: SchedulePhase?): String = phase?.trade?.trim().orEmpty()
    val entries = buildList {
        phases.forEach { add(tradeOf(it.phase) to LookAheadEntry.Phase(it, PhaseRole.RUNNING, maxOf(it.start, window.first))) }
        appointments.forEach { add(tradeOf(phaseOfAppointment(allPhases, it.appointment.id)) to LookAheadEntry.Visit(it)) }
        tasks.forEach { add(tradeOf(phaseOfTask(allPhases, it.task.id)) to LookAheadEntry.Due(it)) }
    }
    val names = HashMap<String, String>()
    entries.forEach { (trade, _) -> names.getOrPut(trade.lowercase().split(" ").filter { it.isNotEmpty() }.joinToString(" ")) { trade } }
    val groups = entries.groupBy { (trade, _) -> trade.lowercase().split(" ").filter { it.isNotEmpty() }.joinToString(" ") }
        .map { (key, list) -> LookAheadGroup(null, names[key].orEmpty(), list.map { it.second }.sortedWith(entryOrder)) }
    val (named, none) = groups.partition { it.trade!!.isNotEmpty() }
    return named.sortedWith(compareBy<LookAheadGroup> { it.entries.first().day }.thenBy { it.trade!!.lowercase() }) + none
}
