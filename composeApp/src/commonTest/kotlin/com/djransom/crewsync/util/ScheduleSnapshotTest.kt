package com.djransom.crewsync.util

import com.djransom.crewsync.data.model.ChecklistGroup
import com.djransom.crewsync.data.model.ChecklistItem
import com.djransom.crewsync.data.model.DailyLog
import com.djransom.crewsync.data.model.PhaseStatus
import com.djransom.crewsync.data.model.Task
import com.djransom.crewsync.data.model.WeatherReading
import com.djransom.crewsync.util.ScheduleSampleData.day
import com.djransom.crewsync.util.ScheduleSampleData.martinInProgress
import com.djransom.crewsync.util.ScheduleSampleData.roofAppointments
import com.djransom.crewsync.util.ScheduleSampleData.roofTasks
import com.djransom.crewsync.util.ScheduleSampleData.today
import com.djransom.crewsync.util.ScheduleSampleData.zone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Today snapshot, using the Martin-shaped sample with "today" pinned to Wed Nov 18, 2026.
 * Run with: gradlew :composeApp:desktopTest
 */
class ScheduleSnapshotTest {
    private val now = today.toLocalMidnightMillis(zone) + 12 * 3_600_000L   // noon on Nov 18
    private fun ms(m: Int, d: Int) = day(2026, m, d).toLocalMidnightMillis(zone)
    private fun items(n: Int, done: Int = 0) = (1..n).map { ChecklistItem("i$it", "Item $it", isDone = it <= done) }

    private val bigTask = Task(id = "tBig", title = "Punch walk", status = "Not Started", checklistGroups = listOf(ChecklistGroup("g", "Punch", items(40))))
    private val topTask = Task(id = "tTop", title = "Order windows", status = "TOP Priority", dueDate = ms(11, 20), checklistGroups = listOf(ChecklistGroup("g", "Order", items(2))))
    private val overdueTask = Task(id = "tLate", title = "Septic permit", status = "In Progress", dueDate = ms(11, 13))
    private val tasks = roofTasks + listOf(bigTask, topTask, overdueTask)
    private val phases = martinInProgress.map {
        // Electrical service was marked Done two days ago; the chimney milestone long ago.
        if (it.name == "Electrical service") it.copy(updatedAt = now - 2 * 86_400_000L) else it
    }
    private val yesterdayLog = DailyLog(
        id = "2026-11-17", date = "2026-11-17", crewOnSite = listOf("NACE crew (14)"),
        issuesDelays = listOf("Confirm the gas line test before the pour"), tomorrowPlan = listOf("A+ Concrete - pour the patio slab")
    )
    private val todayWeatherOnly = DailyLog(id = "2026-11-18", date = "2026-11-18", weatherReadings = listOf(WeatherReading(slot = "morning", tempF = 34, conditions = "Clear")))
    private fun snap(
        phases: List<com.djransom.crewsync.data.model.SchedulePhase> = this.phases,
        logs: List<DailyLog> = listOf(yesterdayLog, todayWeatherOnly)
    ) = buildTodaySnapshot(phases, tasks, roofAppointments, logs, WorkCalendar(), today, now, zone)

    // ---- where we stand ----

    @Test
    fun whereWeStand() {
        val s = snap()
        assertTrue(s.hasSchedule)
        assertEquals(
            listOf(Triple("Roof complete", 40, 41), Triple("Siding", 60, 0), Triple("Mechanical & plumbing", 85, 0)),
            s.inProgress.map { Triple(it.phase.name, it.percent, it.slipDays) },
            "the roof's percent comes from its linked cards; it has slipped 41 working days"
        )
        assertEquals(listOf("Electrical service"), s.recentlyFinished.map { it.name }, "marked Done this past week")
        assertEquals(day(2027, 4, 5), s.plannedFinish)
        assertEquals(0, s.finishSlipDays)
    }

    @Test
    fun aLatePhaseIsBehind() {
        val s = snap()
        assertEquals(listOf("Electrical rough-in", "Remaining concrete", "Septic & grading", "Siding"), s.behindPhases.map { it.phase.name })
        assertEquals(BehindReason.SHOULD_HAVE_STARTED, s.behindPhases.first().reason)
        assertEquals(listOf("Septic permit"), s.overdueTasks.map { it.task.title })
    }

    @Test
    fun nextUpLooksTenWorkingDaysAhead() {
        // Ten working days from Wed Nov 18 is Wed Dec 2: insulation (Nov 19) is in, drywall (Dec 4) isn't.
        assertEquals(listOf("Insulation"), snap().nextUp.map { it.name })
        val earlierDrywall = phases.map { if (it.name == "Drywall") it.copy(start = ms(12, 2)) else it }
        assertEquals(listOf("Insulation", "Drywall"), snap(phases = earlierDrywall).nextUp.map { it.name })
    }

    @Test
    fun onSiteTodayThenTheNextSevenDays() {
        val s = snap()
        assertEquals(emptyList(), s.onSiteToday.map { it.appointment.title }, "nothing on Wednesday")
        assertEquals(
            listOf("Roofer on site - ridge and flashing" to day(2026, 11, 20), "Weekly Meeting" to day(2026, 11, 24)),
            s.onSiteNextWeek.map { it.appointment.title to it.day }
        )
        val onTuesday = buildTodaySnapshot(phases, tasks, roofAppointments, emptyList(), WorkCalendar(), day(2026, 11, 24), now, zone)
        assertEquals(listOf("Weekly Meeting"), onTuesday.onSiteToday.map { it.appointment.title })
    }

    // ---- to do ----

    @Test
    fun toDoPutsTopPriorityAndActivePhaseCardsFirst() {
        val s = snap()
        // Order windows is in TOP Priority; Roof tear-off is linked to the roof, which is in progress.
        assertEquals(listOf("Order windows", "Order windows", "Roof tear-off", "Roof tear-off", "Roof tear-off"), s.toDo.take(5).map { it.task.title })
        assertTrue(s.toDo.take(5).all { it.priority })
        assertEquals(listOf("Deck repair", "Dry in", "Shingle"), s.toDo.filter { it.task.id == "tRoof" }.map { it.item.text }, "ticked items are left out")
        assertTrue(s.toDo.none { it.task.id == "tRidge" }, "a finished card has nothing to do")
        assertEquals(2 + 3 + 40, s.toDo.size)
    }

    @Test
    fun aCardWithFortyOpenItemsShowsFifteenThenMore() {
        val s = snap()
        val (shown, more) = s.toDoShown(expanded = false)
        assertEquals(SNAPSHOT_TODO_SHOWN, shown.size)
        assertEquals(30, more, "45 open items in all")
        assertEquals(45 to 0, s.toDoShown(expanded = true).let { it.first.size to it.second })
    }

    // ---- the last daily report and the weather ----

    @Test
    fun theLastReportIsTheLatestLogWithSomethingInIt() {
        val s = snap()
        assertEquals("2026-11-17", s.lastReport?.date, "today's log has weather only")
        assertEquals(listOf("A+ Concrete - pour the patio slab"), s.lastReport?.tomorrowPlan)
        assertTrue(s.weather.startsWith("7 AM 34°"), s.weather)
    }

    @Test
    fun aDayWithNoDailyLog() {
        val s = snap(logs = listOf(yesterdayLog))
        assertEquals("", s.weather, "nothing recorded today: the view looks the weather up instead")
        assertEquals("2026-11-17", s.lastReport?.date)
        assertNull(snap(logs = emptyList()).lastReport)
    }

    // ---- a project with no schedule ----

    @Test
    fun aProjectWithNoSchedule() {
        val s = snap(phases = emptyList())
        assertFalse(s.hasSchedule)
        assertTrue(s.inProgress.isEmpty() && s.recentlyFinished.isEmpty() && s.nextUp.isEmpty() && s.behindPhases.isEmpty())
        assertNull(s.finishSlipDays)
        assertEquals(listOf("Septic permit"), s.overdueTasks.map { it.task.title }, "the parts that don't need a schedule still work")
        assertEquals(listOf("Order windows", "Order windows"), s.toDo.filter { it.priority }.map { it.task.title }, "with no phases, only TOP Priority comes first")
        assertEquals(2, s.onSiteNextWeek.size)
        assertEquals(PhaseStatus.NOT_STARTED, PhaseStatus.normalize(null))
    }
}
