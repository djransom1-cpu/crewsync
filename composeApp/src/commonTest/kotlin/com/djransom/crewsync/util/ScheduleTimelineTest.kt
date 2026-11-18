package com.djransom.crewsync.util

import com.djransom.crewsync.data.model.Appointment
import com.djransom.crewsync.data.model.SchedulePhase
import com.djransom.crewsync.util.ScheduleSampleData.day
import com.djransom.crewsync.util.ScheduleSampleData.martinInProgress
import com.djransom.crewsync.util.ScheduleSampleData.martinPlan
import com.djransom.crewsync.util.ScheduleSampleData.zone
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The arithmetic behind the timeline (Gantt) view, using the Martin-shaped sample schedule with
 * "today" pinned to Wednesday Nov 18, 2026. Run with: gradlew :composeApp:desktopTest
 */
class ScheduleTimelineTest {
    private val today = day(2026, 11, 18)
    private fun named(name: String, from: List<SchedulePhase> = martinPlan) = from.first { it.name == name }
    private fun at(year: Int, month: Int, d: Int, hour: Int = 0, minute: Int = 0): Long =
        LocalDateTime(year, month, d, hour, minute).toInstant(zone).toEpochMilliseconds()

    // ---- the stretch of calendar ----

    @Test
    fun aWeekStartsOnMonday() {
        assertEquals(day(2026, 11, 16), day(2026, 11, 18).weekStart())
        assertEquals(day(2026, 11, 16), day(2026, 11, 16).weekStart(), "a Monday is its own week start")
        assertEquals(day(2026, 11, 16), day(2026, 11, 22).weekStart(), "Sunday belongs to the week before it")
    }

    @Test
    fun theTimelineCoversTheWholeScheduleInWholeWeeks() {
        val span = assertNotNull(timelineSpan(martinPlan, today, zone))
        assertEquals(day(2026, 9, 14), span.first, "a week before the first phase (Mon Sep 21)")
        assertEquals(day(2027, 4, 18), span.last, "the end of the week after the last phase (Mon Apr 5)")
        assertEquals(DayOfWeek.MONDAY, span.first.dayOfWeek)
        assertEquals(DayOfWeek.SUNDAY, span.last.dayOfWeek)
        assertEquals(217, span.days)
        assertEquals(31, span.weeks.size)
        assertEquals(day(2026, 9, 14), span.weeks.first())
        assertEquals(day(2027, 4, 12), span.weeks.last())
        assertTrue(span.weeks.all { it.dayOfWeek == DayOfWeek.MONDAY })
        assertEquals(65, span.offsetOf(today))
    }

    @Test
    fun monthsAreClippedToWhatShows() {
        val months = timelineSpan(martinPlan, today, zone)!!.months
        assertEquals(TimelineMonth(2026, 9, offsetDays = 0, lengthDays = 17), months.first(), "September from the 14th")
        assertEquals(TimelineMonth(2026, 10, offsetDays = 17, lengthDays = 31), months[1])
        assertEquals(TimelineMonth(2027, 1, offsetDays = 109, lengthDays = 31), months[4], "the year rolls over")
        assertEquals(TimelineMonth(2027, 4, offsetDays = 199, lengthDays = 18), months.last(), "April up to the 18th")
        assertEquals(listOf(9, 10, 11, 12, 1, 2, 3, 4), months.map { it.month })
        assertEquals(217, months.sumOf { it.lengthDays })
    }

    @Test
    fun todayIsIncludedUnlessTheScheduleIsFarAway() {
        val spring = listOf(SchedulePhase(id = "a", start = day(2027, 3, 1).toLocalMidnightMillis(zone), end = day(2027, 3, 12).toLocalMidnightMillis(zone)))
        assertEquals(day(2026, 11, 9), timelineSpan(spring, today, zone)!!.first, "a job starting in a few months still shows today")
        val nextYear = listOf(SchedulePhase(id = "a", start = day(2028, 3, 1).toLocalMidnightMillis(zone), end = day(2028, 3, 10).toLocalMidnightMillis(zone)))
        assertEquals(day(2028, 2, 21), timelineSpan(nextYear, today, zone)!!.first, "one over half a year off does not")
        val finished = listOf(SchedulePhase(id = "a", start = day(2026, 9, 1).toLocalMidnightMillis(zone), end = day(2026, 9, 30).toLocalMidnightMillis(zone)))
        assertEquals(day(2026, 11, 29), timelineSpan(finished, today, zone)!!.last, "a job that ended recently still shows today")
    }

    @Test
    fun aScheduleWithNoDatesHasNoTimeline() {
        assertNull(timelineSpan(emptyList(), today, zone))
        assertNull(timelineSpan(listOf(SchedulePhase(id = "a", name = "Punch list")), today, zone))
    }

    // ---- bars ----

    @Test
    fun barsSitOnTheirDates() {
        val span = timelineSpan(martinPlan, today, zone)!!
        fun bar(p: SchedulePhase) = span.bar(p.startDate(zone), p.endDate(zone))
        assertEquals(TimelineBar(offsetDays = 7, lengthDays = 25), bar(named("Roof complete")), "Sep 21 through Oct 15, both days included")
        assertEquals(TimelineBar(offsetDays = 11, lengthDays = 1), bar(named("Exterior walls at chimney")), "a milestone is one day")
        assertEquals(TimelineBar(offsetDays = 81, lengthDays = 32), bar(named("Drywall")), "Dec 4 through Jan 4")
        assertNull(span.bar(null, null))
        assertNull(span.bar(day(2026, 12, 1), null))
    }

    @Test
    fun theBaselineStaysPutWhenAPhaseSlips() {
        // The slip case from the build plan: Roof complete now ends Dec 11, planned for Oct 15.
        val roof = named("Roof complete", martinInProgress)
        val span = timelineSpan(martinInProgress, today, zone)!!
        val current = span.bar(roof.startDate(zone), roof.endDate(zone))
        val ghost = span.bar(roof.baselineStart?.let { millisToLocalDate(it, zone) }, roof.baselineEnd?.let { millisToLocalDate(it, zone) })
        assertEquals(TimelineBar(offsetDays = 7, lengthDays = 82), current, "Sep 21 through Dec 11")
        assertEquals(TimelineBar(offsetDays = 7, lengthDays = 25), ghost, "the ghost bar still ends Oct 15")
        assertEquals(day(2026, 10, 15), millisToLocalDate(roof.baselineEnd!!, zone))
    }

    // ---- Fit ----

    @Test
    fun fitFramesTodayThroughTheLastPhase() {
        val span = timelineSpan(martinPlan, today, zone)!!
        val lastEnd = assertNotNull(lastPhaseEnd(martinPlan, zone))
        assertEquals(day(2027, 4, 5), lastEnd)
        // Nov 15 (three days before today) through Apr 8 (three days after the end) is 145 days.
        val fit = span.fit(today, lastEnd, viewportWidth = 1160f)
        assertEquals(8f, fit.dayWidth)
        assertEquals(62, fit.firstDayOffset, "the left edge lands on Nov 15")
    }

    @Test
    fun fitShowsEverythingOnceTheJobIsDone() {
        val span = timelineSpan(martinPlan, day(2027, 4, 12), zone)!!
        val fit = span.fit(day(2027, 4, 12), day(2027, 4, 5), viewportWidth = 1085f)
        assertEquals(1085f / span.days, fit.dayWidth)
        assertEquals(0, fit.firstDayOffset)
    }

    @Test
    fun fitNeverGoesSillyNarrowOrWide() {
        val span = timelineSpan(martinPlan, today, zone)!!
        assertEquals(TIMELINE_MIN_DAY_WIDTH, span.fit(today, day(2027, 4, 5), viewportWidth = 60f).dayWidth)
        assertEquals(TIMELINE_MAX_DAY_WIDTH, span.fit(today, day(2026, 11, 20), viewportWidth = 3000f).dayWidth)
    }

    // ---- rows ----

    @Test
    fun rowsAreGroupHeadingsThenTheirPhases() {
        val rows = scheduleRows(martinPlan)
        assertEquals(18, rows.size, "4 headings + 14 phases")
        assertEquals(ScheduleRow.GroupHead("Exterior / Site", 5), rows[0])
        assertEquals("Exterior walls at chimney", (rows[1] as ScheduleRow.PhaseLine).phase.name)
        assertEquals(ScheduleRow.GroupHead("Rough trades", 3), rows[6])
        assertEquals(ScheduleRow.GroupHead("Closeout", 1), rows[16])
        assertEquals("Finish MEP", (rows[17] as ScheduleRow.PhaseLine).phase.name)
    }

    @Test
    fun aScheduleWithNoGroupsHasNoHeadings() {
        val rows = scheduleRows(listOf(SchedulePhase(id = "a", name = "Roof", order = 1.0), SchedulePhase(id = "b", name = "Siding", order = 2.0)))
        assertEquals(listOf("Roof", "Siding"), rows.map { (it as ScheduleRow.PhaseLine).phase.name })
        assertEquals(emptyList(), scheduleRows(emptyList()))
    }

    // ---- the next site visit ----

    @Test
    fun aRepeatingAppointmentComesRoundAgain() {
        val weekly = Appointment(id = "w", title = "Weekly Meeting", startDate = at(2026, 10, 13, 9), endDate = at(2026, 10, 13, 10), recurrence = "Weekly")
        assertEquals(day(2026, 11, 24), weekly.nextOccurrence(today, zone), "next Tuesday")
        assertEquals(day(2026, 11, 24), weekly.nextOccurrence(day(2026, 11, 24), zone), "today counts")
        assertEquals(day(2026, 10, 13), weekly.nextOccurrence(day(2026, 9, 1), zone), "not before it starts")
        val daily = weekly.copy(recurrence = "Daily")
        assertEquals(today, daily.nextOccurrence(today, zone))
        val monthly = Appointment(id = "m", startDate = at(2027, 1, 31, 8), endDate = at(2027, 1, 31, 9), recurrence = "Monthly")
        assertEquals(day(2027, 3, 31), monthly.nextOccurrence(day(2027, 2, 1), zone), "February has no 31st")
    }

    @Test
    fun aOneOffAppointmentIsOnlyAheadUntilItIsOver() {
        val visit = Appointment(id = "v", title = "Inspection", startDate = at(2026, 11, 20, 13), endDate = at(2026, 11, 20, 14))
        assertEquals(day(2026, 11, 20), visit.nextOccurrence(today, zone))
        assertEquals(day(2026, 11, 20), visit.nextOccurrence(day(2026, 11, 20), zone))
        assertNull(visit.nextOccurrence(day(2026, 11, 21), zone))
        val threeDays = Appointment(id = "p", startDate = at(2026, 11, 17, 7), endDate = at(2026, 11, 19, 15))
        assertEquals(today, threeDays.nextOccurrence(today, zone), "one that is under way is today")
    }

    @Test
    fun aPhaseShowsItsNextLinkedAppointment() {
        val weekly = Appointment(id = "w", title = "Weekly Meeting", startDate = at(2026, 10, 13, 9), endDate = at(2026, 10, 13, 10), recurrence = "Weekly")
        val inspection = Appointment(id = "i", title = "Rough-in inspection", startDate = at(2026, 11, 20, 13), endDate = at(2026, 11, 20, 14))
        val early = Appointment(id = "e", title = "Delivery", startDate = at(2026, 11, 20, 7, 30), endDate = at(2026, 11, 20, 8))
        val old = Appointment(id = "o", title = "Walk-through", startDate = at(2026, 10, 1, 9), endDate = at(2026, 10, 1, 10))
        val byId = listOf(weekly, inspection, early, old).associateBy { it.id }
        val phase = SchedulePhase(id = "p", appointmentIds = listOf("w", "gone", "i", "o"))
        assertEquals(inspection to day(2026, 11, 20), phase.nextLinkedAppointment(byId, today, zone), "Friday's inspection is before Tuesday's meeting")
        assertEquals(early to day(2026, 11, 20), phase.copy(appointmentIds = listOf("i", "e")).nextLinkedAppointment(byId, today, zone), "same day: the earlier time")
        assertEquals(weekly to day(2026, 11, 24), phase.nextLinkedAppointment(byId, day(2026, 11, 21), zone))
        assertNull(phase.copy(appointmentIds = listOf("o", "gone")).nextLinkedAppointment(byId, today, zone), "nothing ahead")
        assertNull(SchedulePhase(id = "q").nextLinkedAppointment(byId, today, zone))
    }
}
