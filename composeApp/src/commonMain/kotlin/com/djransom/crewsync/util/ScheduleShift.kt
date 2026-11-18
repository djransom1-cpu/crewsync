package com.djransom.crewsync.util

import com.djransom.crewsync.data.model.Appointment
import com.djransom.crewsync.data.model.PhaseStatus
import com.djransom.crewsync.data.model.SchedulePhase
import com.djransom.crewsync.data.model.Task
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

// ---- Shifting a phase and everything that follows it -------------------------------------------
// When a phase's dates change, the phases that wait on it - through their predecessors, however
// long the chain - move too. This file works out WHAT would move; nothing is saved until the
// person has seen the preview and pressed Apply. The connector does the same sums in
// functions/schedule.js (planCascade), and the two are tested against the same five-phase chain.
//
// The rule for a follower: it keeps its place relative to the earliest day its predecessors
// allow. Each predecessor allows a start (the working day after it finishes, or the day it
// starts, plus the lag); the latest of those is the follower's earliest start. If a change moves
// that earliest start by N working days, the follower moves N working days, keeping its length.
//  - A gap the schedule had on purpose stays a gap; phases that overlapped still overlap.
//  - It works both ways, so shifting back by the same amount puts everything back.
//  - A follower held up by some OTHER predecessor that hasn't moved doesn't move.
//  - A phase that's Done doesn't move, and a phase with no dates has nothing to move.
// The baseline is never touched by any of this.

/** One phase that a change would move. [startShift] and [endShift] are in working days; positive is later. */
data class PhaseMove(
    val phase: SchedulePhase,
    val oldStart: LocalDate,
    val oldEnd: LocalDate,
    val newStart: LocalDate,
    val newEnd: LocalDate,
    val startShift: Int,
    val endShift: Int,
    val isOrigin: Boolean,     // the phase the person changed, as opposed to one that follows it
    val newDurationDays: Int = 0 // its length at the new dates, to be stored alongside them
)

/** What a change would do: the phases that move, or the reason it can't be worked out. */
sealed class ShiftPlan {
    /** [moves] has the changed phase first, then its followers in the order they were reached. Empty when nothing would change. */
    data class Moves(val moves: List<PhaseMove>) : ShiftPlan()

    /** The predecessors go round in a circle, so there's no order to move things in. [names] is the circle, first name repeated at the end. */
    data class Loop(val names: List<String>) : ShiftPlan()
}

/** Where a phase would sit if moved [days] working days (negative is earlier), keeping its length. Null if it has no dates. */
fun shiftedDates(phase: SchedulePhase, days: Int, calendar: WorkCalendar, zone: TimeZone = TimeZone.currentSystemDefault()): Pair<LocalDate, LocalDate>? {
    val start = phase.startDate(zone) ?: return null
    val end = phase.endDate(zone) ?: return null
    if (days == 0) return start to end
    val newStart = calendar.addWorkingDays(start, days)
    return newStart to (if (phase.isMilestone) newStart else calendar.endForDuration(newStart, calendar.durationDays(start, end).coerceAtLeast(1)))
}

/**
 * Works out everything that moves when phase [originId] is given the dates [newStart]..[newEnd].
 * With [cascade] off only the phase itself is listed.
 */
fun planCascade(
    phases: List<SchedulePhase>,
    originId: String,
    newStart: LocalDate,
    newEnd: LocalDate,
    calendar: WorkCalendar,
    zone: TimeZone = TimeZone.currentSystemDefault(),
    cascade: Boolean = true
): ShiftPlan {
    val byId = phases.associateBy { it.id }
    val origin = byId[originId] ?: return ShiftPlan.Moves(emptyList())
    val followersOf: Map<String, List<SchedulePhase>> = phases
        .flatMap { p -> p.predecessors.map { it.phaseId to p } }
        .groupBy({ it.first }, { it.second })

    // Followers of the changed phase in an order where each comes after everything it waits on
    // (a depth-first walk, finished phases last-in first-out), stopping dead at a loop.
    val order = ArrayList<SchedulePhase>()
    val state = HashMap<String, Int>() // 1 = on the path being walked, 2 = finished
    val path = ArrayList<SchedulePhase>()
    var loop: List<String>? = null
    fun walk(p: SchedulePhase) {
        if (loop != null) return
        state[p.id] = 1
        path += p
        for (next in followersOf[p.id].orEmpty()) {
            when (state[next.id]) {
                1 -> { loop = (path.dropWhile { it.id != next.id } + next).map { it.name.ifBlank { it.id } }; return }
                2 -> {}
                else -> walk(next)
            }
            if (loop != null) return
        }
        path.removeAt(path.size - 1)
        state[p.id] = 2
        order += p
    }
    if (cascade) walk(origin)
    loop?.let { return ShiftPlan.Loop(it) }
    val followers = order.asReversed().filter { it.id != originId }

    val oldDates = phases.mapNotNull { p -> val s = p.startDate(zone); val e = p.endDate(zone); if (s != null && e != null) p.id to (s to e) else null }.toMap()
    val newDates = HashMap(oldDates)
    newDates[originId] = newStart to newEnd

    /** The earliest day [phase] may start given where its predecessors sit in [dates]. Null if none of them is dated. */
    fun earliestStart(phase: SchedulePhase, dates: Map<String, Pair<LocalDate, LocalDate>>): LocalDate? =
        phase.predecessors.mapNotNull { link -> dates[link.phaseId]?.let { (s, e) -> calendar.successorStart(s, e, link.type, link.lagDays) } }.maxOrNull()

    for (follower in followers) {
        val (start, end) = oldDates[follower.id] ?: continue
        if (follower.status == PhaseStatus.DONE) continue
        val before = earliestStart(follower, oldDates) ?: continue
        val after = earliestStart(follower, newDates) ?: continue
        val shift = calendar.workingDaysBetween(before, after)
        if (shift == 0) continue
        val movedStart = calendar.addWorkingDays(start, shift)
        val movedEnd = if (follower.isMilestone) movedStart else calendar.endForDuration(movedStart, calendar.durationDays(start, end).coerceAtLeast(1))
        newDates[follower.id] = movedStart to movedEnd
    }

    val moves = (listOf(origin) + followers).mapNotNull { p ->
        val (s, e) = newDates[p.id] ?: return@mapNotNull null
        val old = oldDates[p.id]
        if (old == s to e) return@mapNotNull null
        val (os, oe) = old ?: (s to e) // a phase being given its first dates: nothing to measure a shift from
        PhaseMove(
            p, os, oe, s, e, calendar.workingDaysBetween(os, s), calendar.workingDaysBetween(oe, e),
            isOrigin = p.id == originId, newDurationDays = if (e >= s) calendar.durationDays(s, e) else 0
        )
    }
    return ShiftPlan.Moves(moves)
}

// ---- Things linked to a phase that moves -------------------------------------------------------

/** A linked appointment moved along with its phase: the same clock time, on its new day. */
data class AppointmentMove(val appointment: Appointment, val newStart: Long, val newEnd: Long)

/** A linked task card whose dates move along with its phase. Either date may be absent. */
data class TaskMove(val task: Task, val newStart: Long?, val newDue: Long?)

/**
 * The appointments linked to a moving phase that would move with it: by the same number of
 * working days as the phase's START, at the same time of day. Left alone: repeating appointments
 * (moving one would change every occurrence), anything that has already happened, and everything
 * when only the phase's end moved.
 */
fun appointmentMoves(
    move: PhaseMove,
    appointmentsById: Map<String, Appointment>,
    today: LocalDate,
    calendar: WorkCalendar,
    zone: TimeZone = TimeZone.currentSystemDefault()
): List<AppointmentMove> {
    if (move.startShift == 0) return emptyList()
    return move.phase.appointmentIds.mapNotNull { appointmentsById[it] }.mapNotNull { appt ->
        if (appt.recurrence != "None" && appt.recurrence.isNotBlank()) return@mapNotNull null
        val start = Instant.fromEpochMilliseconds(appt.startDate).toLocalDateTime(zone)
        if (start.date < today) return@mapNotNull null
        val day = calendar.addWorkingDays(start.date, move.startShift)
        val newStart = LocalDateTime(day.year, day.monthNumber, day.dayOfMonth, start.hour, start.minute).toInstant(zone).toEpochMilliseconds()
        AppointmentMove(appt, newStart, newStart + (appt.endDate - appt.startDate).coerceAtLeast(0L))
    }
}

/**
 * The task cards linked to a moving phase that would move with it: those with a start or due
 * date that aren't in a Done bucket, each date moved by the same number of working days as the
 * phase's start.
 */
fun taskMoves(move: PhaseMove, tasksById: Map<String, Task>, calendar: WorkCalendar, zone: TimeZone = TimeZone.currentSystemDefault()): List<TaskMove> {
    if (move.startShift == 0) return emptyList()
    fun moved(millis: Long?): Long? = millis?.let { calendar.addWorkingDays(millisToLocalDate(it, zone), move.startShift).toLocalMidnightMillis(zone) }
    return move.phase.taskIds.mapNotNull { tasksById[it] }
        .filter { (it.startDate != null || it.dueDate != null) && !isDoneStatus(it.status) }
        .map { TaskMove(it, moved(it.startDate), moved(it.dueDate)) }
}
