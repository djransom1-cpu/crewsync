package com.djransom.crewsync.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import com.djransom.crewsync.data.model.Appointment
import com.djransom.crewsync.data.model.PhaseStatus
import com.djransom.crewsync.data.model.SchedulePhase
import com.djransom.crewsync.data.model.Task
import com.djransom.crewsync.util.AppointmentMove
import com.djransom.crewsync.util.PhaseLinkKind
import com.djransom.crewsync.util.PhaseLinkOp
import com.djransom.crewsync.util.PhaseMove
import com.djransom.crewsync.util.TaskMove
import com.djransom.crewsync.util.linkOps
import com.djransom.crewsync.util.phaseFieldChanges
import com.djransom.crewsync.util.tidiedForSave
import com.djransom.crewsync.util.toFirestoreMap
import com.djransom.crewsync.util.toLocalMidnightMillis
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.auth.auth
import dev.gitlive.firebase.firestore.CollectionReference
import dev.gitlive.firebase.firestore.FieldValue
import dev.gitlive.firebase.firestore.FirebaseFirestore
import dev.gitlive.firebase.firestore.firestore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.datetime.TimeZone
import kotlin.time.Clock

/**
 * Everything the schedule screens - and the Phase chips on the task and appointment screens - can
 * ask to have saved. One of these is made per project (see [rememberScheduleActions]) and handed
 * down to whoever needs it; a screen given none is read-only.
 *
 * How things are written matters here:
 *  - An existing phase is only ever changed with a TARGETED update of the fields that were
 *    edited, never by saving the whole document, so its links and any field a newer build has
 *    added are left alone.
 *  - Links are stored on the phase (taskIds / appointmentIds) and changed with array add/remove,
 *    so two people linking different things at the same moment don't undo each other. Task and
 *    appointment documents are never touched.
 *  - Nothing is written from inside a click handler. Each request is queued and carried out a
 *    frame later by one long-lived coroutine - see the note above pendingSaveTask in
 *    PlannerScreen for the web crash that writing while a dialog is closing used to cause.
 */
@Stable
class ScheduleActions internal constructor(
    private val firestore: FirebaseFirestore,
    private val projectId: String,
    private val environmentId: String,
    private val phasesNow: () -> List<SchedulePhase>,
    private val firstBucket: () -> String,
    private val enqueue: (suspend () -> Unit) -> Unit
) {
    /** The last thing that couldn't be saved, for the Schedule tab to show. Cleared by [dismissError]. */
    var error by mutableStateOf<String?>(null)
        internal set

    fun dismissError() { error = null }

    private val phases: CollectionReference get() = firestore.collection("projects").document(projectId).collection("schedulePhases")
    private fun now(): Long = Clock.System.now().toEpochMilliseconds()
    private fun who(): String = Firebase.auth.currentUser?.email ?: ""
    private fun stamp(): Array<Pair<String, Any?>> = arrayOf("updatedAt" to now().toDouble(), "updatedBy" to who())

    /**
     * Saves an edited phase. [before] is the phase as it was when the edit started (null for a
     * new one): only what differs from it is written.
     */
    fun savePhase(before: SchedulePhase?, edited: SchedulePhase) {
        val tidy = edited.tidiedForSave()
        if (before == null || before.id.isEmpty()) {
            enqueue {
                val ref = phases.document // a new document with an id of Firestore's choosing
                val time = now()
                val bottom = (phasesNow().maxOfOrNull { it.order } ?: 0.0) + 1.0
                ref.set(
                    tidy.copy(
                        id = ref.id, projectId = projectId, environmentId = environmentId, order = bottom,
                        taskIds = emptyList(), appointmentIds = emptyList(),
                        createdAt = time, updatedAt = time, updatedBy = who()
                    ).toFirestoreMap()
                )
            }
            return
        }
        val changes = phaseFieldChanges(before, tidy)
        if (changes.isEmpty()) return
        enqueue {
            // null means "remove this field": a cleared date, an emptied predecessor list.
            val fields = changes.map { (name, value) -> name to (value ?: FieldValue.delete) }
            phases.document(before.id).update(*fields.toTypedArray(), *stamp())
        }
    }

    /** Marks a phase Done. Only ever called from a button the person pressed. */
    fun markDone(phase: SchedulePhase) = savePhase(phase, phase.copy(status = PhaseStatus.DONE))

    /**
     * Removes a phase. Its linked tasks and appointments are untouched (the links lived on the
     * phase, so they go with it), and it's taken out of the predecessors of anything waiting on it.
     */
    fun deletePhase(phase: SchedulePhase) {
        if (phase.id.isEmpty()) return
        enqueue {
            val batch = firestore.batch()
            batch.delete(phases.document(phase.id))
            phasesNow().filter { other -> other.id != phase.id && other.predecessors.any { it.phaseId == phase.id } }.forEach { other ->
                val remaining = other.predecessors.filter { it.phaseId != phase.id }
                batch.update(phases.document(other.id), "predecessors" to (if (remaining.isEmpty()) FieldValue.delete else remaining), *stamp())
            }
            batch.commit()
        }
    }

    /**
     * Carries out a shift the person has seen in the preview and approved, all in one go: every
     * phase in [moves] gets its new dates, and the ticked appointments and task cards get theirs.
     * When the shift came from the edit screen, [originBefore] and [originEdited] are the changed
     * phase before and after, so its other edited fields are saved with it. Only dates are written
     * to the phases that follow, and the baseline is never touched.
     */
    fun applyShift(
        moves: List<PhaseMove>,
        appointments: List<AppointmentMove>,
        tasks: List<TaskMove>,
        originBefore: SchedulePhase? = null,
        originEdited: SchedulePhase? = null
    ) {
        val originChanges = if (originBefore != null && originEdited != null) phaseFieldChanges(originBefore, originEdited.tidiedForSave()) else null
        if (moves.isEmpty() && originChanges.isNullOrEmpty()) return
        enqueue {
            val zone = TimeZone.currentSystemDefault()
            val batch = firestore.batch()
            if (originBefore != null && !originChanges.isNullOrEmpty()) {
                val fields = originChanges.map { (name, value) -> name to (value ?: FieldValue.delete) }
                batch.update(phases.document(originBefore.id), *fields.toTypedArray(), *stamp())
            }
            moves.filter { originChanges == null || !it.isOrigin }.forEach { move ->
                batch.update(
                    phases.document(move.phase.id),
                    "start" to move.newStart.toLocalMidnightMillis(zone).toDouble(),
                    "end" to move.newEnd.toLocalMidnightMillis(zone).toDouble(),
                    "durationDays" to move.newDurationDays,
                    *stamp()
                )
            }
            // Targeted updates of the dates alone: nothing else on the appointment or the task card is rewritten.
            val calendar = firestore.collection("projects").document(projectId).collection("appointments")
            appointments.forEach { batch.update(calendar.document(it.appointment.id), "startDate" to it.newStart.toDouble(), "endDate" to it.newEnd.toDouble()) }
            tasks.forEach { move ->
                val fields = listOfNotNull(move.newStart?.let { "startDate" to it.toDouble() }, move.newDue?.let { "dueDate" to it.toDouble() })
                if (fields.isNotEmpty()) batch.update(firestore.collection("tasks").document(move.task.id), *fields.toTypedArray())
            }
            batch.commit()
        }
    }

    /** "Set as baseline": the phase's current dates become its plan - what slip is measured against from now on. */
    fun setBaseline(phase: SchedulePhase) {
        if (phase.id.isEmpty()) return
        enqueue {
            phases.document(phase.id).update(
                "baselineStart" to (phase.start?.toDouble() ?: FieldValue.delete),
                "baselineEnd" to (phase.end?.toDouble() ?: FieldValue.delete),
                *stamp()
            )
        }
    }

    /** Puts a task on a phase, or on none (null). It comes off whichever phase it was on. */
    fun setTaskPhase(taskId: String, phaseId: String?) = applyLinks(linkOps(phasesNow(), PhaseLinkKind.Task, taskId, phaseId))

    /** Puts an appointment on a phase, or on none (null). */
    fun setAppointmentPhase(appointmentId: String, phaseId: String?) = applyLinks(linkOps(phasesNow(), PhaseLinkKind.Appointment, appointmentId, phaseId))

    private fun applyLinks(ops: List<PhaseLinkOp>) {
        if (ops.isEmpty()) return
        enqueue { commitLinks(ops) }
    }

    private suspend fun commitLinks(ops: List<PhaseLinkOp>) {
        val batch = firestore.batch()
        ops.forEach { op ->
            val change = if (op.add) FieldValue.arrayUnion(op.itemId) else FieldValue.arrayRemove(op.itemId)
            batch.update(phases.document(op.phaseId), op.kind.field to change, *stamp())
        }
        batch.commit()
    }

    /** "Add task" on a phase: a new task card in the project's first bucket, already linked. */
    fun createLinkedTask(title: String, phaseId: String) {
        val name = title.trim()
        if (name.isEmpty() || phaseId.isEmpty()) return
        enqueue {
            val task = Task(
                environmentId = environmentId, projectId = projectId, title = name,
                status = firstBucket(), order = now() // sorts new cards to the end of their bucket, as the Planner does
            )
            val ref = firestore.collection("tasks").add(task.toFirestoreMap())
            commitLinks(listOf(PhaseLinkOp(phaseId, PhaseLinkKind.Task, ref.id, add = true)))
        }
    }

    /** "Add visit" on a phase: a new site-calendar appointment, already linked. */
    fun createLinkedAppointment(appointment: Appointment, phaseId: String?) {
        enqueue {
            val ref = firestore.collection("projects").document(projectId).collection("appointments").add(appointment.toFirestoreMap())
            if (!phaseId.isNullOrEmpty()) commitLinks(listOf(PhaseLinkOp(phaseId, PhaseLinkKind.Appointment, ref.id, add = true)))
        }
    }
}

/**
 * The project's [ScheduleActions], plus the coroutine that carries its requests out one at a
 * time. Call it once, high up (ProjectDetailsScreen), so the queue outlives any one tab or dialog.
 */
@Composable
fun rememberScheduleActions(projectId: String, environmentId: String, phases: List<SchedulePhase>, buckets: List<String>): ScheduleActions {
    val latestPhases by rememberUpdatedState(phases)
    val latestBuckets by rememberUpdatedState(buckets)
    val queue = remember(projectId) { Channel<suspend () -> Unit>(Channel.UNLIMITED) }
    val actions = remember(projectId, environmentId) {
        ScheduleActions(
            firestore = Firebase.firestore, projectId = projectId, environmentId = environmentId,
            phasesNow = { latestPhases }, firstBucket = { latestBuckets.firstOrNull() ?: "Not Started" },
            enqueue = { job -> queue.trySend(job) }
        )
    }
    LaunchedEffect(queue, actions) {
        for (job in queue) {
            try {
                withFrameNanos { }   // let whatever dialog asked for this finish closing first
                job()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                actions.error = e.message ?: e.toString()
            }
        }
    }
    return actions
}
