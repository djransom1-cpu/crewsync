package com.djransom.crewsync.util

import com.djransom.crewsync.data.model.Appointment
import com.djransom.crewsync.data.model.ChecklistItem
import com.djransom.crewsync.data.model.DailyLog
import com.djransom.crewsync.data.model.PhaseStatus
import com.djransom.crewsync.data.model.SchedulePhase
import com.djransom.crewsync.data.model.Task
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime

// ---- Today: where we are and what's next -------------------------------------------------------
// A one-screen answer for the project, worked out live from the schedule, the task cards, the
// site calendar and the latest daily log. Pure, so it can be unit-tested (see ScheduleSnapshotTest);
// the Today view (TodaySnapshotView.kt) draws it on a phone or across the meeting-room screen.

/** A phase under way, with its percent complete and slip against the plan (null with no baseline). */
data class SnapshotPhase(val phase: SchedulePhase, val percent: Int, val slipDays: Int?)

/** One open checklist item, with the task card it's on. [priority] items come first in the To do list. */
data class SnapshotToDo(val task: Task, val groupId: String, val item: ChecklistItem, val priority: Boolean)

data class TodaySnapshot(
    val date: LocalDate,
    val hasSchedule: Boolean,
    /** Today's weather as the daily log has it; blank when it has none yet (the view then looks it up). */
    val weather: String,
    val inProgress: List<SnapshotPhase>,
    val recentlyFinished: List<SchedulePhase>,
    val plannedFinish: LocalDate?,
    val currentFinish: LocalDate?,
    val finishSlipDays: Int?,
    val behindPhases: List<BehindPhase>,
    val overdueTasks: List<TaskDue>,
    val nextUp: List<SchedulePhase>,
    val onSiteToday: List<AppointmentOccurrence>,
    val onSiteNextWeek: List<AppointmentOccurrence>,
    val toDo: List<SnapshotToDo>,
    /** The most recent daily log with something written in it, today's included. */
    val lastReport: DailyLog?
)

/** How many To do items show before "N more". */
const val SNAPSHOT_TODO_SHOWN = 15
/** "Next up" looks this many working days ahead. */
const val SNAPSHOT_NEXT_UP_WORKING_DAYS = 10
/** "Finished recently" covers phases marked Done in this many days. */
private const val RECENTLY_FINISHED_DAYS = 7

/**
 * Works out the snapshot for [today].
 *  - Where we stand: phases in progress with percent and slip; phases marked Done in the last
 *    week; and the working days of slip at the finish.
 *  - Behind: the look-ahead's rule - phases that should have started or finished, and overdue cards.
 *  - Next up: phases starting in the next 10 working days.
 *  - On site: today's appointments, then the next 7 days' (repeating ones included).
 *  - To do: every open checklist item on cards that aren't done - first those on cards in the
 *    TOP Priority bucket or linked to an active phase, then the rest; cards with the nearest due
 *    date first, items in checklist order.
 *  - From the last daily report: the latest log with something in it.
 * [nowMillis] is "now" for "finished recently" (a phase's updatedAt).
 */
fun buildTodaySnapshot(
    phases: List<SchedulePhase>,
    tasks: List<Task>,
    appointments: List<Appointment>,
    logs: List<DailyLog>,
    calendar: WorkCalendar,
    today: LocalDate,
    nowMillis: Long,
    zone: TimeZone = TimeZone.currentSystemDefault()
): TodaySnapshot {
    val dated = phases.filter { it.start != null && it.end != null }
    val tasksById = tasks.associateBy { it.id }
    val totals = scheduleTotals(phases, calendar, zone)
    val look = buildLookAhead(phases, tasks, appointments, today, weeks = 1, zone = zone)

    val inProgress = phases.inScheduleOrder().filter { it.status == PhaseStatus.IN_PROGRESS }
        .map { SnapshotPhase(it, it.percentShown(it.linkedTasks(tasksById)), it.slipDays(calendar, zone)) }
    val recentlyFinished = phases.inScheduleOrder()
        .filter { it.status == PhaseStatus.DONE && it.updatedAt >= nowMillis - RECENTLY_FINISHED_DAYS * 86_400_000L }

    val horizon = calendar.addWorkingDays(today, SNAPSHOT_NEXT_UP_WORKING_DAYS)
    val nextUp = dated.filter { p -> p.status != PhaseStatus.DONE && p.startDate(zone)!!.let { it > today && it <= horizon } }
        .sortedWith(compareBy<SchedulePhase> { it.start }.thenBy { it.order })

    fun visits(window: LookAheadWindow) = appointments.flatMap { appt ->
        val minutes = if (appt.isAllDay) null else millisToMinutes(appt.startDate, zone)
        appt.occurrenceDaysIn(window, zone).map { AppointmentOccurrence(appt, it, minutes) }
    }.sortedWith(compareBy<AppointmentOccurrence> { it.day }.thenBy { it.minutes ?: -1 }.thenBy { it.appointment.title.lowercase() })
    val onSiteToday = visits(LookAheadWindow(today, today))
    val onSiteNext = visits(LookAheadWindow(today.plus(1, DateTimeUnit.DAY), today.plus(7, DateTimeUnit.DAY)))

    // Active: in progress, or running today and not finished.
    val active = phases.filter { p ->
        p.status == PhaseStatus.IN_PROGRESS ||
            (p.status != PhaseStatus.DONE && p.startDate(zone)?.let { it <= today } == true && p.endDate(zone)?.let { it >= today } == true)
    }
    val activeTaskIds = active.flatMap { it.taskIds }.toSet()
    fun isPriority(t: Task) = squashStatus(t.status) == "top priority" || t.id in activeTaskIds
    val openCards = tasks.filter { !isDoneStatus(it.status) && it.allChecklistItems().any { item -> !item.isDone } }
        .sortedWith(compareBy<Task> { if (isPriority(it)) 0 else 1 }.thenBy { it.dueDate ?: Long.MAX_VALUE }.thenBy { it.title.lowercase() })
    val toDo = openCards.flatMap { task ->
        task.checklistGroups.flatMap { g -> g.items.filter { !it.isDone }.map { SnapshotToDo(task, g.id, it, isPriority(task)) } }
    }

    val todayIso = today.toLogDate()
    val lastReport = logs.filter { it.date <= todayIso && !it.isEmpty() }.maxByOrNull { it.date }
    val weather = logs.firstOrNull { it.date == todayIso }?.weatherLine().orEmpty()

    return TodaySnapshot(
        date = today, hasSchedule = dated.isNotEmpty(), weather = weather,
        inProgress = inProgress, recentlyFinished = recentlyFinished,
        plannedFinish = totals.plannedFinish, currentFinish = totals.currentFinish, finishSlipDays = totals.finishSlipDays,
        behindPhases = look.behindPhases, overdueTasks = look.overdueTasks,
        nextUp = nextUp, onSiteToday = onSiteToday, onSiteNextWeek = onSiteNext,
        toDo = toDo, lastReport = lastReport
    )
}

/** The To do items to show, and how many more there are: the first [SNAPSHOT_TODO_SHOWN] unless [expanded]. */
fun TodaySnapshot.toDoShown(expanded: Boolean, limit: Int = SNAPSHOT_TODO_SHOWN): Pair<List<SnapshotToDo>, Int> =
    if (expanded || toDo.size <= limit) toDo to 0 else toDo.take(limit) to toDo.size - limit

private fun squashStatus(s: String) = s.trim().lowercase().split(" ").filter { it.isNotEmpty() }.joinToString(" ")

private fun millisToMinutes(millis: Long, zone: TimeZone): Int =
    Instant.fromEpochMilliseconds(millis).toLocalDateTime(zone).let { it.hour * 60 + it.minute }
