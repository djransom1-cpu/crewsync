package com.djransom.crewsync.data.model

import kotlinx.serialization.Serializable

/**
 * One bar on the project schedule ("Roofing", "Insulation", "Drywall"...), stored at
 * projects/{projectId}/schedulePhases/{id} - beside appointments, so deleting a project
 * (recursiveDelete in the deleteProject Cloud Function) cleans phases up with everything else.
 *
 * A phase sits above the work: the tasks and site-calendar appointments that carry it out are
 * linked through [taskIds] and [appointmentIds]. The link deliberately lives HERE and not as a
 * phaseId on the task/appointment: both the app and the connector save a task by rewriting the
 * whole document from the fields they know about, so any device still on an older build would
 * silently erase a phaseId the first time someone ticked a checklist item. Nothing but
 * schedule-aware code ever writes a phase document, so the link can't be lost that way - and
 * existing task/appointment records stay exactly as they were.
 *
 * Dates are stored the way task dates are: epoch milliseconds at local midnight (see
 * ScheduleDates.kt for the conversions and the working-day math).
 */
@Serializable
data class SchedulePhase(
    val id: String = "",
    val projectId: String = "",
    val environmentId: String = "",
    val name: String = "",
    val group: String = "",            // "Exterior / Site", "Rough trades", "Interior", "Closeout"
    val order: Double = 0.0,           // position within the schedule; a double so a row can be dropped between two others
    val start: Long? = null,           // first working day
    val end: Long? = null,             // LAST working day, inclusive
    val durationDays: Int = 0,         // working days from start through end, kept in step with them
    val isMilestone: Boolean = false,  // a one-day marker such as "Roof complete"
    val predecessors: List<PhasePredecessor> = emptyList(),
    val trade: String = "",
    val contactId: String = "",
    val contactName: String = "",
    val status: String = PhaseStatus.NOT_STARTED,
    val percentComplete: Int = 0,      // 0-100
    val percentMode: String = PercentMode.AUTO,
    val baselineStart: Long? = null,   // the original plan - never moved by a shift
    val baselineEnd: Long? = null,
    val notes: String = "",
    val color: String = "",            // optional "#RRGGBB"; blank means "color by status"
    val taskIds: List<String> = emptyList(),
    val appointmentIds: List<String> = emptyList(),
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val updatedBy: String = ""
)

/** "This phase can't start until [phaseId] has finished (FS) / started (SS), plus [lagDays] working days." */
@Serializable
data class PhasePredecessor(
    val phaseId: String = "",
    val type: String = PredecessorType.FS,
    val lagDays: Int = 0               // working days; may be negative (an overlap)
)

/** The four phase statuses, as stored. Wrap in t() for display, like task bucket names. */
object PhaseStatus {
    const val NOT_STARTED = "Not started"
    const val IN_PROGRESS = "In progress"
    const val DONE = "Done"
    const val ON_HOLD = "On hold"
    val all = listOf(NOT_STARTED, IN_PROGRESS, DONE, ON_HOLD)

    /** Tolerates case/spacing differences ("in progress", "Not Started") from hand-entered or imported data. */
    fun normalize(raw: String?): String {
        val key = raw?.trim()?.lowercase()?.split(" ")?.filter { it.isNotEmpty() }?.joinToString(" ") ?: return NOT_STARTED
        return all.firstOrNull { it.lowercase() == key } ?: NOT_STARTED
    }
}

object PredecessorType {
    const val FS = "FS" // finish-to-start (the default)
    const val SS = "SS" // start-to-start
    fun normalize(raw: String?): String = if (raw?.trim()?.uppercase() == SS) SS else FS
}

object PercentMode {
    const val AUTO = "auto"     // worked out from the linked tasks' checklists
    const val MANUAL = "manual" // typed in by hand
    fun normalize(raw: String?): String = if (raw?.trim()?.lowercase() == MANUAL) MANUAL else AUTO
}
