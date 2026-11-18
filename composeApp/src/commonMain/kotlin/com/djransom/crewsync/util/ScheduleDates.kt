package com.djransom.crewsync.util

import com.djransom.crewsync.data.model.PhaseStatus
import com.djransom.crewsync.data.model.PredecessorType
import com.djransom.crewsync.data.model.Project
import com.djransom.crewsync.data.model.SchedulePhase
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.math.abs

// ---- Schedule date math ------------------------------------------------------------------------
// Everything here works in whole calendar days (LocalDate) and is pure - no Firestore, no clock,
// no UI - so it can be unit-tested (see ScheduleDatesTest) and reused by every schedule screen.
// Phase dates are STORED as local-midnight epoch millis, the same way Task.startDate/dueDate are;
// convert at the edges with millisToLocalDate / toLocalMidnightMillis and do the math in between.

/**
 * Which days count as working days on a project: the working week minus the project's holidays.
 * Build one from a project with [workCalendar]; the default is Monday-Friday with no holidays.
 */
data class WorkCalendar(
    val workDays: Set<DayOfWeek> = DEFAULT_WORK_DAYS,
    val holidays: Set<LocalDate> = emptySet()
) {
    // An empty working week would make every "find the next working day" walk run forever, so a
    // calendar with no working days at all quietly behaves as Monday-Friday instead.
    private val effectiveWorkDays: Set<DayOfWeek> get() = workDays.ifEmpty { DEFAULT_WORK_DAYS }

    fun isWorkingDay(date: LocalDate): Boolean = date.dayOfWeek in effectiveWorkDays && date !in holidays

    /** [date] itself if it's a working day, otherwise the next one after it. */
    fun onOrAfter(date: LocalDate): LocalDate {
        var d = date
        var guard = 0
        while (!isWorkingDay(d) && guard++ < MAX_SCAN_DAYS) d = d.plus(1, DateTimeUnit.DAY)
        return d
    }

    /** [date] itself if it's a working day, otherwise the last one before it. */
    fun onOrBefore(date: LocalDate): LocalDate {
        var d = date
        var guard = 0
        while (!isWorkingDay(d) && guard++ < MAX_SCAN_DAYS) d = d.minus(1, DateTimeUnit.DAY)
        return d
    }

    /**
     * Moves [n] working days from [date], skipping weekends and holidays. Positive goes forward,
     * negative goes back, and 0 returns [date] itself (or the next working day if it isn't one).
     * Friday + 1 is Monday; Monday - 1 is Friday.
     */
    fun addWorkingDays(date: LocalDate, n: Int): LocalDate {
        if (n == 0) return onOrAfter(date)
        var d = date
        repeat(abs(n)) {
            d = if (n > 0) onOrAfter(d.plus(1, DateTimeUnit.DAY)) else onOrBefore(d.minus(1, DateTimeUnit.DAY))
        }
        return d
    }

    /**
     * How many working days it takes to step from [from] to [to]: forward, the working days after
     * [from] up to and including [to]; backward, the same count negated, so it always undoes
     * [addWorkingDays]. Friday to the next Monday is 1, and Monday back to that Friday is -1.
     * This is the "slip" measure - see [slipDays].
     */
    fun workingDaysBetween(from: LocalDate, to: LocalDate): Int {
        if (to == from) return 0
        if (to > from) return countWorkingDays(from.plus(1, DateTimeUnit.DAY), to)
        // Going backward, the day you land on counts and the day you leave doesn't.
        return -countWorkingDays(to, from.minus(1, DateTimeUnit.DAY))
    }

    /** Working days from [first] through [last], both included. 0 if [last] is before [first]. */
    private fun countWorkingDays(first: LocalDate, last: LocalDate): Int {
        var count = 0
        var d = first
        while (d <= last) {
            if (isWorkingDay(d)) count++
            d = d.plus(1, DateTimeUnit.DAY)
        }
        return count
    }

    /** A phase's length: the working days from [start] through [end], both included. 0 if end is before start. */
    fun durationDays(start: LocalDate, end: LocalDate): Int = countWorkingDays(start, end)

    /**
     * The last working day of a phase that starts on [start] and runs [durationDays] working days.
     * A one-day phase (or a milestone) ends the day it starts. A start that falls on a weekend
     * or holiday is treated as starting on the next working day.
     */
    fun endForDuration(start: LocalDate, durationDays: Int): LocalDate =
        addWorkingDays(onOrAfter(start), durationDays.coerceAtLeast(1) - 1)

    /**
     * The earliest day a phase may start given one predecessor.
     *  - FS (finish-to-start): the working day after the predecessor's last day, plus [lagDays].
     *    Lag -1 starts on the predecessor's last day; lag -2 the working day before that.
     *  - SS (start-to-start): the predecessor's first day, plus [lagDays].
     */
    fun successorStart(predecessorStart: LocalDate, predecessorEnd: LocalDate, type: String, lagDays: Int): LocalDate =
        if (PredecessorType.normalize(type) == PredecessorType.SS) {
            addWorkingDays(onOrAfter(predecessorStart), lagDays)
        } else {
            addWorkingDays(onOrBefore(predecessorEnd), 1 + lagDays)
        }

    companion object {
        val DEFAULT_WORK_DAYS: Set<DayOfWeek> = setOf(
            DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY
        )

        // Longest run of non-working days we'll walk across before giving up - far more than any
        // real holiday shutdown, just a stop so bad data can never hang a screen.
        private const val MAX_SCAN_DAYS = 400
    }
}

/** Local-midnight epoch millis (how phase and task dates are stored) to the calendar day they mean. */
fun millisToLocalDate(millis: Long, zone: TimeZone = TimeZone.currentSystemDefault()): LocalDate =
    Instant.fromEpochMilliseconds(millis).toLocalDateTime(zone).date

/** A calendar day to the local-midnight epoch millis it's stored as. */
fun LocalDate.toLocalMidnightMillis(zone: TimeZone = TimeZone.currentSystemDefault()): Long =
    atStartOfDayIn(zone).toEpochMilliseconds()

/** Project.workWeek's ISO day numbers (1 = Monday ... 7 = Sunday) as days; anything out of range is ignored. */
fun workDaysFromIsoNumbers(numbers: List<Int>): Set<DayOfWeek> {
    val week = listOf(
        DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY,
        DayOfWeek.FRIDAY, DayOfWeek.SATURDAY, DayOfWeek.SUNDAY
    )
    return numbers.mapNotNull { week.getOrNull(it - 1) }.toSet()
}

/** Project.scheduleHolidays ("YYYY-MM-DD" strings) as dates; anything unreadable is ignored. */
fun parseHolidays(dates: List<String>): Set<LocalDate> = dates.mapNotNull { parseLogDate(it.trim()) }.toSet()

/** The working calendar for this project: its work week (Monday-Friday unless set) minus its holidays. */
fun Project.workCalendar(): WorkCalendar = WorkCalendar(
    workDays = workDaysFromIsoNumbers(workWeek).ifEmpty { WorkCalendar.DEFAULT_WORK_DAYS },
    holidays = parseHolidays(scheduleHolidays)
)

// ---- Worked out from a phase, never stored -----------------------------------------------------

fun SchedulePhase.startDate(zone: TimeZone = TimeZone.currentSystemDefault()): LocalDate? =
    start?.let { millisToLocalDate(it, zone) }

fun SchedulePhase.endDate(zone: TimeZone = TimeZone.currentSystemDefault()): LocalDate? =
    end?.let { millisToLocalDate(it, zone) }

/**
 * Working days the phase's finish has moved against the original plan: positive is late,
 * negative is ahead, 0 is on plan. Null when the phase has no end date or no baseline yet.
 */
fun SchedulePhase.slipDays(calendar: WorkCalendar, zone: TimeZone = TimeZone.currentSystemDefault()): Int? {
    val planned = baselineEnd?.let { millisToLocalDate(it, zone) } ?: return null
    val current = endDate(zone) ?: return null
    return calendar.workingDaysBetween(planned, current)
}

/**
 * Behind schedule as of [today]: the start date has passed and it still hasn't started, or the
 * end date has passed and it isn't done.
 */
fun SchedulePhase.isLate(today: LocalDate, zone: TimeZone = TimeZone.currentSystemDefault()): Boolean {
    val s = startDate(zone)
    val e = endDate(zone)
    val notStartedPastStart = status == PhaseStatus.NOT_STARTED && s != null && today > s
    val notDonePastEnd = status != PhaseStatus.DONE && e != null && today > e
    return notStartedPastStart || notDonePastEnd
}
