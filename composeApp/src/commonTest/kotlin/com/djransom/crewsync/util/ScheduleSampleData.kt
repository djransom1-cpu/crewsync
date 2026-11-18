package com.djransom.crewsync.util

import com.djransom.crewsync.data.model.ChecklistGroup
import com.djransom.crewsync.data.model.ChecklistItem
import com.djransom.crewsync.data.model.PercentMode
import com.djransom.crewsync.data.model.PhaseStatus
import com.djransom.crewsync.data.model.SchedulePhase
import com.djransom.crewsync.data.model.Task
import com.djransom.crewsync.data.model.Appointment
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant

/**
 * Martin-shaped sample data for schedule tests and screenshots: the Martin schedule as planned on
 * Sep 21, 2026. Nothing here touches Firestore.
 */
object ScheduleSampleData {
    val zone: TimeZone = TimeZone.of("America/New_York")

    fun day(year: Int, month: Int, day: Int): LocalDate = LocalDate(year, month, day)

    private fun phase(n: Int, group: String, name: String, start: LocalDate, end: LocalDate, milestone: Boolean = false): SchedulePhase {
        val s = start.toLocalMidnightMillis(zone)
        val e = end.toLocalMidnightMillis(zone)
        return SchedulePhase(
            id = "ph$n", projectId = "pM", environmentId = "env1",
            name = name, group = group, order = n.toDouble(),
            start = s, end = e, durationDays = WorkCalendar().durationDays(start, end), isMilestone = milestone,
            baselineStart = s, baselineEnd = e
        )
    }

    /** The plan exactly as written: current dates and baseline are the same, nothing started. */
    val martinPlan: List<SchedulePhase> = listOf(
        phase(1, "Exterior / Site", "Exterior walls at chimney", day(2026, 9, 25), day(2026, 9, 25), milestone = true),
        phase(2, "Exterior / Site", "Roof complete", day(2026, 9, 21), day(2026, 10, 15)),
        phase(3, "Exterior / Site", "Siding", day(2026, 9, 21), day(2026, 11, 15)),
        phase(4, "Exterior / Site", "Septic & grading", day(2026, 10, 15), day(2026, 11, 15)),
        phase(5, "Exterior / Site", "Remaining concrete", day(2026, 11, 1), day(2026, 11, 15)),
        phase(6, "Rough trades", "Electrical service", day(2026, 10, 5), day(2026, 10, 9)),
        phase(7, "Rough trades", "Mechanical & plumbing", day(2026, 10, 15), day(2026, 11, 19)),
        phase(8, "Rough trades", "Electrical rough-in", day(2026, 10, 29), day(2026, 11, 19)),
        phase(9, "Interior", "Insulation", day(2026, 11, 19), day(2026, 12, 4)),
        phase(10, "Interior", "Drywall", day(2026, 12, 4), day(2027, 1, 4)),
        phase(11, "Interior", "Tile & flooring", day(2027, 1, 4), day(2027, 1, 23)),
        phase(12, "Interior", "Painting", day(2027, 1, 9), day(2027, 3, 20)),
        phase(13, "Interior", "Trim & cabinets", day(2027, 1, 23), day(2027, 3, 5)),
        phase(14, "Closeout", "Finish MEP", day(2027, 3, 15), day(2027, 4, 5))
    )

    /** Two tasks for the roof: one with a checklist a quarter done, one with no checklist that is finished. */
    val roofTasks: List<Task> = listOf(
        Task(
            id = "tRoof", projectId = "pM", title = "Roof tear-off", status = "In Progress",
            checklistGroups = listOf(ChecklistGroup(id = "g1", title = "Steps", items = listOf(
                ChecklistItem("i1", "Strip", isDone = true), ChecklistItem("i2", "Deck repair"),
                ChecklistItem("i3", "Dry in"), ChecklistItem("i4", "Shingle")
            )))
        ),
        Task(id = "tRidge", projectId = "pM", title = "Order ridge vent", status = "Completed")
    )

    /** "Today" for anything drawn from this data: a Wednesday in the middle of the job. */
    val today: LocalDate = day(2026, 11, 18)

    private fun at(date: LocalDate, hour: Int, minute: Int = 0): Long =
        LocalDateTime(date.year, date.monthNumber, date.dayOfMonth, hour, minute).toInstant(zone).toEpochMilliseconds()

    /** Site-calendar appointments linked to the roof: the standing Tuesday meeting and a one-off visit this Friday. */
    val roofAppointments: List<Appointment> = listOf(
        Appointment(id = "aWeekly", projectId = "pM", title = "Weekly Meeting", startDate = at(day(2026, 10, 13), 9), endDate = at(day(2026, 10, 13), 10), recurrence = "Weekly"),
        Appointment(id = "aRoofer", projectId = "pM", title = "Roofer on site - ridge and flashing", startDate = at(day(2026, 11, 20), 7, 30), endDate = at(day(2026, 11, 20), 15))
    )

    /**
     * The same schedule the way it might look in mid-November: early phases done, the roof running
     * late (its end moved to Dec 11 while the baseline stays at Oct 15, the slip case from the
     * build plan), a typed percent on the siding, one phase on hold, and one phase with no dates.
     */
    val martinInProgress: List<SchedulePhase> = martinPlan.map { p ->
        when (p.name) {
            "Exterior walls at chimney", "Electrical service" -> p.copy(status = PhaseStatus.DONE)
            "Roof complete" -> p.copy(
                status = PhaseStatus.IN_PROGRESS, end = day(2026, 12, 11).toLocalMidnightMillis(zone),
                taskIds = listOf("tRoof", "tRidge"), appointmentIds = listOf("aWeekly", "aRoofer"),
                trade = "Roofing", contactName = "JR Roofing"
            )
            "Siding" -> p.copy(status = PhaseStatus.IN_PROGRESS, percentMode = PercentMode.MANUAL, percentComplete = 60)
            "Septic & grading" -> p.copy(status = PhaseStatus.ON_HOLD)
            "Mechanical & plumbing" -> p.copy(status = PhaseStatus.IN_PROGRESS, percentMode = PercentMode.MANUAL, percentComplete = 85)
            else -> p
        }
    } + SchedulePhase(id = "ph15", projectId = "pM", name = "Punch list", group = "Closeout", order = 15.0)
}
