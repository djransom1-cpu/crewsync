package com.djransom.crewsync.ui.screens

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import com.djransom.crewsync.i18n.I18n
import com.djransom.crewsync.util.ScheduleSampleData
import com.djransom.crewsync.util.WorkCalendar
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.measureTime

/**
 * A rough check that the timeline stays cheap to scroll: 24 phases over 31 weeks, drawn at
 * full-HD size and scrolled sideways and down for 120 frames. Run with: gradlew :composeApp:desktopTest
 *
 * This draws in software with no graphics card, which is far slower than a real screen, so the
 * limit is deliberately loose. It's here to catch the chart suddenly getting several times more
 * expensive, not to promise a frame rate.
 */
class ScheduleTimelineScrollTest {
    @Test
    fun scrollingTwoDozenPhasesStaysCheap() {
        I18n.init("en")
        val data = ScheduleSampleData
        // The 15 sample phases plus 9 more, to get past the "about 20 phases" the build plan asks for.
        val phases = data.martinInProgress + data.martinPlan.take(9).mapIndexed { i, p ->
            p.copy(id = "extra$i", name = p.name + " (2)", group = "Second building", order = 100.0 + i)
        }
        assertEquals(24, phases.size)
        val timeline = ScheduleTimelineState().apply { positioned = true }
        val scene = ImageComposeScene(width = 1920, height = 1080, density = Density(1f)) {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    ScheduleContent(phases, data.roofTasks, data.roofAppointments, WorkCalendar(), zone = data.zone, today = data.today, timelineState = timeline)
                }
            }
        }
        try {
            var nanos = 0L
            repeat(5) { scene.render(nanos); nanos += 16_000_000L }        // settle
            assertTrue(timeline.horizontal.maxValue > 0, "the chart should be wider than the window")
            val frames = 120
            val took = measureTime {
                repeat(frames) { frame ->
                    timeline.horizontal.dispatchRawDelta(if (frame < 60) 24f else -24f)
                    timeline.vertical.dispatchRawDelta(if (frame < 60) 4f else -4f)
                    scene.render(nanos)
                    nanos += 16_000_000L
                }
            }
            val perFrame = took.inWholeMicroseconds / 1000.0 / frames
            println("Schedule timeline: $frames scrolled frames at 1920x1080 in software, ${"%.1f".format(perFrame)} ms each")
            assertTrue(perFrame < 150.0, "scrolling the timeline took ${"%.1f".format(perFrame)} ms a frame")
        } finally {
            scene.close()
        }
    }
}
