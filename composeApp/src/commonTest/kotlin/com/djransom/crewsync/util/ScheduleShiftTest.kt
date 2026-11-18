package com.djransom.crewsync.util

import com.djransom.crewsync.data.model.Appointment
import com.djransom.crewsync.data.model.PhasePredecessor
import com.djransom.crewsync.data.model.PhaseStatus
import com.djransom.crewsync.data.model.PredecessorType
import com.djransom.crewsync.data.model.SchedulePhase
import com.djransom.crewsync.data.model.Task
import com.djransom.crewsync.util.ScheduleSampleData.day
import com.djransom.crewsync.util.ScheduleSampleData.zone
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Shifting a phase and everything that follows it. Run with: gradlew :composeApp:desktopTest
 *
 * The chain used throughout (November 2026: the 2nd is a Monday; Thanksgiving is Thu the 26th):
 *
 *   Framing            Mon Nov 2  - Fri Nov 6
 *   Rough plumbing     Mon Nov 9  - Fri Nov 13    after Framing finishes
 *   Rough electrical   Wed Nov 11 - Tue Nov 17    two working days after plumbing STARTS
 *   Insulation         Tue Nov 17 - Mon Nov 23    starts on electrical's last day (finish-to-start, lag -1)
 *   Drywall            Tue Nov 24 - ...           after Insulation AND after plumbing
 *
 * functions/test/mcp.test.js runs the same chain through the connector, and must get the same dates.
 */
class ScheduleShiftTest {
    private val plain = WorkCalendar()
    private val thanksgiving = WorkCalendar(holidays = setOf(day(2026, 11, 26), day(2026, 11, 27)))
    private fun nov(d: Int) = day(2026, 11, d)
    private fun dec(d: Int) = day(2026, 12, d)

    private fun phase(id: String, start: LocalDate?, end: LocalDate?, vararg waitsOn: PhasePredecessor, status: String = PhaseStatus.NOT_STARTED, milestone: Boolean = false) =
        SchedulePhase(
            id = id, name = id, start = start?.toLocalMidnightMillis(zone), end = end?.toLocalMidnightMillis(zone),
            predecessors = waitsOn.toList(), status = status, isMilestone = milestone,
            baselineStart = start?.toLocalMidnightMillis(zone), baselineEnd = end?.toLocalMidnightMillis(zone)
        )

    private fun chain(drywallEnd: LocalDate) = listOf(
        phase("Framing", nov(2), nov(6)),
        phase("Rough plumbing", nov(9), nov(13), PhasePredecessor("Framing")),
        phase("Rough electrical", nov(11), nov(17), PhasePredecessor("Rough plumbing", PredecessorType.SS, 2)),
        phase("Insulation", nov(17), nov(23), PhasePredecessor("Rough electrical", PredecessorType.FS, -1)),
        phase("Drywall", nov(24), drywallEnd, PhasePredecessor("Insulation"), PhasePredecessor("Rough plumbing"))
    )

    private fun moves(plan: ShiftPlan): List<PhaseMove> = (plan as? ShiftPlan.Moves)?.moves ?: fail("expected moves, got $plan")
    private fun List<PhaseMove>.dates() = associate { it.phase.name to (it.newStart to it.newEnd) }

    /** Shifts [id] by [days] working days and returns what would move. */
    private fun shift(phases: List<SchedulePhase>, id: String, days: Int, calendar: WorkCalendar, cascade: Boolean = true): List<PhaseMove> {
        val (s, e) = shiftedDates(phases.first { it.id == id }, days, calendar, zone)!!
        return moves(planCascade(phases, id, s, e, calendar, zone, cascade))
    }

    /** The schedule as it would be once [moved] is applied. */
    private fun List<SchedulePhase>.after(moved: List<PhaseMove>) = map { p ->
        moved.firstOrNull { it.phase.id == p.id }?.let { p.copy(start = it.newStart.toLocalMidnightMillis(zone), end = it.newEnd.toLocalMidnightMillis(zone)) } ?: p
    }

    // ---- the chain of five ----

    @Test
    fun aSlipRunsDownTheWholeChain() {
        val moved = shift(chain(drywallEnd = nov(30)), "Framing", 3, plain)
        assertEquals(listOf("Framing", "Rough plumbing", "Rough electrical", "Insulation", "Drywall"), moved.map { it.phase.name })
        assertEquals(
            mapOf(
                "Framing" to (nov(5) to nov(11)),
                "Rough plumbing" to (nov(12) to nov(18)),      // finish-to-start: the working day after Framing ends
                "Rough electrical" to (nov(16) to nov(20)),    // start-to-start + 2: two working days after plumbing starts
                "Insulation" to (nov(20) to nov(26)),          // lag -1: still starts on electrical's last day
                "Drywall" to (nov(27) to dec(3))               // after the later of its two predecessors
            ),
            moved.dates()
        )
        assertTrue(moved.all { it.startShift == 3 && it.endShift == 3 }, "every phase moved the same three working days")
        assertTrue(moved.first().isOrigin && moved.drop(1).none { it.isOrigin })
    }

    @Test
    fun aSlipAcrossAHolidayCountsOnlyWorkingDays() {
        // With Thanksgiving and the Friday after it off, Drywall's five days are Nov 24, 25, 30, Dec 1, 2.
        val moved = shift(chain(drywallEnd = dec(2)), "Framing", 3, thanksgiving)
        assertEquals(nov(20) to nov(30), moved.dates()["Insulation"], "Fri 20, Mon 23, Tue 24, Wed 25, then Mon 30")
        assertEquals(dec(1) to dec(7), moved.dates()["Drywall"], "three working days on from Nov 24 is Dec 1")
        val drywall = moved.first { it.phase.name == "Drywall" }
        assertEquals(3, drywall.startShift)
        assertEquals(3, drywall.endShift)
        assertEquals(5, thanksgiving.durationDays(drywall.newStart, drywall.newEnd), "its length in working days is kept")
    }

    @Test
    fun shiftingBackPutsEverythingBack() {
        val original = chain(drywallEnd = dec(2))
        val slipped = original.after(shift(original, "Framing", 3, thanksgiving))
        val restored = slipped.after(shift(slipped, "Framing", -3, thanksgiving))
        assertEquals(original, restored)
    }

    @Test
    fun theBaselineNeverMoves() {
        val original = chain(drywallEnd = nov(30))
        val moved = shift(original, "Framing", 3, plain)
        // A move only ever carries new start and end dates; the phase it points at still has its plan.
        assertTrue(moved.all { it.phase.baselineStart == it.oldStart.toLocalMidnightMillis(zone) && it.phase.baselineEnd == it.oldEnd.toLocalMidnightMillis(zone) })
    }

    @Test
    fun stretchingAPhaseMovesWhatWaitsForItsEndNotWhatWaitsForItsStart() {
        // Rough plumbing takes two days longer; its start doesn't change.
        val moved = moves(planCascade(chain(drywallEnd = nov(30)), "Rough plumbing", nov(9), nov(17), plain, zone))
        assertEquals(listOf("Rough plumbing"), moved.map { it.phase.name }, "electrical waits on its START; drywall is still held by Insulation")
        assertEquals(0, moved.single().startShift)
        assertEquals(2, moved.single().endShift)
        // ...but far enough, and plumbing becomes the one Drywall is waiting for.
        val further = moves(planCascade(chain(drywallEnd = nov(30)), "Rough plumbing", nov(9), nov(25), plain, zone))
        assertEquals(nov(26) to dec(2), further.dates()["Drywall"], "plumbing now ends Wed Nov 25, two working days past what Insulation allowed, so Drywall moves two")
    }

    @Test
    fun withoutCascadeOnlyThePhaseItselfMoves() {
        assertEquals(listOf("Framing"), shift(chain(drywallEnd = nov(30)), "Framing", 3, plain, cascade = false).map { it.phase.name })
        assertEquals(emptyList(), shift(chain(drywallEnd = nov(30)), "Framing", 0, plain).map { it.phase.name }, "no change, nothing to do")
    }

    // ---- what stays where it is ----

    @Test
    fun aGapLeftOnPurposeIsKept() {
        val phases = listOf(
            phase("Framing", nov(2), nov(6)),
            phase("Cabinets", nov(23), nov(25), PhasePredecessor("Framing"))   // could start Nov 9; deliberately two weeks later
        )
        val moved = shift(phases, "Framing", 3, plain)
        assertEquals(nov(26) to nov(30), moved.dates()["Cabinets"], "Mon Nov 23 + 3 working days: it moves the same three days, keeping the two-week gap")
    }

    @Test
    fun aFollowerHeldUpBySomethingElseStaysPut() {
        val phases = listOf(
            phase("Framing", nov(2), nov(6)),
            phase("Septic", nov(9), nov(20)),
            phase("Backfill", nov(23), nov(25), PhasePredecessor("Framing"), PhasePredecessor("Septic"))
        )
        assertEquals(listOf("Framing"), shift(phases, "Framing", 3, plain).map { it.phase.name }, "Septic, not Framing, decides when Backfill can start")
        // Push Framing past Septic's end and it takes over: Framing would end Mon Nov 23, so Backfill goes one day, to Tue Nov 24.
        assertEquals(nov(24) to nov(26), shift(phases, "Framing", 11, plain).dates()["Backfill"])
    }

    @Test
    fun aPhaseThatIsDoneDoesNotMoveAndNothingMovesThroughIt() {
        val phases = listOf(
            phase("Framing", nov(2), nov(6)),
            phase("Inspection", nov(9), nov(9), PhasePredecessor("Framing"), status = PhaseStatus.DONE, milestone = true),
            phase("Insulation", nov(10), nov(13), PhasePredecessor("Inspection")),
            phase("Punch list", null, null, PhasePredecessor("Framing"))
        )
        assertEquals(listOf("Framing"), shift(phases, "Framing", 3, plain).map { it.phase.name }, "Done stays; Insulation only waits on the Done one; Punch list has no dates")
    }

    @Test
    fun aMilestoneMovesAsOneDay() {
        val phases = listOf(
            phase("Roof on", nov(6), nov(6), milestone = true),
            phase("Dried in", nov(9), nov(9), PhasePredecessor("Roof on"), milestone = true)
        )
        val moved = shift(phases, "Roof on", 1, plain)
        assertEquals(mapOf("Roof on" to (nov(9) to nov(9)), "Dried in" to (nov(10) to nov(10))), moved.dates(), "Friday + 1 is Monday")
        assertEquals(nov(5) to nov(5), shiftedDates(phases[0], -1, plain, zone))
        assertEquals(null, shiftedDates(phase("Undated", null, null), 3, plain, zone))
    }

    // ---- a loop ----

    @Test
    fun aLoopInThePredecessorsIsRefusedAndNamed() {
        val phases = listOf(
            phase("Framing", nov(2), nov(6), PhasePredecessor("Drywall")),
            phase("Insulation", nov(9), nov(13), PhasePredecessor("Framing")),
            phase("Drywall", nov(16), nov(20), PhasePredecessor("Insulation"))
        )
        val plan = planCascade(phases, "Framing", nov(5), nov(11), plain, zone)
        assertEquals(ShiftPlan.Loop(listOf("Framing", "Insulation", "Drywall", "Framing")), plan)
        // With cascade off nothing downstream is looked at, so the phase itself can still be moved.
        assertEquals(listOf("Framing"), moves(planCascade(phases, "Framing", nov(5), nov(11), plain, zone, cascade = false)).map { it.phase.name })
        // A loop further down the chain is caught too.
        val downstream = listOf(phase("Site prep", nov(2), nov(3))) + phases.map { if (it.id == "Framing") it.copy(predecessors = it.predecessors + PhasePredecessor("Site prep")) else it }
        assertTrue(planCascade(downstream, "Site prep", nov(3), nov(4), plain, zone) is ShiftPlan.Loop)
    }

    // ---- things linked to a phase that moves ----

    private fun at(d: LocalDate, hour: Int, minute: Int = 0) = LocalDateTime(d.year, d.monthNumber, d.dayOfMonth, hour, minute).toInstant(zone).toEpochMilliseconds()

    @Test
    fun linkedAppointmentsMoveWithThePhaseAtTheSameTimeOfDay() {
        val inspection = Appointment(id = "a1", title = "Rough-in inspection", startDate = at(nov(13), 13, 30), endDate = at(nov(13), 14, 30))
        val weekly = Appointment(id = "a2", title = "Weekly Meeting", startDate = at(nov(10), 9), endDate = at(nov(10), 10), recurrence = "Weekly")
        val lastWeek = Appointment(id = "a3", title = "Delivery", startDate = at(nov(3), 7), endDate = at(nov(3), 8))
        val byId = listOf(inspection, weekly, lastWeek).associateBy { it.id }
        val plumbing = phase("Rough plumbing", nov(9), nov(13)).copy(appointmentIds = listOf("a1", "a2", "a3", "gone"))
        val move = PhaseMove(plumbing, nov(9), nov(13), nov(12), nov(18), startShift = 3, endShift = 3, isOrigin = true)
        val moved = appointmentMoves(move, byId, today = nov(9), calendar = plain, zone = zone)
        assertEquals(listOf("a1"), moved.map { it.appointment.id }, "not the repeating meeting, not the delivery that already happened")
        assertEquals(at(nov(18), 13, 30), moved.single().newStart, "Fri Nov 13 + 3 working days is Wed Nov 18, still 1:30 PM")
        assertEquals(at(nov(18), 14, 30), moved.single().newEnd)
        // Only the end moved: appointments stay where they are.
        assertEquals(emptyList(), appointmentMoves(move.copy(newStart = nov(9), startShift = 0), byId, nov(9), plain, zone))
    }

    @Test
    fun linkedTasksMoveTheirDatesUnlessTheyAreDoneOrUndated() {
        val order = Task(id = "t1", title = "Order fixtures", status = "In Progress", dueDate = nov(13).toLocalMidnightMillis(zone))
        val rough = Task(id = "t2", title = "Rough-in", status = "Not Started", startDate = nov(9).toLocalMidnightMillis(zone), dueDate = nov(12).toLocalMidnightMillis(zone))
        val done = Task(id = "t3", title = "Permit", status = "Completed", dueDate = nov(6).toLocalMidnightMillis(zone))
        val undated = Task(id = "t4", title = "Walk-through", status = "Not Started")
        val byId = listOf(order, rough, done, undated).associateBy { it.id }
        val plumbing = phase("Rough plumbing", nov(9), nov(13)).copy(taskIds = listOf("t1", "t2", "t3", "t4"))
        val move = PhaseMove(plumbing, nov(9), nov(13), nov(12), nov(18), startShift = 3, endShift = 3, isOrigin = true)
        val moved = taskMoves(move, byId, plain, zone)
        assertEquals(listOf("t1", "t2"), moved.map { it.task.id })
        assertEquals(null, moved[0].newStart)
        assertEquals(nov(18).toLocalMidnightMillis(zone), moved[0].newDue)
        assertEquals(nov(12).toLocalMidnightMillis(zone), moved[1].newStart)
        assertEquals(nov(17).toLocalMidnightMillis(zone), moved[1].newDue, "Thu Nov 12 + 3 working days is Tue Nov 17")
    }
}
