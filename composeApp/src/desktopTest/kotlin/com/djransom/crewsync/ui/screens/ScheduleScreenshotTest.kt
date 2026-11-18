package com.djransom.crewsync.ui.screens

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import com.djransom.crewsync.i18n.I18n
import com.djransom.crewsync.util.ScheduleSampleData
import com.djransom.crewsync.util.WorkCalendar
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Draws the Schedule tab off-screen with the Martin-shaped sample data and saves each picture as
 * a PNG in composeApp/build/schedule-screenshots/, so the layout can be looked at without signing
 * in or touching real data. Run with: gradlew :composeApp:desktopTest
 *
 * These are pictures to look at, not pixel comparisons: each test only fails if the screen can't
 * be drawn at all.
 */
class ScheduleScreenshotTest {
    private val thanksgiving = WorkCalendar(
        holidays = setOf(ScheduleSampleData.day(2026, 11, 26), ScheduleSampleData.day(2026, 11, 27))
    )

    @Test
    fun wideList() = shot("schedule-list-wide", width = 1280, height = 900) {
        ScheduleList(ScheduleSampleData.martinInProgress, ScheduleSampleData.roofTasks, thanksgiving, zone = ScheduleSampleData.zone)
    }

    @Test
    fun phoneList() = shot("schedule-list-phone", width = 400, height = 1500) {
        ScheduleList(ScheduleSampleData.martinInProgress, ScheduleSampleData.roofTasks, thanksgiving, zone = ScheduleSampleData.zone)
    }

    @Test
    fun noScheduleYet() = shot("schedule-list-empty", width = 400, height = 500) {
        ScheduleList(emptyList(), emptyList(), WorkCalendar())
    }

    private fun shot(name: String, width: Int, height: Int, content: @Composable () -> Unit) {
        I18n.init("en")
        val scene = ImageComposeScene(width = width, height = height, density = Density(1f)) {
            MaterialTheme { Surface(modifier = Modifier.fillMaxSize()) { content() } }
        }
        try {
            scene.render()                 // first pass lays the list out
            val image = scene.render(16_000_000L)
            val png = image.encodeToData(EncodedImageFormat.PNG)?.bytes ?: error("could not encode $name")
            val dir = File("build/schedule-screenshots").apply { mkdirs() }
            File(dir, "$name.png").writeBytes(png)
            assertTrue(png.size > 2_000, "$name came out blank")
        } finally {
            scene.close()
        }
    }
}
