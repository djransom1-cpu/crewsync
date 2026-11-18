package com.djransom.crewsync.util

import com.djransom.crewsync.data.model.SchedulePhase
import com.djransom.crewsync.util.ScheduleSampleData.day
import com.djransom.crewsync.util.ScheduleSampleData.martinInProgress
import com.djransom.crewsync.util.ScheduleSampleData.martinPlan
import com.djransom.crewsync.util.ScheduleSampleData.today
import com.djransom.crewsync.util.ScheduleSampleData.zone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The numbers behind presentation mode: the stretch of calendar it opens on and the three figures
 * in its header. Uses the Martin-shaped sample schedule with "today" pinned to Wed Nov 18, 2026.
 * Run with: gradlew :composeApp:desktopTest
 */
class SchedulePresentationTest {
    private fun ms(year: Int, month: Int, d: Int) = day(year, month, d).toLocalMidnightMillis(zone)

    // ---- what it opens on: two weeks back to twelve weeks ahead ----

    @Test
    fun itOpensOnTwoWeeksBackToTwelveAhead() {
        val span = timelineSpan(martinPlan, today, zone)!!
        // This week starts Mon Nov 16, so the left edge is Mon Nov 2, and 14 weeks (98 days) fill the window.
        val view = span.around(today, weeksBack = 2, weeksAhead = 12, viewportWidth = 1568f)
        assertEquals(16f, view.dayWidth)
        assertEquals(span.offsetOf(day(2026, 11, 2)), view.firstDayOffset)
        assertEquals(49, view.firstDayOffset)
    }

    @Test
    fun aJobThatStartedThisWeekOpensAtItsFirstWeek() {
        val justStarted = listOf(SchedulePhase(id = "a", start = ms(2026, 11, 16), end = ms(2026, 12, 18)))
        val span = timelineSpan(justStarted, today, zone)!!
        assertEquals(day(2026, 11, 9), span.first)
        assertEquals(0, span.around(today, 2, 12, 1568f).firstDayOffset, "there is nothing two weeks back to show")
    }

    @Test
    fun theOpeningViewStaysWithinSensibleZoom() {
        val span = timelineSpan(martinPlan, today, zone)!!
        assertEquals(TIMELINE_MIN_DAY_WIDTH, span.around(today, 2, 12, viewportWidth = 100f).dayWidth)
        assertEquals(TIMELINE_MAX_DAY_WIDTH, span.around(today, 0, 1, viewportWidth = 2000f).dayWidth)
    }

    // ---- the header: phases done, phases in progress, working days of slip at the finish ----

    @Test
    fun theHeaderCountsDoneAndInProgress() {
        val totals = scheduleTotals(martinInProgress, WorkCalendar(), zone)
        assertEquals(15, totals.total)
        assertEquals(2, totals.done)
        assertEquals(3, totals.inProgress)
    }

    @Test
    fun aSlipInTheMiddleThatDoesNotMoveTheFinishIsNoSlipAtTheFinish() {
        // Roof complete runs 39 working days late in the sample, but the last phase still ends Apr 5.
        val totals = scheduleTotals(martinInProgress, WorkCalendar(), zone)
        assertEquals(day(2027, 4, 5), totals.plannedFinish)
        assertEquals(day(2027, 4, 5), totals.currentFinish)
        assertEquals(0, totals.finishSlipDays)
    }

    @Test
    fun theFinishSlipIsCountedInWorkingDays() {
        fun withFinish(end: Long) = martinInProgress.map { if (it.name == "Finish MEP") it.copy(end = end) else it }
        // Mon Apr 5 to Mon Apr 19 is two working weeks.
        val late = scheduleTotals(withFinish(ms(2027, 4, 19)), WorkCalendar(), zone)
        assertEquals(day(2027, 4, 19), late.currentFinish)
        assertEquals(day(2027, 4, 5), late.plannedFinish, "the plan does not move")
        assertEquals(10, late.finishSlipDays)
        // A day off in between is not counted.
        assertEquals(9, scheduleTotals(withFinish(ms(2027, 4, 19)), WorkCalendar(holidays = setOf(day(2027, 4, 9))), zone).finishSlipDays)
        // Finishing Wed Mar 31 instead is three working days early.
        assertEquals(-3, scheduleTotals(withFinish(ms(2027, 3, 31)), WorkCalendar(), zone).finishSlipDays)
    }

    @Test
    fun theFinishIsTheLatestEndWhicheverPhaseThatIs() {
        // Painting running on past the closeout becomes the finish.
        val overrun = martinInProgress.map { if (it.name == "Painting") it.copy(end = ms(2027, 4, 12)) else it }
        val totals = scheduleTotals(overrun, WorkCalendar(), zone)
        assertEquals(day(2027, 4, 12), totals.currentFinish)
        assertEquals(5, totals.finishSlipDays)
    }

    @Test
    fun withNoBaselineOrNoDatesThereIsNoSlipToShow() {
        val noBaseline = martinPlan.map { it.copy(baselineStart = null, baselineEnd = null) }
        val totals = scheduleTotals(noBaseline, WorkCalendar(), zone)
        assertNull(totals.plannedFinish)
        assertEquals(day(2027, 4, 5), totals.currentFinish)
        assertNull(totals.finishSlipDays)
        val empty = scheduleTotals(emptyList(), WorkCalendar(), zone)
        assertEquals(0, empty.total)
        assertNull(empty.currentFinish)
        assertNull(empty.finishSlipDays)
    }
}
