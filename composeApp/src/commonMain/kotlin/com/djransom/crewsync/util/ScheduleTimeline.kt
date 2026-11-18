package com.djransom.crewsync.util

import com.djransom.crewsync.data.model.Appointment
import com.djransom.crewsync.data.model.PhaseStatus
import com.djransom.crewsync.data.model.SchedulePhase
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.daysUntil
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

// ---- Laying the schedule out on a timeline -----------------------------------------------------
// The arithmetic behind the Gantt view: which stretch of calendar it covers, where each bar sits
// on it, what "Fit" frames, and the rows it draws. Everything is in whole days and plain numbers -
// the screen turns days into pixels - so it can be unit-tested (see ScheduleTimelineTest).

/** Today's date where the device is. */
fun scheduleToday(zone: TimeZone = TimeZone.currentSystemDefault()): LocalDate =
    Instant.fromEpochMilliseconds(Clock.System.now().toEpochMilliseconds()).toLocalDateTime(zone).date

/** The Monday on or before this date - where its week column starts. */
fun LocalDate.weekStart(): LocalDate = minus(dayOfWeek.isoDayNumber - 1, DateTimeUnit.DAY)

/** One month's slice of the timeline: where it starts (in days from the left edge) and how many days of it show. */
data class TimelineMonth(val year: Int, val month: Int, val offsetDays: Int, val lengthDays: Int)

/** Where a bar sits: days from the left edge, and how many days long (the end day is included). */
data class TimelineBar(val offsetDays: Int, val lengthDays: Int)

/**
 * The stretch of calendar the timeline covers, [first] through [last] inclusive. It always starts
 * on a Monday and ends on a Sunday so the week columns come out whole.
 */
data class TimelineSpan(val first: LocalDate, val last: LocalDate) {
    val days: Int get() = first.daysUntil(last) + 1

    /** Days from the left edge to [date]; negative or past [days] when the date is off the timeline. */
    fun offsetOf(date: LocalDate): Int = first.daysUntil(date)

    operator fun contains(date: LocalDate): Boolean = date in first..last

    /** The Monday that starts each week column, left to right. */
    val weeks: List<LocalDate> get() = (0 until days / 7).map { first.plus(it * 7, DateTimeUnit.DAY) }

    /** Each month the timeline touches, clipped to the part that shows. */
    val months: List<TimelineMonth>
        get() {
            val out = mutableListOf<TimelineMonth>()
            var start = first
            while (start <= last) {
                val nextMonth = LocalDate(start.year, start.monthNumber, 1).plus(1, DateTimeUnit.MONTH)
                val end = minOf(last, nextMonth.minus(1, DateTimeUnit.DAY))
                out += TimelineMonth(start.year, start.monthNumber, offsetOf(start), start.daysUntil(end) + 1)
                start = nextMonth
            }
            return out
        }

    /** The bar for a phase running [start] through [end]. Null when a date is missing; a phase that ends before it starts shows as one day. */
    fun bar(start: LocalDate?, end: LocalDate?): TimelineBar? {
        if (start == null || end == null) return null
        return TimelineBar(offsetOf(start), (start.daysUntil(end) + 1).coerceAtLeast(1))
    }
}

/** Day width and left edge that "Fit" lands on. */
data class TimelineFit(val dayWidth: Float, val firstDayOffset: Int)

const val TIMELINE_WEEKS_DAY_WIDTH = 12f   // dp per day: a week column 84dp wide, about 13 weeks on a laptop
const val TIMELINE_MONTHS_DAY_WIDTH = 3f   // dp per day: a month about 90dp wide, about a year on a laptop
const val TIMELINE_MIN_DAY_WIDTH = 1.5f
const val TIMELINE_MAX_DAY_WIDTH = 40f

/**
 * The stretch of calendar that holds every phase and its baseline, with [padWeeks] of breathing
 * room at each end. Today is included too, unless the whole schedule is more than half a year
 * away from it (an old job, or one planned far ahead), where it would only add blank space.
 * Null when no phase has a date yet.
 */
fun timelineSpan(phases: List<SchedulePhase>, today: LocalDate, zone: TimeZone = TimeZone.currentSystemDefault(), padWeeks: Int = 1): TimelineSpan? {
    val dates = phases.flatMap { listOfNotNull(it.start, it.end, it.baselineStart, it.baselineEnd) }.map { millisToLocalDate(it, zone) }
    if (dates.isEmpty()) return null
    var earliest = dates.min()
    var latest = dates.max()
    val halfYear = 26 * 7
    if (earliest.daysUntil(today) >= -halfYear && today.daysUntil(latest) >= -halfYear) {
        earliest = minOf(earliest, today)
        latest = maxOf(latest, today)
    }
    val first = earliest.minus(padWeeks * 7, DateTimeUnit.DAY).weekStart()
    val last = latest.plus(padWeeks * 7, DateTimeUnit.DAY).weekStart().plus(6, DateTimeUnit.DAY)
    return TimelineSpan(first, last)
}

/** The last day any phase ends, by its current dates. Null when nothing is dated. */
fun lastPhaseEnd(phases: List<SchedulePhase>, zone: TimeZone = TimeZone.currentSystemDefault()): LocalDate? =
    phases.mapNotNull { it.end ?: it.start }.maxOrNull()?.let { millisToLocalDate(it, zone) }

/**
 * What the Fit button frames: today through the last phase, with a few days of margin, squeezed
 * or stretched to fill a window [viewportWidth] dp wide. If the schedule is already finished
 * (or today is off the timeline) it frames the whole thing instead.
 */
fun TimelineSpan.fit(today: LocalDate, lastEnd: LocalDate?, viewportWidth: Float): TimelineFit {
    val margin = 3
    val whole = lastEnd == null || today !in this || today > lastEnd
    val from = if (whole) first else maxOf(first, today.minus(margin, DateTimeUnit.DAY))
    val to = if (whole) last else minOf(last, lastEnd!!.plus(margin, DateTimeUnit.DAY))
    val shown = (from.daysUntil(to) + 1).coerceAtLeast(1)
    val dayWidth = (viewportWidth / shown).coerceIn(TIMELINE_MIN_DAY_WIDTH, TIMELINE_MAX_DAY_WIDTH)
    return TimelineFit(dayWidth, offsetOf(from).coerceAtLeast(0))
}

/** One row of the schedule, in the list or on the timeline: a group heading or a phase. */
sealed class ScheduleRow {
    data class GroupHead(val name: String, val count: Int) : ScheduleRow()
    data class PhaseLine(val phase: SchedulePhase) : ScheduleRow()
}

/**
 * The rows top to bottom: each group's heading, then its phases in schedule order. When nothing
 * has been grouped at all (one band with no name) the heading is left out.
 */
fun scheduleRows(phases: List<SchedulePhase>): List<ScheduleRow> {
    val groups = groupPhases(phases)
    val showHeads = groups.size > 1 || groups.firstOrNull()?.name?.isNotEmpty() == true
    return groups.flatMap { group ->
        (if (showHeads) listOf(ScheduleRow.GroupHead(group.name, group.phases.size)) else emptyList()) +
            group.phases.map { ScheduleRow.PhaseLine(it) }
    }
}

// ---- A phase's next site visit -----------------------------------------------------------------

/**
 * The first day on or after [day] that this appointment happens, by the same rules the Calendar
 * tab uses: a repeating appointment recurs daily, on its weekday, or on its day of the month from
 * its start date on. Null when it's over and doesn't repeat.
 */
fun Appointment.nextOccurrence(day: LocalDate, zone: TimeZone = TimeZone.currentSystemDefault()): LocalDate? {
    val start = millisToLocalDate(startDate, zone)
    val end = millisToLocalDate(if (endDate >= startDate) endDate else startDate, zone)
    if (day <= end) return maxOf(day, start)           // still to come, or under way today
    var d = day
    repeat(400) {                                        // more than a year: enough for "the 31st"
        val hit = when (recurrence) {
            "Daily" -> true
            "Weekly" -> d.dayOfWeek == start.dayOfWeek
            "Monthly" -> d.dayOfMonth == start.dayOfMonth
            else -> return null
        }
        if (hit) return d
        d = d.plus(1, DateTimeUnit.DAY)
    }
    return null
}

/** The next linked appointment for a phase and the day it falls on; the earliest time of day wins a tie. Null when there's none ahead. */
fun SchedulePhase.nextLinkedAppointment(
    appointmentsById: Map<String, Appointment>,
    today: LocalDate,
    zone: TimeZone = TimeZone.currentSystemDefault()
): Pair<Appointment, LocalDate>? =
    appointmentIds.mapNotNull { appointmentsById[it] }
        .mapNotNull { appt -> appt.nextOccurrence(today, zone)?.let { appt to it } }
        .minWithOrNull(compareBy<Pair<Appointment, LocalDate>> { it.second }.thenBy { timeOfDayMinutes(it.first.startDate, zone) })

private fun timeOfDayMinutes(millis: Long, zone: TimeZone): Int =
    Instant.fromEpochMilliseconds(millis).toLocalDateTime(zone).let { it.hour * 60 + it.minute }

// ---- Presentation mode -------------------------------------------------------------------------

/**
 * What presentation mode opens on: [weeksBack] whole weeks before this week through [weeksAhead]
 * weeks after it, filling a window [viewportWidth] dp wide. The left edge is a Monday so the week
 * columns start cleanly. If the schedule begins later than that, the left edge is its first week.
 */
fun TimelineSpan.around(today: LocalDate, weeksBack: Int, weeksAhead: Int, viewportWidth: Float): TimelineFit {
    val from = today.weekStart().minus(weeksBack * 7, DateTimeUnit.DAY)
    val shown = ((weeksBack + weeksAhead) * 7).coerceAtLeast(7)
    val dayWidth = (viewportWidth / shown).coerceIn(TIMELINE_MIN_DAY_WIDTH, TIMELINE_MAX_DAY_WIDTH)
    return TimelineFit(dayWidth, offsetOf(from).coerceIn(0, days - 1))
}

/**
 * The numbers on the presentation header: how many phases are done and in progress, and where
 * the finish stands against the plan. The finish is the latest end date of any phase; the planned
 * finish is the latest baseline end. [finishSlipDays] is the working days between them (positive
 * is late) and is null until at least one phase has both a date and a baseline. The connector
 * reports the same figures from list_schedule (scheduleTotals in functions/mcp.js).
 */
data class ScheduleTotals(
    val total: Int,
    val done: Int,
    val inProgress: Int,
    val plannedFinish: LocalDate?,
    val currentFinish: LocalDate?,
    val finishSlipDays: Int?
)

fun scheduleTotals(phases: List<SchedulePhase>, calendar: WorkCalendar, zone: TimeZone = TimeZone.currentSystemDefault()): ScheduleTotals {
    val current = phases.mapNotNull { it.end }.maxOrNull()?.let { millisToLocalDate(it, zone) }
    val planned = phases.mapNotNull { it.baselineEnd }.maxOrNull()?.let { millisToLocalDate(it, zone) }
    return ScheduleTotals(
        total = phases.size,
        done = phases.count { it.status == PhaseStatus.DONE },
        inProgress = phases.count { it.status == PhaseStatus.IN_PROGRESS },
        plannedFinish = planned,
        currentFinish = current,
        finishSlipDays = if (planned != null && current != null) calendar.workingDaysBetween(planned, current) else null
    )
}
