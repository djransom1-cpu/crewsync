package com.djransom.crewsync.util

import com.djransom.crewsync.data.model.PhaseStatus
import com.djransom.crewsync.data.model.Project
import com.djransom.crewsync.data.model.SchedulePhase
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The schedule's working-day math. Run with: gradlew :composeApp:desktopTest
 *
 * Calendar used throughout (2026): Nov 1 is a Sunday, so Nov 20 is a Friday, Nov 23 a Monday,
 * Thanksgiving is Thursday Nov 26, and Dec 1 is a Tuesday.
 */
class ScheduleDatesTest {
    private fun d(month: Int, day: Int, year: Int = 2026) = LocalDate(year, month, day)

    private val plain = WorkCalendar()
    private val thanksgiving = WorkCalendar(holidays = setOf(d(11, 26), d(11, 27)))

    // ---- across a weekend ----

    @Test
    fun addingWorkingDaysSkipsTheWeekend() {
        assertEquals(d(11, 23), plain.addWorkingDays(d(11, 20), 1), "Friday + 1 is Monday")
        assertEquals(d(11, 20), plain.addWorkingDays(d(11, 23), -1), "Monday - 1 is Friday")
        assertEquals(d(11, 27), plain.addWorkingDays(d(11, 20), 5), "a full working week later")
        assertEquals(d(11, 13), plain.addWorkingDays(d(11, 20), -5))
    }

    @Test
    fun addingFromAWeekendDayLandsOnAWorkingDay() {
        assertEquals(d(11, 23), plain.addWorkingDays(d(11, 21), 0), "Saturday + 0 is the next Monday")
        assertEquals(d(11, 23), plain.addWorkingDays(d(11, 21), 1), "Saturday + 1 is Monday")
        assertEquals(d(11, 20), plain.addWorkingDays(d(11, 22), -1), "Sunday - 1 is Friday")
        assertEquals(d(11, 20), plain.addWorkingDays(d(11, 20), 0), "a working day + 0 stays put")
    }

    @Test
    fun durationCountsWorkingDaysInclusive() {
        assertEquals(4, plain.durationDays(d(11, 19), d(11, 24)), "Thu, Fri, Mon, Tue")
        assertEquals(d(11, 24), plain.endForDuration(d(11, 19), 4))
        assertEquals(5, plain.durationDays(d(11, 16), d(11, 22)), "ending on a Sunday still counts Mon-Fri only")
        assertEquals(0, plain.durationDays(d(11, 24), d(11, 19)), "end before start")
    }

    @Test
    fun workingDaysBetweenIsSignedAndUndoesAddWorkingDays() {
        assertEquals(1, plain.workingDaysBetween(d(11, 20), d(11, 23)), "Friday to Monday")
        assertEquals(-1, plain.workingDaysBetween(d(11, 23), d(11, 20)), "Monday back to Friday")
        assertEquals(0, plain.workingDaysBetween(d(11, 20), d(11, 20)))
        for (n in -12..12) {
            val moved = plain.addWorkingDays(d(11, 18), n)
            assertEquals(n, plain.workingDaysBetween(d(11, 18), moved), "round trip for $n")
        }
    }

    // ---- across Thanksgiving ----

    @Test
    fun holidaysAreSkippedLikeWeekends() {
        assertFalse(thanksgiving.isWorkingDay(d(11, 26)))
        assertEquals(d(11, 30), thanksgiving.addWorkingDays(d(11, 25), 1), "Wednesday + 1 jumps Thu, Fri, Sat, Sun")
        assertEquals(d(11, 25), thanksgiving.addWorkingDays(d(11, 30), -1))
        assertEquals(4, thanksgiving.durationDays(d(11, 23), d(11, 30)), "Mon, Tue, Wed, then the next Mon")
        assertEquals(d(12, 1), thanksgiving.endForDuration(d(11, 23), 5))
        assertEquals(2, thanksgiving.workingDaysBetween(d(11, 25), d(12, 1)))
        // The same week without the holiday is a normal five days.
        assertEquals(6, plain.durationDays(d(11, 23), d(11, 30)))
    }

    @Test
    fun aPhaseStartingOnAHolidayStartsTheNextWorkingDay() {
        assertEquals(d(11, 30), thanksgiving.onOrAfter(d(11, 26)))
        assertEquals(d(11, 25), thanksgiving.onOrBefore(d(11, 27)))
        assertEquals(d(12, 1), thanksgiving.endForDuration(d(11, 26), 2), "starts Mon Nov 30, ends Tue Dec 1")
    }

    // ---- predecessors and lag ----

    @Test
    fun finishToStartBeginsTheWorkingDayAfterThePredecessorEnds() {
        val start = d(11, 23)
        val end = d(12, 4) // a Friday
        assertEquals(d(12, 7), plain.successorStart(start, end, "FS", 0), "the Monday after")
        assertEquals(d(12, 9), plain.successorStart(start, end, "FS", 2))
        assertEquals(d(12, 7), plain.successorStart(start, end, "", 0), "FS is the default type")
    }

    @Test
    fun negativeLagOverlapsThePredecessor() {
        val start = d(11, 23)
        val end = d(12, 4) // a Friday
        assertEquals(d(12, 4), plain.successorStart(start, end, "FS", -1), "starts on the last day")
        assertEquals(d(12, 3), plain.successorStart(start, end, "FS", -2))
        assertEquals(d(11, 27), plain.successorStart(start, end, "FS", -6), "back across a weekend")
        // Back across Thanksgiving: predecessor ends Mon Nov 30, lag -2 lands on Wed Nov 25.
        assertEquals(d(11, 25), thanksgiving.successorStart(d(11, 16), d(11, 30), "FS", -2))
    }

    @Test
    fun startToStartFollowsThePredecessorsStart() {
        val start = d(11, 23) // a Monday
        val end = d(12, 4)
        assertEquals(d(11, 23), plain.successorStart(start, end, "SS", 0))
        assertEquals(d(11, 25), plain.successorStart(start, end, "SS", 2))
        assertEquals(d(11, 20), plain.successorStart(start, end, "SS", -1), "the Friday before")
        assertEquals(d(11, 25), plain.successorStart(start, end, "ss", 2), "type is not case-sensitive")
    }

    @Test
    fun aPredecessorEndingOnAWeekendCountsFromItsLastWorkingDay() {
        // Siding in the Martin plan ends Sunday Nov 15.
        assertEquals(d(11, 16), plain.successorStart(d(9, 21), d(11, 15), "FS", 0))
        assertEquals(d(11, 13), plain.successorStart(d(9, 21), d(11, 15), "FS", -1))
    }

    // ---- a one-day milestone ----

    @Test
    fun aMilestoneIsOneDayLong() {
        val chimney = d(9, 25) // "Exterior walls at chimney", a Friday
        assertEquals(1, plain.durationDays(chimney, chimney))
        assertEquals(chimney, plain.endForDuration(chimney, 1))
        assertEquals(chimney, plain.endForDuration(chimney, 0), "a zero or missing duration is still one day")
        assertEquals(d(9, 28), plain.endForDuration(d(9, 26), 1), "a milestone set on a Saturday shows on Monday")
        assertEquals(d(9, 28), plain.successorStart(chimney, chimney, "FS", 0), "what follows starts the next working day")
    }

    // ---- slip against the baseline ----

    @Test
    fun slipIsWorkingDaysBetweenBaselineEndAndCurrentEnd() {
        val zone = TimeZone.UTC
        val roof = SchedulePhase(
            name = "Roof complete",
            baselineEnd = d(10, 15).toLocalMidnightMillis(zone),
            end = d(12, 11).toLocalMidnightMillis(zone)
        )
        assertEquals(41, roof.slipDays(plain, zone), "Oct 15 to Dec 11 with no holidays")
        assertEquals(39, roof.slipDays(thanksgiving, zone), "less Thanksgiving Thursday and Friday")
        assertEquals(0, roof.copy(end = roof.baselineEnd).slipDays(plain, zone))
        assertEquals(-4, roof.copy(end = d(10, 9).toLocalMidnightMillis(zone)).slipDays(plain, zone), "finishing early is negative")
        assertNull(roof.copy(baselineEnd = null).slipDays(plain, zone), "no baseline, no slip")
        assertNull(roof.copy(end = null).slipDays(plain, zone))
    }

    // ---- late ----

    @Test
    fun lateMeansPastStartAndNotStartedOrPastEndAndNotDone() {
        val zone = TimeZone.UTC
        val phase = SchedulePhase(
            start = d(11, 19).toLocalMidnightMillis(zone),
            end = d(12, 4).toLocalMidnightMillis(zone)
        )
        assertFalse(phase.isLate(d(11, 19), zone), "not late on its start day")
        assertTrue(phase.isLate(d(11, 20), zone), "a day past its start and still not started")
        val started = phase.copy(status = PhaseStatus.IN_PROGRESS)
        assertFalse(started.isLate(d(12, 4), zone), "in progress on its last day")
        assertTrue(started.isLate(d(12, 5), zone), "past its end and not done")
        assertTrue(phase.copy(status = PhaseStatus.ON_HOLD).isLate(d(12, 5), zone))
        assertFalse(phase.copy(status = PhaseStatus.DONE).isLate(d(12, 30), zone), "done is never late")
        assertFalse(SchedulePhase().isLate(d(12, 30), zone), "a phase with no dates is never late")
    }

    // ---- stored dates ----

    @Test
    fun datesRoundTripThroughLocalMidnightMillis() {
        val newYork = TimeZone.of("America/New_York")
        // Includes both 2026 clock changes (Mar 8 and Nov 1), where a day is 23 or 25 hours long.
        val days = listOf(d(3, 7), d(3, 8), d(3, 9), d(10, 31), d(11, 1), d(11, 2), d(12, 1), d(1, 4, 2027))
        for (day in days) {
            assertEquals(day, millisToLocalDate(day.toLocalMidnightMillis(newYork), newYork), "round trip for $day")
        }
        val hour = 3_600_000L
        assertEquals(5 * hour, d(12, 1).toLocalMidnightMillis(newYork) - d(12, 1).toLocalMidnightMillis(TimeZone.UTC), "winter is UTC-5")
        assertEquals(4 * hour, d(7, 1).toLocalMidnightMillis(newYork) - d(7, 1).toLocalMidnightMillis(TimeZone.UTC), "summer is UTC-4")
    }

    // ---- the project's calendar ----

    @Test
    fun aProjectWithNoCalendarSettingsWorksMondayToFriday() {
        val calendar = Project().workCalendar()
        assertEquals(WorkCalendar.DEFAULT_WORK_DAYS, calendar.workDays)
        assertTrue(calendar.holidays.isEmpty())
        assertTrue(calendar.isWorkingDay(d(11, 20)))
        assertFalse(calendar.isWorkingDay(d(11, 21)))
    }

    @Test
    fun aProjectCanSetItsOwnWorkWeekAndHolidays() {
        val calendar = Project(
            workWeek = listOf(1, 2, 3, 4, 5, 6),
            scheduleHolidays = listOf("2026-11-26", "not a date", " 2026-12-25 ")
        ).workCalendar()
        assertTrue(DayOfWeek.SATURDAY in calendar.workDays)
        assertEquals(setOf(d(11, 26), d(12, 25)), calendar.holidays, "unreadable dates are ignored")
        assertEquals(d(11, 21), calendar.addWorkingDays(d(11, 20), 1), "Saturday is a working day here")
        assertEquals(d(11, 27), calendar.addWorkingDays(d(11, 25), 1), "skips only Thanksgiving Thursday")
    }

    @Test
    fun aNonsenseWorkWeekFallsBackToMondayToFriday() {
        assertEquals(WorkCalendar.DEFAULT_WORK_DAYS, Project(workWeek = emptyList()).workCalendar().workDays)
        assertEquals(WorkCalendar.DEFAULT_WORK_DAYS, Project(workWeek = listOf(0, 8, -3)).workCalendar().workDays)
        // Built directly with no working days, the walk still ends instead of running forever.
        assertEquals(d(11, 23), WorkCalendar(workDays = emptySet()).addWorkingDays(d(11, 20), 1))
    }
}
