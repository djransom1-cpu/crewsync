package com.djransom.crewsync.util

import com.djransom.crewsync.data.model.Appointment
import com.djransom.crewsync.data.model.PhaseStatus
import com.djransom.crewsync.data.model.SchedulePhase
import com.djransom.crewsync.data.model.Task
import com.djransom.crewsync.util.ScheduleSampleData.day
import com.djransom.crewsync.util.ScheduleSampleData.martinInProgress
import com.djransom.crewsync.util.ScheduleSampleData.roofAppointments
import com.djransom.crewsync.util.ScheduleSampleData.roofTasks
import com.djransom.crewsync.util.ScheduleSampleData.today
import com.djransom.crewsync.util.ScheduleSampleData.zone
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The look-ahead, using the Martin-shaped sample schedule with "today" pinned to Wednesday
 * Nov 18, 2026. Run with: gradlew :composeApp:desktopTest
 *
 * As of that day the sample has: the roof running Sep 21 - Dec 11; mechanical and the electrical
 * rough-in both finishing Thu Nov 19 (the rough-in never started); insulation starting Nov 19 and
 * finishing Dec 4, when drywall starts; and four phases behind.
 */
class ScheduleLookAheadTest {
    private fun nov(d: Int) = day(2026, 11, d)
    private fun dec(d: Int) = day(2026, 12, d)
    private fun at(d: LocalDate, hour: Int, minute: Int = 0) = LocalDateTime(d.year, d.monthNumber, d.dayOfMonth, hour, minute).toInstant(zone).toEpochMilliseconds()
    private fun ms(d: LocalDate) = d.toLocalMidnightMillis(zone)

    // A few trades, and a task card linked to the roof with a due date.
    private val phases = martinInProgress.map {
        when (it.name) {
            "Roof complete" -> it.copy(taskIds = it.taskIds + "tFlash")
            "Mechanical & plumbing" -> it.copy(trade = "Mechanical")
            "Electrical rough-in" -> it.copy(trade = " electrical ")
            else -> it
        }
    }
    private val tasks = roofTasks + listOf(
        Task(id = "tPermit", title = "Septic permit", status = "In Progress", dueDate = ms(nov(13))),        // overdue
        Task(id = "tFlash", title = "Flashing at chimney", status = "Not Started", dueDate = ms(nov(20))),   // due this week, on the roof
        Task(id = "tOld", title = "Order insulation", status = "Completed", dueDate = ms(nov(19)))           // done: left out
    )
    private val appointments = roofAppointments + listOf(   // the Tuesday meeting and Friday's roofer, both on the roof
        Appointment(id = "aDelivery", title = "Insulation delivery", isAllDay = true, startDate = ms(nov(19)), endDate = ms(nov(19))),
        Appointment(id = "aPast", title = "Walk-through", startDate = at(nov(10), 9), endDate = at(nov(10), 10))
    )
    private fun look(weeks: Int) = buildLookAhead(phases, tasks, appointments, today, weeks, zone)

    private fun describe(entry: LookAheadEntry): String = when (entry) {
        is LookAheadEntry.Phase -> "${entry.item.phase.name} ${entry.role.name.lowercase()}"
        is LookAheadEntry.Visit -> "visit ${entry.item.appointment.title}"
        is LookAheadEntry.Due -> "due ${entry.item.task.title}"
    }
    private fun List<LookAheadGroup>.describe(): List<Pair<String, List<String>>> =
        map { g -> (g.day?.toString() ?: g.trade ?: "under way") to g.entries.map(::describe) }

    // ---- the window ----

    @Test
    fun theWindowRunsFromTodayToTheSundayEndingTheLastWeek() {
        assertEquals(LookAheadWindow(nov(18), nov(22)), lookAheadWindow(nov(18), 1), "this week, from a Wednesday")
        assertEquals(LookAheadWindow(nov(18), nov(29)), lookAheadWindow(nov(18), 2))
        assertEquals(LookAheadWindow(nov(18), dec(6)), lookAheadWindow(nov(18), 3))
        assertEquals(LookAheadWindow(nov(22), nov(22)), lookAheadWindow(nov(22), 1), "on a Sunday, this week is just today")
        assertEquals(LookAheadWindow(nov(16), nov(29)), lookAheadWindow(nov(16), 2), "from a Monday, two whole weeks")
        assertEquals(lookAheadWindow(nov(18), 1), lookAheadWindow(nov(18), 0), "never less than this week")
        assertEquals(5, lookAheadWindow(nov(18), 1).days.size)
    }

    // ---- behind ----

    @Test
    fun behindListsPhasesThatShouldHaveStartedOrFinishedAndOverdueCards() {
        val look = look(1)
        assertEquals(
            listOf(
                "Electrical rough-in" to BehindReason.SHOULD_HAVE_STARTED to day(2026, 10, 29),
                "Remaining concrete" to BehindReason.SHOULD_HAVE_STARTED to nov(1),
                "Septic & grading" to BehindReason.SHOULD_HAVE_FINISHED to nov(15),   // on hold, past its end
                "Siding" to BehindReason.SHOULD_HAVE_FINISHED to nov(15)              // in progress, past its end
            ),
            look.behindPhases.map { it.phase.name to it.reason to it.since }
        )
        assertEquals(listOf("Septic permit"), look.overdueTasks.map { it.task.title })
        assertEquals(nov(13), look.overdueTasks.single().due)
    }

    // ---- what's in the window ----

    @Test
    fun theWindowHoldsRunningPhasesVisitsAndCardsDue() {
        val look = look(1)
        assertEquals(
            listOf("Roof complete", "Mechanical & plumbing", "Electrical rough-in", "Insulation"),
            look.phases.map { it.phase.name },
            "not Done, not finished before today, not starting after Sunday"
        )
        val roughIn = look.phases.first { it.phase.name == "Electrical rough-in" }
        assertTrue(roughIn.endsInWindow && !roughIn.startsInWindow && roughIn.isLate)
        assertEquals(listOf("Roofer on site - ridge and flashing" to nov(20), "Insulation delivery" to nov(19)), look.appointments.map { it.appointment.title to it.day }.sortedByDescending { it.first })
        assertEquals(listOf("Flashing at chimney"), look.tasks.map { it.task.title }, "the Completed card is left out")
        assertEquals(null, look.appointments.first { it.appointment.id == "aDelivery" }.minutes, "all day")
        assertEquals(7 * 60 + 30, look.appointments.first { it.appointment.id == "aRoofer" }.minutes)
    }

    @Test
    fun aPhaseMarkedDoneIsLeftOutEvenInsideTheWindow() {
        val done = phases.map { if (it.name == "Insulation") it.copy(status = PhaseStatus.DONE) else it }
        assertTrue(buildLookAhead(done, tasks, appointments, today, 1, zone).phases.none { it.phase.name == "Insulation" })
    }

    // ---- by day ----

    @Test
    fun byDayThisWeek() {
        assertEquals(
            listOf(
                "under way" to listOf("Roof complete under_way"),
                "2026-11-19" to listOf(
                    "visit Insulation delivery",               // all-day visits first
                    "Insulation starts",
                    "Electrical rough-in finishes",
                    "Mechanical & plumbing finishes"
                ),
                "2026-11-20" to listOf("visit Roofer on site - ridge and flashing", "due Flashing at chimney")
            ),
            look(1).byDay().describe()
        )
    }

    @Test
    fun byDayOverThreeWeeksListsAPhaseOnTheDayItStartsAndTheDayItFinishes() {
        val days = look(3).byDay().describe().toMap()
        assertEquals(listOf("visit Insulation delivery", "Insulation starts", "Electrical rough-in finishes", "Mechanical & plumbing finishes"), days["2026-11-19"])
        assertEquals(listOf("visit Weekly Meeting"), days["2026-11-24"], "the Tuesday meeting comes round")
        assertEquals(listOf("visit Weekly Meeting"), days["2026-12-01"])
        assertEquals(listOf("Drywall starts", "Insulation finishes"), days["2026-12-04"])
        assertEquals(listOf("Roof complete under_way"), days["under way"])
    }

    // ---- by trade ----

    @Test
    fun byTradeOverTwoWeeks() {
        assertEquals(
            listOf(
                "electrical" to listOf("Electrical rough-in running"),
                "Mechanical" to listOf("Mechanical & plumbing running"),
                "Roofing" to listOf(
                    "Roof complete running",
                    "visit Roofer on site - ridge and flashing",   // linked to the roof, so under its trade
                    "due Flashing at chimney",
                    "visit Weekly Meeting"
                ),
                "" to listOf("visit Insulation delivery", "Insulation running")   // no trade, last
            ),
            look(2).byTrade(phases).describe()
        )
    }

    @Test
    fun tradesMatchIgnoringCaseAndSpacing() {
        val mixed = phases.map { if (it.name == "Insulation") it.copy(trade = "Electrical") else it }
        val groups = buildLookAhead(mixed, tasks, appointments, today, 2, zone).byTrade(mixed)
        assertEquals("electrical", groups.first().trade, "the first spelling met is the one shown")
        assertEquals(listOf("Electrical rough-in running", "Insulation running"), groups.first().entries.map(::describe))
    }

    // ---- appointments that repeat or run over several days ----

    @Test
    fun repeatingAndLongAppointmentsLandOnTheRightDays() {
        val window = lookAheadWindow(nov(18), 2)
        val daily = Appointment(id = "d", startDate = at(nov(20), 7), endDate = at(nov(20), 8), recurrence = "Daily")
        assertEquals((20..29).map { nov(it) }, daily.occurrenceDaysIn(window, zone), "not before it starts")
        val monthly = Appointment(id = "m", startDate = at(day(2026, 10, 31), 7), endDate = at(day(2026, 10, 31), 8), recurrence = "Monthly")
        assertEquals(emptyList(), monthly.occurrenceDaysIn(LookAheadWindow(nov(1), nov(30)), zone), "November has no 31st")
        val threeDays = Appointment(id = "p", startDate = at(nov(16), 7), endDate = at(nov(19), 15))
        assertEquals(listOf(nov(18)), threeDays.occurrenceDaysIn(window, zone), "under way: listed once, on the window's first day")
        val past = Appointment(id = "x", startDate = at(nov(10), 9), endDate = at(nov(10), 10))
        assertEquals(emptyList(), past.occurrenceDaysIn(window, zone))
    }

    @Test
    fun nothingComingUpIsEmpty() {
        assertTrue(buildLookAhead(emptyList(), emptyList(), emptyList(), today, 2, zone).isEmpty)
        assertEquals(emptyList(), buildLookAhead(emptyList(), emptyList(), emptyList(), today, 2, zone).byDay())
        assertEquals(emptyList(), buildLookAhead(listOf(SchedulePhase(id = "u", name = "Undated")), emptyList(), emptyList(), today, 2, zone).byTrade(emptyList()))
    }
}
