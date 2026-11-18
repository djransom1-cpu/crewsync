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
import com.djransom.crewsync.i18n.I18n
import com.djransom.crewsync.util.ScheduleSampleData
import com.djransom.crewsync.util.TIMELINE_MONTHS_DAY_WIDTH
import com.djransom.crewsync.util.WorkCalendar
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

    private fun shot(name: String, width: Int, height: Int, content: @Composable () -> Unit) {
        I18n.init("en")
        val scene = ImageComposeScene(width = width, height = height, density = Density(1f)) {
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
