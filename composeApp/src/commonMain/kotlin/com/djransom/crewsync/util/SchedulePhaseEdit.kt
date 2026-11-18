package com.djransom.crewsync.util

import com.djransom.crewsync.data.model.PercentMode
import com.djransom.crewsync.data.model.PhasePredecessor
import com.djransom.crewsync.data.model.PhaseStatus
import com.djransom.crewsync.data.model.SchedulePhase
import com.djransom.crewsync.data.model.Task
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone

// ---- Editing a phase ---------------------------------------------------------------------------
// The rules behind the phase edit screen and the Phase chips, kept free of Firestore and UI so
// they can be unit-tested (see SchedulePhaseEditTest). The connector follows the same rules -
// resolvePhaseDates, findLoop and link_to_phase in functions/ - so a phase edited in the app and
// one edited through Claude behave the same way.

/**
 * A phase's dates while they're being edited, as calendar days. The three ways of changing them
 * keep the dates and the working-day count in step:
 *  - a new start MOVES the phase: it keeps its length and the end follows;
 *  - a new end STRETCHES or shrinks it from its start;
 *  - a new length moves the end.
 * A milestone is always a single day.
 */
data class PhaseDatesEdit(
    val start: LocalDate? = null,
    val end: LocalDate? = null,
    val durationDays: Int = 0,
    val isMilestone: Boolean = false
) {
    /** The end is before the start - the one way these dates can be wrong. */
    val endsBeforeStart: Boolean get() = start != null && end != null && end < start

    fun withStart(newStart: LocalDate, calendar: WorkCalendar): PhaseDatesEdit {
        if (isMilestone) return copy(start = newStart, end = newStart).counted(calendar)
        val length = when {
            start != null && end != null && end >= start -> calendar.durationDays(start, end)
            else -> durationDays
        }
        val newEnd = if (length >= 1) calendar.endForDuration(newStart, length) else end
        return copy(start = newStart, end = newEnd).counted(calendar)
    }

    fun withEnd(newEnd: LocalDate, calendar: WorkCalendar): PhaseDatesEdit =
        (if (isMilestone) copy(start = newEnd, end = newEnd) else copy(end = newEnd)).counted(calendar)

    fun withDuration(days: Int, calendar: WorkCalendar): PhaseDatesEdit {
        val length = days.coerceAtLeast(1)
        if (isMilestone) return this
        return if (start != null) copy(end = calendar.endForDuration(start, length)).counted(calendar) else copy(durationDays = length)
    }

    fun withMilestone(on: Boolean, calendar: WorkCalendar): PhaseDatesEdit {
        if (!on) return copy(isMilestone = false)
        val day = start ?: end
        return copy(isMilestone = true, start = day, end = day).counted(calendar)
    }

    /** Back to "no dates yet". A length typed in beforehand is kept. */
    fun cleared(): PhaseDatesEdit = copy(start = null, end = null)

    private fun counted(calendar: WorkCalendar): PhaseDatesEdit =
        if (start != null && end != null) copy(durationDays = if (end >= start) calendar.durationDays(start, end) else 0) else this
}

/**
 * A phase as dates to edit. Given the project calendar, the working-day count is taken from the
 * dates themselves rather than from what was stored - the stored number goes stale when the
 * holidays change or the dates were set somewhere that did not recount it.
 */
fun SchedulePhase.datesForEdit(zone: TimeZone = TimeZone.currentSystemDefault(), calendar: WorkCalendar? = null): PhaseDatesEdit {
    val start = startDate(zone)
    val end = endDate(zone)
    val counted = if (calendar != null && start != null && end != null && end >= start) calendar.durationDays(start, end) else durationDays
    return PhaseDatesEdit(start, end, counted, isMilestone)
}

fun SchedulePhase.withDates(dates: PhaseDatesEdit, zone: TimeZone = TimeZone.currentSystemDefault()): SchedulePhase = copy(
    start = dates.start?.toLocalMidnightMillis(zone),
    end = dates.end?.toLocalMidnightMillis(zone),
    durationDays = dates.durationDays,
    isMilestone = dates.isMilestone
)

// ---- Predecessors ------------------------------------------------------------------------------

/**
 * The phases that can't be picked as a predecessor of [phaseId]: the phase itself, and every
 * phase that already waits on it, directly or down a chain. Picking one of those would make a
 * loop (A waits for B, B waits for A), which no schedule can satisfy.
 */
fun blockedPredecessorIds(phases: List<SchedulePhase>, phaseId: String): Set<String> {
    val blocked = mutableSetOf(phaseId)
    var grew = true
    while (grew) {
        grew = false
        for (phase in phases) {
            if (phase.id !in blocked && phase.predecessors.any { it.phaseId in blocked }) {
                blocked += phase.id
                grew = true
            }
        }
    }
    return blocked
}

// ---- Links -------------------------------------------------------------------------------------

/** Which of a phase's two link lists a change is for. The names are the Firestore field names. */
enum class PhaseLinkKind(val field: String) { Task("taskIds"), Appointment("appointmentIds") }

/** One change to one phase's link list: add [itemId] to it, or take it out. */
data class PhaseLinkOp(val phaseId: String, val kind: PhaseLinkKind, val itemId: String, val add: Boolean)

private fun SchedulePhase.links(kind: PhaseLinkKind): List<String> = if (kind == PhaseLinkKind.Task) taskIds else appointmentIds

/** The phase a task is on, if any. (The link is stored on the phase - see SchedulePhase.) */
fun phaseOfTask(phases: List<SchedulePhase>, taskId: String): SchedulePhase? = phases.firstOrNull { taskId in it.taskIds }

/** The phase an appointment is on, if any. */
fun phaseOfAppointment(phases: List<SchedulePhase>, appointmentId: String): SchedulePhase? = phases.firstOrNull { appointmentId in it.appointmentIds }

/**
 * What has to change to put a task or appointment on [toPhaseId] (or on no phase, when null).
 * It can be on only one phase, so it comes off any other phase that has it; nothing is listed for
 * a phase that's already right. An empty list means there's nothing to do.
 */
fun linkOps(phases: List<SchedulePhase>, kind: PhaseLinkKind, itemId: String, toPhaseId: String?): List<PhaseLinkOp> {
    val removals = phases.filter { it.id != toPhaseId && itemId in it.links(kind) }.map { PhaseLinkOp(it.id, kind, itemId, add = false) }
    val target = phases.firstOrNull { it.id == toPhaseId }
    val addition = if (target != null && itemId !in target.links(kind)) listOf(PhaseLinkOp(target.id, kind, itemId, add = true)) else emptyList()
    return removals + addition
}

// ---- "Mark this phase done?" -------------------------------------------------------------------

/**
 * The phases worth asking about: not marked Done yet, with at least one linked task, and every
 * linked task sitting in a Done-like bucket. The app only ever ASKS - it never marks a phase
 * done by itself. Links to tasks that have since been deleted don't count either way.
 */
fun phasesReadyToMarkDone(phases: List<SchedulePhase>, tasksById: Map<String, Task>): List<SchedulePhase> =
    phases.inScheduleOrder().filter { phase ->
        val linked = phase.linkedTasks(tasksById)
        phase.status != PhaseStatus.DONE && linked.isNotEmpty() && linked.all { isDoneStatus(it.status) }
    }

// ---- Saving ------------------------------------------------------------------------------------

/**
 * The fields that differ between a phase as it was and as edited, in the form they're written to
 * Firestore: dates as Doubles like every other date in the app, and null for a field that should
 * be REMOVED (a cleared date, a blank colour, an emptied predecessor list - an empty list is
 * never written, see toFirestoreMap). Only these fields are sent, as a targeted update, so
 * anything else on the document - its links, fields a newer build added - is left alone.
 */
fun phaseFieldChanges(before: SchedulePhase, after: SchedulePhase): Map<String, Any?> {
    val changes = LinkedHashMap<String, Any?>()
    fun <T> field(name: String, old: T, new: T, stored: (T) -> Any? = { it }) { if (old != new) changes[name] = stored(new) }
    field("name", before.name, after.name)
    field("group", before.group, after.group)
    field("order", before.order, after.order)
    field("start", before.start, after.start) { it?.toDouble() }
    field("end", before.end, after.end) { it?.toDouble() }
    field("durationDays", before.durationDays, after.durationDays)
    field("isMilestone", before.isMilestone, after.isMilestone)
    field("predecessors", before.predecessors, after.predecessors) { list -> list.ifEmpty { null } }
    field("trade", before.trade, after.trade)
    field("contactId", before.contactId, after.contactId)
    field("contactName", before.contactName, after.contactName)
    field("status", before.status, after.status)
    field("percentComplete", before.percentComplete, after.percentComplete)
    field("percentMode", before.percentMode, after.percentMode)
    field("notes", before.notes, after.notes)
    field("color", before.color, after.color) { it.ifBlank { null } }
    return changes
}

/** Tidies what was typed before it's saved: trimmed text, a sensible percent, no predecessor on itself or listed twice. */
fun SchedulePhase.tidiedForSave(): SchedulePhase = copy(
    name = name.trim(),
    group = group.trim(),
    trade = trade.trim(),
    contactName = contactName.trim(),
    notes = notes.trim(),
    status = PhaseStatus.normalize(status),
    percentMode = PercentMode.normalize(percentMode),
    percentComplete = percentComplete.coerceIn(0, 100),
    durationDays = durationDays.coerceAtLeast(0),
    predecessors = predecessors.filter { it.phaseId.isNotBlank() && it.phaseId != id }.distinctBy { it.phaseId }
        .map { PhasePredecessor(it.phaseId, com.djransom.crewsync.data.model.PredecessorType.normalize(it.type), it.lagDays) }
)
