package com.djransom.crewsync.util

import com.djransom.crewsync.data.model.PercentMode
import com.djransom.crewsync.data.model.PhasePredecessor
import com.djransom.crewsync.data.model.PhaseStatus
import com.djransom.crewsync.data.model.PredecessorType
import com.djransom.crewsync.data.model.SchedulePhase
import com.djransom.crewsync.data.model.Task
import com.djransom.crewsync.util.ScheduleSampleData.day
import com.djransom.crewsync.util.ScheduleSampleData.martinInProgress
import com.djransom.crewsync.util.ScheduleSampleData.martinPlan
import com.djransom.crewsync.util.ScheduleSampleData.roofTasks
import com.djransom.crewsync.util.ScheduleSampleData.zone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The rules behind the phase edit screen, the Phase chips and the "Mark this phase done?"
 * question. Run with: gradlew :composeApp:desktopTest
 *
 * Calendar used (2026): Nov 19 is a Thursday, Dec 4 a Friday, Dec 7 a Monday; Thanksgiving is
 * Thu Nov 26 and the Friday after it is also taken off.
 */
class SchedulePhaseEditTest {
    private val plain = WorkCalendar()
    private val thanksgiving = WorkCalendar(holidays = setOf(day(2026, 11, 26), day(2026, 11, 27)))
    private fun named(name: String, from: List<SchedulePhase> = martinPlan) = from.first { it.name == name }

    // ---- dates ----

    @Test
    fun aNewStartMovesThePhaseAndKeepsItsLength() {
        val insulation = named("Insulation").datesForEdit(zone)     // Thu Nov 19 - Fri Dec 4, 12 working days
        assertEquals(12, insulation.durationDays)
        val moved = insulation.withStart(day(2026, 11, 23), plain)  // start the Monday after instead
        assertEquals(day(2026, 11, 23), moved.start)
        assertEquals(day(2026, 12, 8), moved.end, "12 working days from Mon Nov 23 ends Tue Dec 8")
        assertEquals(12, moved.durationDays)
        // With Thanksgiving off, Nov 19 - Dec 4 is only 10 working days, and it is those 10 that are kept.
        val aroundTheHoliday = insulation.withStart(day(2026, 11, 23), thanksgiving)
        assertEquals(10, aroundTheHoliday.durationDays)
        assertEquals(day(2026, 12, 8), aroundTheHoliday.end, "Mon-Wed, then the week after, then Mon-Tue")
    }

    @Test
    fun aNewEndStretchesThePhaseFromItsStart() {
        val roof = named("Roof complete").datesForEdit(zone)        // Sep 21 - Oct 15, 19 working days
        val late = roof.withEnd(day(2026, 12, 11), thanksgiving)
        assertEquals(day(2026, 9, 21), late.start, "the start stays put")
        assertEquals(day(2026, 12, 11), late.end)
        assertEquals(58, late.durationDays)
        assertFalse(late.endsBeforeStart)
    }

    @Test
    fun aNewLengthMovesTheEnd() {
        val drywall = named("Drywall").datesForEdit(zone)           // starts Fri Dec 4
        val shorter = drywall.withDuration(3, plain)
        assertEquals(day(2026, 12, 8), shorter.end, "Fri, Mon, Tue")
        assertEquals(3, shorter.durationDays)
        assertEquals(1, drywall.withDuration(0, plain).durationDays, "never shorter than a day")
        assertEquals(day(2026, 12, 4), drywall.withDuration(1, plain).end)
    }

    @Test
    fun aPhaseWithNoDatesYetCanBeGivenALengthThenAStart() {
        val blank = PhaseDatesEdit()
        val fiveDays = blank.withDuration(5, plain)
        assertNull(fiveDays.start)
        assertEquals(5, fiveDays.durationDays)
        val dated = fiveDays.withStart(day(2026, 12, 7), plain)     // a Monday
        assertEquals(day(2026, 12, 11), dated.end, "Monday to Friday")
        assertEquals(5, dated.durationDays)
        // A start with nothing else is just a start: there's no length to work an end out from.
        val startOnly = blank.withStart(day(2026, 12, 7), plain)
        assertNull(startOnly.end)
        assertEquals(day(2026, 12, 9), startOnly.withEnd(day(2026, 12, 9), plain).end)
        assertEquals(3, startOnly.withEnd(day(2026, 12, 9), plain).durationDays)
    }

    @Test
    fun anEndBeforeTheStartIsFlaggedNotSilentlyFixed() {
        val backwards = named("Insulation").datesForEdit(zone).withEnd(day(2026, 11, 10), plain)
        assertTrue(backwards.endsBeforeStart)
        assertEquals(0, backwards.durationDays)
        assertEquals(day(2026, 11, 19), backwards.start)
    }

    @Test
    fun aMilestoneIsAlwaysOneDay() {
        val roof = named("Roof complete").datesForEdit(zone)
        val marker = roof.withMilestone(true, plain)
        assertTrue(marker.isMilestone)
        assertEquals(day(2026, 9, 21), marker.start)
        assertEquals(day(2026, 9, 21), marker.end, "it collapses onto its start")
        assertEquals(1, marker.durationDays)
        val moved = marker.withEnd(day(2026, 10, 15), plain)
        assertEquals(day(2026, 10, 15), moved.start, "moving either date moves the marker")
        assertEquals(day(2026, 10, 15), moved.withStart(day(2026, 10, 15), plain).end)
        assertEquals(marker, marker.withDuration(9, plain), "a milestone has no length to change")
        assertFalse(marker.withMilestone(false, plain).isMilestone)
        assertTrue(PhaseDatesEdit().withMilestone(true, plain).isMilestone, "a milestone can be undated too")
    }

    @Test
    fun clearingTheDatesKeepsTheLength() {
        val cleared = named("Insulation").datesForEdit(zone).cleared()
        assertNull(cleared.start)
        assertNull(cleared.end)
        assertEquals(12, cleared.durationDays)
    }

    @Test
    fun editedDatesGoBackOntoThePhaseAsLocalMidnights() {
        val insulation = named("Insulation")
        val edited = insulation.withDates(insulation.datesForEdit(zone).withStart(day(2026, 11, 23), plain), zone)
        assertEquals(day(2026, 11, 23).toLocalMidnightMillis(zone), edited.start)
        assertEquals(day(2026, 12, 8).toLocalMidnightMillis(zone), edited.end)
        assertEquals(12, edited.durationDays)
        assertEquals(insulation.baselineEnd, edited.baselineEnd, "the baseline never moves on an edit")
        assertEquals(insulation, insulation.withDates(insulation.datesForEdit(zone), zone), "no edit, no change")
    }

    // ---- predecessors ----

    @Test
    fun aPhaseCannotWaitOnItselfOrOnAnythingThatWaitsOnIt() {
        // Insulation <- Drywall <- Tile <- Painting, and Trim also waits on Drywall.
        val chain = listOf(
            SchedulePhase(id = "ins", name = "Insulation"),
            SchedulePhase(id = "dry", name = "Drywall", predecessors = listOf(PhasePredecessor("ins"))),
            SchedulePhase(id = "tile", name = "Tile", predecessors = listOf(PhasePredecessor("dry", PredecessorType.SS, 5))),
            SchedulePhase(id = "paint", name = "Painting", predecessors = listOf(PhasePredecessor("tile"))),
            SchedulePhase(id = "trim", name = "Trim", predecessors = listOf(PhasePredecessor("dry"))),
            SchedulePhase(id = "roof", name = "Roof")
        )
        assertEquals(setOf("ins", "dry", "tile", "paint", "trim"), blockedPredecessorIds(chain, "ins"), "everything downstream of Insulation")
        assertEquals(setOf("tile", "paint"), blockedPredecessorIds(chain, "tile"))
        assertEquals(setOf("paint"), blockedPredecessorIds(chain, "paint"), "the end of the chain can wait on anything else")
        assertEquals(setOf("roof"), blockedPredecessorIds(chain, "roof"))
        assertEquals(setOf(""), blockedPredecessorIds(chain, ""), "a phase not saved yet blocks nothing real")
    }

    @Test
    fun aLoopAlreadyInTheDataDoesNotHangTheCheck() {
        val loop = listOf(
            SchedulePhase(id = "a", predecessors = listOf(PhasePredecessor("b"))),
            SchedulePhase(id = "b", predecessors = listOf(PhasePredecessor("a")))
        )
        assertEquals(setOf("a", "b"), blockedPredecessorIds(loop, "a"))
    }

    // ---- links ----

    @Test
    fun linkingATaskPutsItOnOnePhaseOnly() {
        val roof = named("Roof complete", martinInProgress)       // holds tRoof and tRidge
        val siding = named("Siding", martinInProgress)
        assertEquals("Roof complete", phaseOfTask(martinInProgress, "tRoof")?.name)
        assertNull(phaseOfTask(martinInProgress, "tNew"))
        // a task on no phase: one addition
        assertEquals(listOf(PhaseLinkOp(siding.id, PhaseLinkKind.Task, "tNew", add = true)), linkOps(martinInProgress, PhaseLinkKind.Task, "tNew", siding.id))
        // a task already on another phase: it comes off that one first
        assertEquals(
            listOf(PhaseLinkOp(roof.id, PhaseLinkKind.Task, "tRoof", add = false), PhaseLinkOp(siding.id, PhaseLinkKind.Task, "tRoof", add = true)),
            linkOps(martinInProgress, PhaseLinkKind.Task, "tRoof", siding.id)
        )
        // already where it should be: nothing to do
        assertEquals(emptyList(), linkOps(martinInProgress, PhaseLinkKind.Task, "tRoof", roof.id))
        // off every phase
        assertEquals(listOf(PhaseLinkOp(roof.id, PhaseLinkKind.Task, "tRoof", add = false)), linkOps(martinInProgress, PhaseLinkKind.Task, "tRoof", null))
        assertEquals(emptyList(), linkOps(martinInProgress, PhaseLinkKind.Task, "tNew", null))
        // a phase that no longer exists: it only comes off where it was
        assertEquals(listOf(PhaseLinkOp(roof.id, PhaseLinkKind.Task, "tRoof", add = false)), linkOps(martinInProgress, PhaseLinkKind.Task, "tRoof", "deleted"))
    }

    @Test
    fun appointmentsLinkTheSameWayThroughTheirOwnList() {
        val roof = named("Roof complete", martinInProgress)       // holds aWeekly and aRoofer
        val drywall = named("Drywall", martinInProgress)
        assertEquals("Roof complete", phaseOfAppointment(martinInProgress, "aWeekly")?.name)
        assertEquals("appointmentIds", PhaseLinkKind.Appointment.field)
        assertEquals("taskIds", PhaseLinkKind.Task.field)
        assertEquals(
            listOf(PhaseLinkOp(roof.id, PhaseLinkKind.Appointment, "aWeekly", add = false), PhaseLinkOp(drywall.id, PhaseLinkKind.Appointment, "aWeekly", add = true)),
            linkOps(martinInProgress, PhaseLinkKind.Appointment, "aWeekly", drywall.id)
        )
        // a task id is not confused with an appointment id
        assertEquals(listOf(PhaseLinkOp(drywall.id, PhaseLinkKind.Appointment, "tRoof", add = true)), linkOps(martinInProgress, PhaseLinkKind.Appointment, "tRoof", drywall.id))
    }

    // ---- "Mark this phase done?" ----

    @Test
    fun aPhaseIsOfferedForDoneOnlyWhenEveryLinkedTaskIsComplete() {
        val roof = named("Roof complete", martinInProgress)
        val byId = roofTasks.associateBy { it.id }
        assertEquals(emptyList(), phasesReadyToMarkDone(martinInProgress, byId), "the tear-off is still in progress")
        val allDone = byId + ("tRoof" to byId.getValue("tRoof").copy(status = "Done"))
        assertEquals(listOf(roof.id), phasesReadyToMarkDone(martinInProgress, allDone).map { it.id })
        // a ticked checklist is not the same as a completed card: the card has to be in a Done bucket
        val tickedButOpen = byId.getValue("tRoof").let { t -> t.copy(checklistGroups = t.checklistGroups.map { g -> g.copy(items = g.items.map { it.copy(isDone = true) }) }) }
        assertEquals(emptyList(), phasesReadyToMarkDone(martinInProgress, byId + ("tRoof" to tickedButOpen)))
    }

    @Test
    fun noQuestionForAPhaseThatIsDoneOrHasNoTasks() {
        val done = mapOf("t" to Task(id = "t", status = "Completed"))
        assertEquals(emptyList(), phasesReadyToMarkDone(listOf(SchedulePhase(id = "p", status = PhaseStatus.DONE, taskIds = listOf("t"))), done), "already done")
        assertEquals(emptyList(), phasesReadyToMarkDone(listOf(SchedulePhase(id = "p")), done), "nothing linked")
        assertEquals(emptyList(), phasesReadyToMarkDone(listOf(SchedulePhase(id = "p", taskIds = listOf("gone"))), done), "its only task was deleted")
        assertEquals(listOf("p"), phasesReadyToMarkDone(listOf(SchedulePhase(id = "p", status = PhaseStatus.ON_HOLD, taskIds = listOf("t", "gone"))), done).map { it.id })
    }

    // ---- saving ----

    @Test
    fun onlyTheFieldsThatChangedAreSaved() {
        val roof = named("Roof complete", martinInProgress)
        assertEquals(emptyMap(), phaseFieldChanges(roof, roof), "nothing edited, nothing written")
        assertEquals(mapOf<String, Any?>("name" to "Roofing"), phaseFieldChanges(roof, roof.copy(name = "Roofing")))
        val edited = roof.copy(status = PhaseStatus.DONE, percentMode = PercentMode.MANUAL, percentComplete = 100, notes = "Signed off")
        assertEquals(
            mapOf<String, Any?>("status" to "Done", "percentComplete" to 100, "percentMode" to "manual", "notes" to "Signed off"),
            phaseFieldChanges(roof, edited)
        )
        // links and the baseline are never part of an edit
        val relinked = roof.copy(taskIds = emptyList(), appointmentIds = listOf("x"), baselineEnd = 1L, updatedAt = 5L, updatedBy = "someone")
        assertEquals(emptyMap(), phaseFieldChanges(roof, relinked))
    }

    @Test
    fun datesAreSavedAsNumbersAndClearedFieldsAreRemoved() {
        val insulation = named("Insulation")
        val moved = insulation.withDates(insulation.datesForEdit(zone).withStart(day(2026, 11, 23), plain), zone)
        assertEquals(
            mapOf<String, Any?>("start" to day(2026, 11, 23).toLocalMidnightMillis(zone).toDouble(), "end" to day(2026, 12, 8).toLocalMidnightMillis(zone).toDouble()),
            phaseFieldChanges(insulation, moved),
            "same length, so the working-day count is not rewritten"
        )
        val cleared = insulation.withDates(insulation.datesForEdit(zone).cleared(), zone)
        assertEquals(mapOf<String, Any?>("start" to null, "end" to null), phaseFieldChanges(insulation, cleared), "null means: remove the field")
        val withPredecessor = insulation.copy(predecessors = listOf(PhasePredecessor("roof", PredecessorType.SS, -2)), color = "#2196F3")
        assertEquals(
            mapOf<String, Any?>("predecessors" to listOf(PhasePredecessor("roof", PredecessorType.SS, -2)), "color" to "#2196F3"),
            phaseFieldChanges(insulation, withPredecessor)
        )
        assertEquals(mapOf<String, Any?>("predecessors" to null, "color" to null), phaseFieldChanges(withPredecessor, insulation), "an emptied list is removed, never written empty")
    }

    @Test
    fun whatWasTypedIsTidiedBeforeSaving() {
        val typed = SchedulePhase(
            id = "p", name = "  Drywall ", group = " Interior", trade = " Drywall  ", contactName = " A+ Drywall ", notes = "  Hang first\n",
            status = "in progress", percentMode = "Manual", percentComplete = 140, durationDays = -2,
            predecessors = listOf(PhasePredecessor("ins", "ss", 1), PhasePredecessor("p"), PhasePredecessor(""), PhasePredecessor("ins", "FS", 9))
        )
        val tidy = typed.tidiedForSave()
        assertEquals("Drywall", tidy.name)
        assertEquals("Interior", tidy.group)
        assertEquals("Drywall", tidy.trade)
        assertEquals("A+ Drywall", tidy.contactName)
        assertEquals("Hang first", tidy.notes)
        assertEquals(PhaseStatus.IN_PROGRESS, tidy.status)
        assertEquals(PercentMode.MANUAL, tidy.percentMode)
        assertEquals(100, tidy.percentComplete)
        assertEquals(0, tidy.durationDays)
        assertEquals(listOf(PhasePredecessor("ins", PredecessorType.SS, 1)), tidy.predecessors, "not itself, not blank, not twice")
    }
}

/** The working-day count shown on the edit screen comes from the dates, not from a stored number that may be stale. */
class SchedulePhaseEditCountTest {
    @Test
    fun aStaleStoredLengthIsRecountedFromTheDates() {
        val roof = martinInProgress.first { it.name == "Roof complete" }     // now ends Dec 11; its stored length still says 19
        assertEquals(19, roof.durationDays)
        assertEquals(19, roof.datesForEdit(zone).durationDays, "with no calendar to count with, the stored number is all there is")
        assertEquals(60, roof.datesForEdit(zone, WorkCalendar()).durationDays, "Sep 21 to Dec 11")
        val thanksgiving = WorkCalendar(holidays = setOf(day(2026, 11, 26), day(2026, 11, 27)))
        assertEquals(58, roof.datesForEdit(zone, thanksgiving).durationDays)
        // and it is the recounted length that is kept when the phase is then moved
        val moved = roof.datesForEdit(zone, thanksgiving).withStart(day(2026, 9, 28), thanksgiving)
        assertEquals(58, moved.durationDays)
        assertEquals(day(2026, 12, 18), moved.end)
        // a phase with no dates keeps whatever length it was given
        assertEquals(8, SchedulePhase(id = "x", durationDays = 8).datesForEdit(zone, thanksgiving).durationDays)
    }
}
