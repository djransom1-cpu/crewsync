package com.djransom.crewsync.util

import com.djransom.crewsync.data.model.PercentMode
import com.djransom.crewsync.data.model.PhasePredecessor
import com.djransom.crewsync.data.model.PhaseStatus
import com.djransom.crewsync.data.model.PredecessorType
import com.djransom.crewsync.data.model.SchedulePhase
import dev.gitlive.firebase.internal.decode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNull

/**
 * Reads phase documents through the Firestore library's own decoder - the same call that
 * DocumentSnapshot.data<T>() and .get<T>() make - fed with values shaped the way Firestore hands
 * them back on Android and desktop (whole numbers as Long, the rest as Double, lists, maps).
 *
 * SchedulePhaseParsingTest covers the app's field-by-field reader; this covers the step before
 * it, so a phase written by the connector is known to load. Run with: gradlew :composeApp:desktopTest
 */
class GitLiveDecodingTest {
    /** A phase exactly as functions/schedule.js phaseWriteMap() saves it: whole numbers, no empty lists, no nulls. */
    private val fromConnector: Map<String, Any?> = mapOf(
        "id" to "abc123", "projectId" to "pM", "environmentId" to "env1",
        "name" to "Drywall", "group" to "Interior",
        "order" to 10L,                                   // a whole number where the app expects a Double
        "start" to 1_796_446_800_000L, "end" to 1_799_125_200_000L,
        "durationDays" to 22L, "isMilestone" to false,
        "predecessors" to listOf(mapOf("phaseId" to "ins1", "type" to "FS", "lagDays" to -1L)),
        "trade" to "", "contactId" to "", "contactName" to "A+ Drywall",
        "status" to "In progress", "percentComplete" to 35L, "percentMode" to "manual",
        "baselineStart" to 1_796_446_800_000L, "baselineEnd" to 1_799_125_200_000L,
        "notes" to "", "taskIds" to listOf("t1", "t2"),
        "createdAt" to 1_795_000_000_000L, "updatedAt" to 1_795_100_000_000L, "updatedBy" to "dan@example.com"
    )

    @Test
    fun aPhaseWrittenByTheConnectorDecodesInOneGo() {
        val phase = decode<SchedulePhase>(fromConnector)
        assertEquals(
            SchedulePhase(
                id = "abc123", projectId = "pM", environmentId = "env1", name = "Drywall", group = "Interior", order = 10.0,
                start = 1_796_446_800_000L, end = 1_799_125_200_000L, durationDays = 22, isMilestone = false,
                predecessors = listOf(PhasePredecessor("ins1", PredecessorType.FS, -1)),
                contactName = "A+ Drywall", status = PhaseStatus.IN_PROGRESS, percentComplete = 35, percentMode = PercentMode.MANUAL,
                baselineStart = 1_796_446_800_000L, baselineEnd = 1_799_125_200_000L,
                taskIds = listOf("t1", "t2"), appointmentIds = emptyList(),
                createdAt = 1_795_000_000_000L, updatedAt = 1_795_100_000_000L, updatedBy = "dan@example.com"
            ),
            phase
        )
    }

    @Test
    fun missingFieldsTakeTheirDefaultsAndUnknownFieldsAreIgnored() {
        val phase = decode<SchedulePhase>(mapOf("name" to "Painting", "somethingNewer" to 7L, "start" to 1_796_446_800_000.0))
        assertEquals(SchedulePhase(name = "Painting", start = 1_796_446_800_000L), phase)
        assertEquals(SchedulePhase(), decode<SchedulePhase>(emptyMap<String, Any?>()))
    }

    @Test
    fun aDateThatIsNotANumberFailsTheWholeDocument() {
        // ...which is when toSchedulePhaseSafe() falls back to reading field by field.
        assertFails { decode<SchedulePhase>(fromConnector + ("start" to "next Tuesday")) }
        // A list field holding something that is not a list is more forgiving: it reads as empty.
        assertEquals(emptyList(), decode<SchedulePhase>(fromConnector + ("taskIds" to "t1")).taskIds)
    }

    // ---- what the field-by-field fallback (loosePhaseField in FirestoreUtils.kt) relies on ----

    @Test
    fun aFieldCannotBeReadWithoutSayingItsType() {
        assertFails { decode<Any?>("Drywall") }
    }

    @Test
    fun singleFieldsReadLoosely() {
        assertEquals(1_796_446_800_000.0, decode<Double?>(1_796_446_800_000L))
        assertEquals(1_796_446_800_000.0, decode<Double?>("1796446800000" as Any), "a number stored as text")
        assertNull(decode<Double?>(null), "a missing field")
        assertFails { decode<Double?>("next Tuesday") }
        assertEquals("Drywall", decode<String?>("Drywall"))
        assertNull(decode<String?>(null))
        assertEquals(true, decode<Boolean?>(true))
        assertEquals(listOf("t1", "t2"), decode<List<String>?>(listOf("t1", "t2")))
        assertNull(decode<List<String>?>(null))
        assertEquals(emptyList(), decode<List<String>?>("t1" as Any), "not a list reads as an empty one")
        assertEquals(
            listOf(PhasePredecessor("roof", PredecessorType.SS, -2), PhasePredecessor("siding")),
            decode<List<PhasePredecessor>?>(listOf(mapOf("phaseId" to "roof", "type" to "SS", "lagDays" to -2.0), mapOf("phaseId" to "siding")))
        )
    }
}
