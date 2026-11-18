package com.djransom.crewsync.util

import com.djransom.crewsync.data.model.PercentMode
import com.djransom.crewsync.data.model.PhasePredecessor
import com.djransom.crewsync.data.model.PhaseStatus
import com.djransom.crewsync.data.model.PredecessorType
import com.djransom.crewsync.data.model.SchedulePhase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Reading a schedule phase that is missing fields or holds values of the wrong type. This covers
 * the field-by-field reader the app falls back to when a document can't be decoded in one go
 * (see toSchedulePhaseSafe in FirestoreUtils.kt). Run with: gradlew :composeApp:desktopTest
 */
class SchedulePhaseParsingTest {
    private fun parse(id: String, fields: Map<String, Any?>): SchedulePhase = schedulePhaseFromFields(id) { fields[it] }

    @Test
    fun anEmptyDocumentBecomesADefaultPhase() {
        val phase = parse("p1", emptyMap())
        assertEquals(SchedulePhase(id = "p1"), phase)
        assertEquals(PhaseStatus.NOT_STARTED, phase.status)
        assertEquals(PercentMode.AUTO, phase.percentMode)
        assertNull(phase.start)
        assertNull(phase.end)
        assertNull(phase.baselineEnd)
        assertTrue(phase.predecessors.isEmpty())
        assertTrue(phase.taskIds.isEmpty())
        assertFalse(phase.isMilestone)
    }

    @Test
    fun aPhaseWithOnlyANameAndDatesStillLoads() {
        val phase = parse("p2", mapOf("name" to "Insulation", "start" to 1_795_150_800_000.0, "end" to 1_796_446_800_000.0))
        assertEquals("Insulation", phase.name)
        assertEquals(1_795_150_800_000L, phase.start)
        assertEquals(1_796_446_800_000L, phase.end)
        assertEquals("", phase.group)
        assertEquals(0, phase.durationDays)
        assertEquals(PhaseStatus.NOT_STARTED, phase.status)
    }

    @Test
    fun numbersAreAcceptedInAnyForm() {
        val phase = parse("p3", mapOf(
            "start" to 1_795_150_800_000.0,   // Double, as the app writes it
            "end" to 1_796_446_800_000L,      // Long, as the connector writes it
            "baselineStart" to 1_795_150_800, // Int
            "baselineEnd" to "1796446800000", // numeric text
            "durationDays" to 12.0,
            "percentComplete" to 40L,
            "order" to 3
        ))
        assertEquals(1_795_150_800_000L, phase.start)
        assertEquals(1_796_446_800_000L, phase.end)
        assertEquals(1_795_150_800L, phase.baselineStart)
        assertEquals(1_796_446_800_000L, phase.baselineEnd)
        assertEquals(12, phase.durationDays)
        assertEquals(40, phase.percentComplete)
        assertEquals(3.0, phase.order)
    }

    @Test
    fun valuesOfTheWrongTypeFallBackToDefaults() {
        val phase = parse("p4", mapOf(
            "name" to 42,                  // not text
            "start" to "next Tuesday",     // not a number
            "end" to null,
            "isMilestone" to "yes",        // not a boolean
            "durationDays" to listOf(1),
            "predecessors" to "p1",        // not a list
            "taskIds" to mapOf("a" to 1),  // not a list
            "notes" to null
        ))
        assertEquals("", phase.name)
        assertNull(phase.start)
        assertNull(phase.end)
        assertFalse(phase.isMilestone)
        assertEquals(0, phase.durationDays)
        assertTrue(phase.predecessors.isEmpty())
        assertTrue(phase.taskIds.isEmpty())
        assertEquals("", phase.notes)
    }

    @Test
    fun outOfRangeValuesAreTidied() {
        val phase = parse("p5", mapOf(
            "status" to "  in   PROGRESS ",
            "percentMode" to "Manual",
            "percentComplete" to 140,
            "durationDays" to -3
        ))
        assertEquals(PhaseStatus.IN_PROGRESS, phase.status)
        assertEquals(PercentMode.MANUAL, phase.percentMode)
        assertEquals(100, phase.percentComplete)
        assertEquals(0, phase.durationDays)
        assertEquals(PhaseStatus.NOT_STARTED, parse("p5", mapOf("status" to "Cancelled")).status, "an unknown status")
        assertEquals(0, parse("p5", mapOf("percentComplete" to -5)).percentComplete)
    }

    @Test
    fun predecessorsTolerateMissingPartsAndJunk() {
        val phase = parse("p6", mapOf("predecessors" to listOf(
            mapOf("phaseId" to "roof", "type" to "SS", "lagDays" to -2.0),
            mapOf("phaseId" to "siding"),                       // no type, no lag
            mapOf("phaseId" to "septic", "type" to "finish"),   // unknown type
            mapOf("type" to "FS", "lagDays" to 3),              // no phase
            mapOf("phaseId" to "p6"),                           // itself
            mapOf("phaseId" to "roof", "lagDays" to 9),         // a repeat
            "not a predecessor",
            null
        )))
        assertEquals(
            listOf(
                PhasePredecessor("roof", PredecessorType.SS, -2),
                PhasePredecessor("siding", PredecessorType.FS, 0),
                PhasePredecessor("septic", PredecessorType.FS, 0)
            ),
            phase.predecessors
        )
    }

    @Test
    fun linkListsDropJunkAndRepeats() {
        val phase = parse("p7", mapOf(
            "taskIds" to listOf("t1", 7, null, "", "t2", "t1"),
            "appointmentIds" to listOf("a1", "a1")
        ))
        assertEquals(listOf("t1", "t2"), phase.taskIds)
        assertEquals(listOf("a1"), phase.appointmentIds)
    }

    @Test
    fun aFullPhaseSurvivesBeingWrittenAndReadBack() {
        val phase = SchedulePhase(
            id = "p8", projectId = "pM", environmentId = "env1",
            name = "Drywall", group = "Interior", order = 2.5,
            start = 1_796_446_800_000L, end = 1_799_125_200_000L, durationDays = 22,
            isMilestone = false,
            predecessors = listOf(PhasePredecessor("insulation", PredecessorType.FS, -1)),
            trade = "Drywall", contactId = "c9", contactName = "A+ Drywall",
            status = PhaseStatus.IN_PROGRESS, percentComplete = 35, percentMode = PercentMode.MANUAL,
            baselineStart = 1_796_446_800_000L, baselineEnd = 1_799_125_200_000L,
            notes = "Hang first, tape after inspection", color = "#2196F3",
            taskIds = listOf("t1", "t2"), appointmentIds = listOf("a1"),
            createdAt = 1_795_000_000_000L, updatedAt = 1_795_100_000_000L, updatedBy = "dan@example.com"
        )
        val stored = phase.toFirestoreMap()
        assertEquals(phase, parse("p8", stored))
    }

    @Test
    fun writingAPhaseLeavesOutEmptyListsAndMissingDates() {
        val stored = SchedulePhase(id = "p9", name = "Painting").toFirestoreMap()
        // An empty list crashes the web encoder (see Task.toFirestoreMap), so none may be written.
        for (key in listOf("predecessors", "taskIds", "appointmentIds", "start", "end", "baselineStart", "baselineEnd", "color")) {
            assertFalse(stored.containsKey(key), "$key should be left out when empty")
        }
        assertTrue(stored.values.none { it is List<*> && it.isEmpty() })
        assertEquals("Painting", stored["name"])
        assertEquals(PhaseStatus.NOT_STARTED, stored["status"])

        val dated = SchedulePhase(id = "p9", start = 1_796_446_800_000L).toFirestoreMap()
        assertEquals(1_796_446_800_000.0, dated["start"], "dates are written as Doubles, like task dates")
        // A phase read back from that map is the phase that was written.
        assertEquals(SchedulePhase(id = "p9", name = "Painting"), parse("p9", stored))
    }
}
