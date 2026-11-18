package com.djransom.crewsync.util

import com.djransom.crewsync.data.model.SchedulePhase
import com.djransom.crewsync.util.ScheduleSampleData.day
import com.djransom.crewsync.util.ScheduleSampleData.martinInProgress
import com.djransom.crewsync.util.ScheduleSampleData.zone
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Where schedule phases sit as banners on the site calendar, using the Martin-shaped sample
 * schedule in November 2026 (Nov 1 is a Sunday). Run with: gradlew :composeApp:desktopTest
 */
class CalendarBannersTest {
    private fun nov(d: Int) = day(2026, 11, d)
    private fun week(first: LocalDate): List<LocalDate?> = (0..6).map { first.plus(it, DateTimeUnit.DAY) }
    private fun List<BannerSegment>.describe() = map { "${it.phase.name} ${it.startCol}-${it.endCol} lane ${it.lane}" }

    @Test
    fun theDayListShowsThePhasesRunningThatDayInScheduleOrder() {
        assertEquals(
            listOf("Roof complete", "Mechanical & plumbing", "Electrical rough-in", "Insulation"),
            phasesOnDay(martinInProgress, nov(19), zone).map { it.name },
            "Insulation starts the day mechanical and the rough-in finish"
        )
        assertEquals(listOf("Exterior walls at chimney", "Roof complete", "Siding"), phasesOnDay(martinInProgress, day(2026, 9, 25), zone).map { it.name }, "a milestone is on its one day")
        assertTrue(phasesOnDay(martinInProgress, day(2027, 6, 1), zone).isEmpty())
        assertTrue(phasesOnDay(listOf(SchedulePhase(id = "u", name = "Undated")), nov(19), zone).isEmpty(), "no dates, not on the calendar")
    }

    @Test
    fun aWeekWhereEverythingRunsRightAcross() {
        // Sun Nov 1 - Sat Nov 7: six phases run through the whole week.
        val banners = rowBanners(martinInProgress, week(nov(1)), zone)
        assertEquals(
            listOf(
                "Roof complete 0-6 lane 0", "Siding 0-6 lane 1", "Septic & grading 0-6 lane 2",
                "Remaining concrete 0-6 lane 3", "Mechanical & plumbing 0-6 lane 4", "Electrical rough-in 0-6 lane 5"
            ),
            banners.describe()
        )
        assertEquals(6, banners.laneCount())
        val concrete = banners.first { it.phase.name == "Remaining concrete" }
        assertTrue(!concrete.continuesLeft && concrete.continuesRight, "it starts on the Sunday and runs on into the next week")
        assertTrue(banners.first().continuesLeft && banners.first().continuesRight)
    }

    @Test
    fun aFreedLaneIsReusedByAPhaseStartingLaterInTheWeek() {
        // Sun Nov 15 - Sat Nov 21: siding, septic and concrete all finish on the Sunday; insulation
        // starts on the Thursday and takes the first lane that's free from Thursday on.
        val banners = rowBanners(martinInProgress, week(nov(15)), zone)
        assertEquals(
            listOf(
                "Roof complete 0-6 lane 0", "Siding 0-0 lane 1", "Septic & grading 0-0 lane 2", "Remaining concrete 0-0 lane 3",
                "Mechanical & plumbing 0-4 lane 4", "Electrical rough-in 0-4 lane 5", "Insulation 4-6 lane 1"
            ),
            banners.describe()
        )
        val siding = banners.first { it.phase.name == "Siding" }
        assertTrue(siding.continuesLeft && !siding.continuesRight)
    }

    @Test
    fun bannersStopAtTheBlankCellsOfTheMonthGrid() {
        // The first row of December with weeks starting Sunday: Nov 29 and 30 are blank cells.
        val row: List<LocalDate?> = listOf(null, null) + (1..5).map { day(2026, 12, it) }
        val banners = rowBanners(martinInProgress, row, zone)
        // Tue Dec 1 is column 2; insulation finishes and drywall starts on Fri Dec 4, column 5, so drywall needs a lane of its own.
        assertEquals(listOf("Roof complete 2-6 lane 0", "Insulation 2-5 lane 1", "Drywall 5-6 lane 2"), banners.describe())
        assertTrue(banners.first().continuesLeft, "the roof carries on from November")
        assertTrue(!banners.first { it.phase.name == "Insulation" }.continuesRight, "insulation finishes Fri Dec 4")
        assertEquals(emptyList(), rowBanners(martinInProgress, List(7) { null }, zone))
    }

    @Test
    fun aMilestoneIsOneCellAndAPhaseWithNoDatesIsLeftOut() {
        val banners = rowBanners(martinInProgress, week(day(2026, 9, 20)), zone)
        val chimney = banners.first { it.phase.name == "Exterior walls at chimney" }
        assertEquals(5 to 5, chimney.startCol to chimney.endCol, "Fri Sep 25")
        assertTrue(banners.none { it.phase.name == "Punch list" })
    }

    @Test
    fun aPhaseWhoseEndIsBeforeItsStartShowsOnItsStartDay() {
        val backwards = SchedulePhase(id = "b", name = "Backwards", order = 1.0, start = nov(18).toLocalMidnightMillis(zone), end = nov(16).toLocalMidnightMillis(zone))
        assertEquals(listOf("Backwards 3-3 lane 0"), rowBanners(listOf(backwards), week(nov(15)), zone).describe())
        assertEquals(listOf("Backwards"), phasesOnDay(listOf(backwards), nov(18), zone).map { it.name })
    }
}
