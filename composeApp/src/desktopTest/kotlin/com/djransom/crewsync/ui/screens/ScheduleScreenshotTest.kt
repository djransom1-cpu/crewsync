package com.djransom.crewsync.ui.screens

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.djransom.crewsync.data.model.Appointment
import com.djransom.crewsync.data.model.Contact
import com.djransom.crewsync.data.model.PhasePredecessor
import com.djransom.crewsync.data.model.PredecessorType
import com.djransom.crewsync.data.model.SchedulePhase
import com.djransom.crewsync.data.model.Task
import com.djransom.crewsync.i18n.I18n
import com.djransom.crewsync.util.ScheduleSampleData
import com.djransom.crewsync.util.TIMELINE_MONTHS_DAY_WIDTH
import com.djransom.crewsync.util.ShiftPlan
import com.djransom.crewsync.util.WorkCalendar
import com.djransom.crewsync.util.planCascade
import com.djransom.crewsync.util.shiftedDates
import com.djransom.crewsync.util.toLocalMidnightMillis
import kotlinx.datetime.DayOfWeek
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Draws the Schedule tab off-screen with the Martin-shaped sample data and saves each picture as
 * a PNG in composeApp/build/schedule-screenshots/, so the layout can be looked at without signing
 * in or touching real data. "Today" is pinned to Nov 18, 2026 so the pictures don't change from
 * day to day. Run with: gradlew :composeApp:desktopTest
 *
 * These are pictures to look at, not pixel comparisons: each test only fails if the screen can't
 * be drawn at all.
 */
class ScheduleScreenshotTest {
    private val data = ScheduleSampleData
    private val thanksgiving = WorkCalendar(holidays = setOf(data.day(2026, 11, 26), data.day(2026, 11, 27)))

    @Composable
    private fun Schedule(view: ScheduleView, timeline: ScheduleTimelineState = rememberScheduleTimelineState()) {
        ScheduleContent(
            phases = data.martinInProgress, tasks = data.roofTasks, appointments = data.roofAppointments, calendar = thanksgiving,
            zone = data.zone, today = data.today, initialView = view, timelineState = timeline
        )
    }

    // ---- the list ----

    @Test
    fun wideList() = shot("schedule-list-wide", width = 1280, height = 900) { Schedule(ScheduleView.List) }

    @Test
    fun phoneList() = shot("schedule-list-phone", width = 400, height = 1500) { Schedule(ScheduleView.List) }

    @Test
    fun noScheduleYet() = shot("schedule-list-empty", width = 400, height = 500) {
        ScheduleContent(emptyList(), emptyList(), emptyList(), WorkCalendar())
    }

    // ---- the timeline ----

    /** Opens the way the tab does: week columns, starting a week before today. */
    @Test
    fun timelineByWeeks() = shot("schedule-timeline-weeks", width = 1280, height = 800) { Schedule(ScheduleView.Timeline) }

    /** The same chart scrolled back to the start of the job, where the roof's slip against its baseline shows. */
    @Test
    fun timelineFromTheStart() {
        val timeline = ScheduleTimelineState().apply { positioned = true }
        shot("schedule-timeline-start", width = 1280, height = 800) { Schedule(ScheduleView.Timeline, timeline) }
    }

    /** Month zoom: the whole job on one screen. */
    @Test
    fun timelineByMonths() {
        val timeline = ScheduleTimelineState().apply { positioned = true; zoom(TIMELINE_MONTHS_DAY_WIDTH) }
        shot("schedule-timeline-months", width = 1280, height = 800) { Schedule(ScheduleView.Timeline, timeline) }
    }

    /** After pressing Fit: today through the last phase fills the width. */
    @Test
    fun timelineFitted() {
        val timeline = ScheduleTimelineState().apply { fit() }
        shot("schedule-timeline-fit", width = 1280, height = 800) { Schedule(ScheduleView.Timeline, timeline) }
    }

    @Test
    fun timelineOnAPhone() = shot("schedule-timeline-phone", width = 400, height = 860) { Schedule(ScheduleView.Timeline) }

    /** A full-HD window, the size of the meeting-room screen. */
    @Test
    fun timelineFullHd() {
        val timeline = ScheduleTimelineState().apply { fit() }
        shot("schedule-timeline-1920", width = 1920, height = 1080) { Schedule(ScheduleView.Timeline, timeline) }
    }

    // ---- the card that opens when a phase is tapped ----

    @Test
    fun phaseCard() = shot("schedule-phase-card", width = 420, height = 520) {
        val roof = data.martinInProgress.first { it.name == "Roof complete" }
        PhaseCardBody(roof, thanksgiving, data.roofTasks, data.roofAppointments, data.today, data.zone, Modifier.padding(24.dp))
    }

    // ---- presentation mode (the meeting-room screen) ----

    @Composable
    private fun Presentation(phases: List<SchedulePhase> = data.martinInProgress, timeline: ScheduleTimelineState? = null) {
        if (timeline == null) {
            SchedulePresentationContent("Martin", phases, data.roofTasks, data.roofAppointments, thanksgiving, onClose = {}, zone = data.zone, today = data.today)
        } else {
            SchedulePresentationContent("Martin", phases, data.roofTasks, data.roofAppointments, thanksgiving, onClose = {}, zone = data.zone, today = data.today, timelineState = timeline)
        }
    }

    /** What the room sees when Present is pressed: full HD, two weeks back to twelve weeks ahead. */
    @Test
    fun presentationFullHd() = shot("schedule-presentation-1920", width = 1920, height = 1080) { Presentation() }

    /** The same with the finish two working weeks late, so the slip figure in the header has something to say. */
    @Test
    fun presentationWithALateFinish() {
        val late = data.martinInProgress.map {
            if (it.name == "Finish MEP") it.copy(start = data.day(2027, 3, 29).toLocalMidnightMillis(data.zone), end = data.day(2027, 4, 19).toLocalMidnightMillis(data.zone)) else it
        }
        val timeline = ScheduleTimelineState().apply { fit() }
        shot("schedule-presentation-1920-late", width = 1920, height = 1080) { Presentation(late, timeline) }
    }

    /** A laptop screen at 150% Windows scaling: the same pixels, everything half as big again. */
    @Test
    fun presentationScaledLaptop() = shot("schedule-presentation-laptop-150", width = 1920, height = 1080, density = 1.5f) { Presentation() }

    /** A phone turned on its side. */
    @Test
    fun presentationOnAPhone() = shot("schedule-presentation-phone", width = 800, height = 360) { Presentation() }

    /** The tap card at the size presentation mode shows it. */
    @Test
    fun presentationPhaseCard() = shot("schedule-presentation-card", width = 672, height = 800, density = PHASE_CARD_LARGE_SCALE) {
        val roof = data.martinInProgress.first { it.name == "Roof complete" }
        PhaseCardBody(roof, thanksgiving, data.roofTasks, data.roofAppointments, data.today, data.zone, Modifier.padding(24.dp))
    }

    // ---- editing a phase ----

    private val roofer = Contact(id = "cRoofer", name = "JR Roofing", jobTitle = "Roofing", phone = "231-555-0142")

    /** The edit screen for a phase that exists: every field, its predecessor, and its linked tasks and visits. */
    @Test
    fun phaseEditForm() = shot("schedule-phase-edit", width = 480, height = 1500) {
        val exterior = data.martinInProgress.first { it.name == "Exterior walls at chimney" }
        val roof = data.martinInProgress.first { it.name == "Roof complete" }
            .copy(contactId = roofer.id, predecessors = listOf(PhasePredecessor(exterior.id, PredecessorType.FS, -1)), notes = "Ridge vent on order. Flashing at the chimney still to do.")
        PhaseEditForm(
            draft = roof, onDraft = {}, live = roof, allPhases = data.martinInProgress, tasks = data.roofTasks,
            appointments = data.roofAppointments, contacts = listOf(roofer), calendar = thanksgiving, today = data.today, zone = data.zone,
            onUnlinkTask = {}, onAddTask = {}, onUnlinkAppointment = {}, onAddVisit = {}, onDelete = {},
            modifier = Modifier.padding(24.dp)
        )
    }

    /** The same screen for a brand-new phase: nothing filled in, and links wait until it's saved. */
    @Test
    fun newPhaseForm() = shot("schedule-phase-new", width = 480, height = 1000) {
        PhaseEditForm(
            draft = SchedulePhase(), onDraft = {}, live = null, allPhases = data.martinInProgress, tasks = data.roofTasks,
            appointments = data.roofAppointments, contacts = listOf(roofer), calendar = thanksgiving, today = data.today, zone = data.zone,
            onUnlinkTask = {}, onAddTask = {}, onUnlinkAppointment = {}, onAddVisit = {}, onDelete = null,
            modifier = Modifier.padding(24.dp)
        )
    }

    /** The card with a contact that has a phone number: the number shows, with a Call button. */
    @Test
    fun phaseCardWithCall() = shot("schedule-phase-card-call", width = 420, height = 540) {
        val roof = data.martinInProgress.first { it.name == "Roof complete" }.copy(contactId = roofer.id)
        PhaseCardBody(roof, thanksgiving, data.roofTasks, data.roofAppointments, data.today, data.zone, Modifier.padding(24.dp), contacts = listOf(roofer))
    }

    /** The Phase chip that appears on the task and appointment screens. */
    @Test
    fun phaseChip() = shot("schedule-phase-chip", width = 360, height = 120) {
        val roof = data.martinInProgress.first { it.name == "Roof complete" }
        PhaseChip(phases = data.martinInProgress, selectedPhaseId = roof.id, onSelect = {}, modifier = Modifier.padding(24.dp))
    }

    // ---- shifting ----

    /**
     * The preview shown before a shift is saved: the roof slips three working days, the siding and
     * the mechanical rough-in that wait on it follow, and the roof's linked visit and dated task
     * are offered to go along (the visit ticked, the task not).
     */
    @Test
    fun shiftPreview() = shot("schedule-shift-preview", width = 460, height = 560) {
        val roofId = data.martinInProgress.first { it.name == "Roof complete" }.id
        val dated = Task(id = "tDated", projectId = "pM", title = "Flashing at chimney", status = "In Progress", dueDate = data.day(2026, 12, 9).toLocalMidnightMillis(data.zone))
        val phases = data.martinInProgress.map { p ->
            when (p.name) {
                "Roof complete" -> p.copy(taskIds = p.taskIds + dated.id)
                "Siding" -> p.copy(predecessors = listOf(PhasePredecessor(roofId, PredecessorType.SS, 0)))
                "Mechanical & plumbing" -> p.copy(predecessors = listOf(PhasePredecessor(roofId, PredecessorType.SS, 18)))
                else -> p
            }
        }
        val roof = phases.first { it.id == roofId }
        val (start, end) = shiftedDates(roof, 3, thanksgiving, data.zone)!!
        val moves = (planCascade(phases, roofId, start, end, thanksgiving, data.zone) as ShiftPlan.Moves).moves
        ShiftPreviewBody(
            moves, data.roofTasks + dated, data.roofAppointments, thanksgiving, data.today, data.zone,
            appointmentsTicked = { true }, onAppointmentsTicked = { _, _ -> }, tasksTicked = { false }, onTasksTicked = { _, _ -> },
            modifier = Modifier.padding(24.dp)
        )
    }

    // ---- the look-ahead ----

    /** The sample schedule with a few trades, two dated task cards and an all-day delivery, so the look-ahead has something in every part. */
    @Composable
    private fun LookAhead(weeks: Int, byTrade: Boolean) {
        val ms = { m: Int, d: Int -> data.day(2026, m, d).toLocalMidnightMillis(data.zone) }
        val phases = data.martinInProgress.map {
            when (it.name) {
                "Roof complete" -> it.copy(taskIds = it.taskIds + "tFlash")
                "Mechanical & plumbing" -> it.copy(trade = "Mechanical")
                "Electrical rough-in" -> it.copy(trade = "Electrical")
                "Insulation" -> it.copy(trade = "Insulation")
                else -> it
            }
        }
        val tasks = data.roofTasks + listOf(
            Task(id = "tPermit", title = "Septic permit", status = "In Progress", dueDate = ms(11, 13)),
            Task(id = "tFlash", title = "Flashing at chimney", status = "Not Started", dueDate = ms(11, 20))
        )
        val appointments = data.roofAppointments + Appointment(id = "aDelivery", title = "Insulation delivery", isAllDay = true, startDate = ms(11, 19), endDate = ms(11, 19))
        ScheduleContent(
            phases, tasks, appointments, thanksgiving, zone = data.zone, today = data.today,
            initialView = ScheduleView.LookAhead, initialLookWeeks = weeks, initialLookByTrade = byTrade
        )
    }

    @Test
    fun lookAheadByDay() = shot("schedule-lookahead-day", width = 900, height = 1000) { LookAhead(weeks = 2, byTrade = false) }

    @Test
    fun lookAheadByTrade() = shot("schedule-lookahead-trade", width = 900, height = 1000) { LookAhead(weeks = 2, byTrade = true) }

    @Test
    fun lookAheadOnAPhone() = shot("schedule-lookahead-phone", width = 400, height = 1100) { LookAhead(weeks = 1, byTrade = false) }

    // ---- phases on the site calendar ----

    /** November 2026 with the sample schedule drawn as banners, the way the Calendar tab shows it on a laptop. */
    @Test
    fun calendarMonthWithPhases() = shot("calendar-month-phases", width = 1100, height = 900) {
        MonthView(data.day(2026, 11, 1), data.today, data.roofTasks, data.roofAppointments, DayOfWeek.SUNDAY, phases = data.martinInProgress) {}
    }

    /** The same month on a phone, where the cells are narrow. */
    @Test
    fun calendarMonthWithPhasesOnAPhone() = shot("calendar-month-phases-phone", width = 400, height = 900) {
        MonthView(data.day(2026, 11, 1), data.today, data.roofTasks, data.roofAppointments, DayOfWeek.SUNDAY, phases = data.martinInProgress) {}
    }

    /** The week of Nov 15: banners across the days each phase runs, above the hours. */
    @Test
    fun calendarWeekWithPhases() = shot("calendar-week-phases", width = 900, height = 520) {
        WeekView(data.today, data.roofTasks, data.roofAppointments, DayOfWeek.SUNDAY, phases = data.martinInProgress) {}
    }

    /** Hidden: the same month with no phases passed, exactly as the calendar looked before. */
    @Test
    fun calendarMonthWithPhasesHidden() = shot("calendar-month-no-phases", width = 1100, height = 760) {
        MonthView(data.day(2026, 11, 1), data.today, data.roofTasks, data.roofAppointments, DayOfWeek.SUNDAY, phases = emptyList()) {}
    }

    private fun shot(name: String, width: Int, height: Int, density: Float = 1f, content: @Composable () -> Unit) {
        I18n.init("en")
        val scene = ImageComposeScene(width = width, height = height, density = Density(density)) {
            MaterialTheme { Surface(modifier = Modifier.fillMaxSize()) { content() } }
        }
        try {
            // A few frames: the timeline lays itself out, then scrolls to where it should start.
            var image = scene.render()
            for (frame in 1..5) image = scene.render(frame * 16_000_000L)
            val png = image.encodeToData(EncodedImageFormat.PNG)?.bytes ?: error("could not encode $name")
            val dir = File("build/schedule-screenshots").apply { mkdirs() }
            File(dir, "$name.png").writeBytes(png)
            assertTrue(png.size > 2_000, "$name came out blank")
        } finally {
            scene.close()
        }
    }
}
