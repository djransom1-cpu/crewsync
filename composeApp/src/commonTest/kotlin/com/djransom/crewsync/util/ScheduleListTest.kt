package com.djransom.crewsync.util

import com.djransom.crewsync.data.model.PercentMode
import com.djransom.crewsync.data.model.PhaseStatus
import com.djransom.crewsync.data.model.SchedulePhase
import com.djransom.crewsync.data.model.Task
import com.djransom.crewsync.i18n.I18n
import com.djransom.crewsync.util.ScheduleSampleData.day
import com.djransom.crewsync.util.ScheduleSampleData.martinInProgress
import com.djransom.crewsync.util.ScheduleSampleData.martinPlan
import com.djransom.crewsync.util.ScheduleSampleData.roofTasks
import com.djransom.crewsync.util.ScheduleSampleData.zone
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * How the Schedule tab lays its rows out: order, grouping, dates, working days and percent, using
 * the Martin-shaped sample schedule. Run with: gradlew :composeApp:desktopTest
 */
class ScheduleListTest {
    @BeforeTest
    fun english() = I18n.init("en") // date wording is checked in English whatever the machine's language

    private fun named(name: String, from: List<SchedulePhase> = martinPlan) = from.first { it.name == name }

    // ---- sorting ----

    @Test
    fun phasesComeBackInOrderHoweverTheyArrive() {
        val shuffled = martinPlan.reversed().let { it.drop(5) + it.take(5) }
        assertEquals(martinPlan.map { it.name }, shuffled.inScheduleOrder().map { it.name })
    }

    @Test
    fun aTieOnOrderGoesToTheEarlierStartThenTheName() {
        val a = SchedulePhase(id = "a", name = "Zinc flashing", order = 1.0, start = 2_000L)
        val b = SchedulePhase(id = "b", name = "Framing", order = 1.0, start = 1_000L)
        val c = SchedulePhase(id = "c", name = "Backfill", order = 1.0) // no dates yet: last
        val d = SchedulePhase(id = "d", name = "apron", order = 1.0)    // name order ignores case
        val e = SchedulePhase(id = "e", name = "Site prep", order = 0.5, start = 9_000L)
        assertEquals(listOf("e", "b", "a", "d", "c"), listOf(a, b, c, d, e).inScheduleOrder().map { it.id })
    }

    // ---- grouping ----

    @Test
    fun theMartinScheduleFallsIntoItsFourGroupsInOrder() {
        val groups = groupPhases(martinPlan.shuffledForTest())
        assertEquals(listOf("Exterior / Site", "Rough trades", "Interior", "Closeout"), groups.map { it.name })
        assertEquals(listOf(5, 3, 5, 1), groups.map { it.phases.size })
        assertEquals(
            listOf("Insulation", "Drywall", "Tile & flooring", "Painting", "Trim & cabinets"),
            groups[2].phases.map { it.name }
        )
    }

    @Test
    fun aGroupStaysTogetherEvenWhenItsPhasesAreSplitUpInTheOrder() {
        val phases = listOf(
            SchedulePhase(id = "1", name = "Roof", group = "Exterior", order = 1.0),
            SchedulePhase(id = "2", name = "Insulation", group = "Interior", order = 2.0),
            SchedulePhase(id = "3", name = "Siding", group = " exterior ", order = 3.0), // different case and spacing
            SchedulePhase(id = "4", name = "Landscaping", order = 4.0),                   // no group
            SchedulePhase(id = "5", name = "Drywall", group = "Interior", order = 5.0)
        )
        val groups = groupPhases(phases)
        assertEquals(listOf("Exterior", "Interior", ""), groups.map { it.name })
        assertEquals(listOf(listOf("Roof", "Siding"), listOf("Insulation", "Drywall"), listOf("Landscaping")), groups.map { g -> g.phases.map { it.name } })
    }

    @Test
    fun noPhasesMeansNoGroups() {
        assertEquals(emptyList(), groupPhases(emptyList()))
    }

    // ---- dates ----

    @Test
    fun datesReadAsOneLine() {
        fun dates(name: String) = named(name).let { formatPhaseDates(it.startDate(zone), it.endDate(zone)) }
        assertEquals("Sep 21 – Oct 15, 2026", dates("Roof complete"))
        assertEquals("Sep 25, 2026", dates("Exterior walls at chimney"), "a milestone shows its one day")
        assertEquals("Dec 4, 2026 – Jan 4, 2027", dates("Drywall"), "over New Year both years show")
        assertEquals("Mar 15 – Apr 5, 2027", dates("Finish MEP"))
        assertEquals("", formatPhaseDates(null, null), "no dates yet")
        assertEquals("Dec 1, 2026", formatPhaseDates(day(2026, 12, 1), null))
        assertEquals("Dec 1, 2026", formatPhaseDates(null, day(2026, 12, 1)))
    }

    @Test
    fun datesFollowTheChosenLanguage() {
        I18n.init("de")
        assertEquals("21. Sep. – 15. Okt. 2026", formatPhaseDates(day(2026, 9, 21), day(2026, 10, 15)))
        I18n.init("pl")
        assertEquals("4 gru 2026 – 4 sty 2027", formatPhaseDates(day(2026, 12, 4), day(2027, 1, 4)))
        I18n.init("en")
    }

    // ---- working days ----

    @Test
    fun workingDaysAreCountedFromTheDates() {
        val plain = WorkCalendar()
        val thanksgiving = WorkCalendar(holidays = setOf(day(2026, 11, 26), day(2026, 11, 27)))
        assertEquals(19, named("Roof complete").workingDays(plain, zone))
        assertEquals(1, named("Exterior walls at chimney").workingDays(plain, zone))
        assertEquals(12, named("Insulation").workingDays(plain, zone))
        assertEquals(10, named("Insulation").workingDays(thanksgiving, zone), "a new holiday shows without re-saving the phase")
        assertEquals(58, named("Roof complete", martinInProgress).workingDays(thanksgiving, zone), "Sep 21 to Dec 11 with Thanksgiving off")
    }

    @Test
    fun aPhaseWithNoDatesShowsItsStoredLengthOrNothing() {
        assertEquals(8, SchedulePhase(id = "x", durationDays = 8).workingDays(WorkCalendar(), zone))
        assertNull(SchedulePhase(id = "x").workingDays(WorkCalendar(), zone))
        assertNull(named("Punch list", martinInProgress).workingDays(WorkCalendar(), zone))
    }

    // ---- percent ----

    @Test
    fun percentComesFromTheLinkedTasksChecklists() {
        val byId = roofTasks.associateBy { it.id }
        val roof = named("Roof complete", martinInProgress)
        assertEquals(listOf("Roof tear-off", "Order ridge vent"), roof.linkedTasks(byId).map { it.title })
        // 1 of 4 items ticked, plus a finished task with no checklist counting as one ticked item: 2 of 5
        assertEquals(40, roof.percentShown(roof.linkedTasks(byId)))
        assertEquals(25, autoPhasePercent(roofTasks.take(1)))
        assertEquals(100, autoPhasePercent(roofTasks.drop(1)))
        assertEquals(0, autoPhasePercent(listOf(Task(id = "t", status = "In Progress"))))
        assertNull(autoPhasePercent(emptyList()))
    }

    @Test
    fun aTaskThatWasDeletedIsSkipped() {
        val roof = named("Roof complete", martinInProgress).copy(taskIds = listOf("gone", "tRoof"))
        val linked = roof.linkedTasks(roofTasks.associateBy { it.id })
        assertEquals(listOf("tRoof"), linked.map { it.id })
        assertEquals(25, roof.percentShown(linked))
    }

    @Test
    fun aTypedPercentWinsAndDoneIsAlwaysOneHundred() {
        val siding = named("Siding", martinInProgress)
        assertEquals(60, siding.percentShown(roofTasks), "manual ignores the checklists")
        assertEquals(100, siding.copy(status = PhaseStatus.DONE).percentShown(emptyList()))
        assertEquals(100, named("Electrical service", martinInProgress).percentShown(emptyList()))
        assertEquals(0, named("Drywall", martinInProgress).percentShown(emptyList()), "nothing linked, nothing typed")
        assertEquals(35, SchedulePhase(id = "x", percentMode = PercentMode.AUTO, percentComplete = 35).percentShown(emptyList()), "nothing linked falls back to the stored number")
    }

    // A fixed scramble (no randomness, so a failure repeats): evens then odds, reversed.
    private fun List<SchedulePhase>.shuffledForTest(): List<SchedulePhase> =
        (filterIndexed { i, _ -> i % 2 == 0 } + filterIndexed { i, _ -> i % 2 == 1 }).reversed()
}
