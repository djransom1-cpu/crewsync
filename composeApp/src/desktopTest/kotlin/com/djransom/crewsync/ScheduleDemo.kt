package com.djransom.crewsync

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.djransom.crewsync.ui.screens.ScheduleContent
import com.djransom.crewsync.ui.screens.SchedulePresentationScreen
import com.djransom.crewsync.util.ScheduleSampleData
import com.djransom.crewsync.util.WorkCalendar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.awt.Robot
import java.io.File
import javax.imageio.ImageIO

/**
 * The Schedule tab in a window of its own, filled with the Martin-shaped sample schedule. No
 * sign-in, no Firestore, nothing real is read or changed - it's for rehearsing presentation mode
 * on the meeting-room screen and for checking the full-screen behaviour.
 *
 *     gradlew :composeApp:scheduleDemo
 *
 * Press Present, then Esc (or the X) to come back. With "--capture some.png" it instead goes
 * straight into presentation mode, saves a picture of the screen a few seconds later, comes back
 * out, notes what the window did in some.png.txt, and closes - that's the automatic check.
 */
fun main(args: Array<String>) {
    val capturePath = args.indexOf("--capture").takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }
    val data = ScheduleSampleData
    val calendar = WorkCalendar(holidays = setOf(data.day(2026, 11, 26), data.day(2026, 11, 27)))

    application {
        val windowState = rememberWindowState(size = DpSize(1280.dp, 800.dp))
        var presenting by remember { mutableStateOf(capturePath != null) }

        Window(onCloseRequest = ::exitApplication, state = windowState, title = "Crewsync schedule demo (sample data)") {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    if (presenting) {
                        SchedulePresentationScreen("Martin (sample)", data.martinInProgress, data.roofTasks, data.roofAppointments, calendar, onClose = { presenting = false })
                    } else {
                        ScheduleContent(data.martinInProgress, data.roofTasks, data.roofAppointments, calendar, zone = data.zone, onPresent = { presenting = true })
                    }
                }
            }
            if (capturePath != null) {
                val density = LocalDensity.current.density
                LaunchedEffect(Unit) {
                    delay(4000)
                    val screen = window.graphicsConfiguration.bounds
                    // Every window the app has open while presenting: this demo window and the full-screen one.
                    val open = java.awt.Window.getWindows().filter { it.isShowing }.joinToString("\n") { w ->
                        "  ${w.bounds.width}x${w.bounds.height} at ${w.bounds.x},${w.bounds.y}" +
                            " borderless=${(w as? java.awt.Frame)?.isUndecorated ?: false} focused=${w.isFocused}"
                    }
                    val picture = withContext(Dispatchers.IO) { Robot().createScreenCapture(screen) }
                    withContext(Dispatchers.IO) { ImageIO.write(picture, "png", File(capturePath)) }
                    presenting = false
                    delay(1500)
                    File("$capturePath.txt").writeText(
                        "screen: ${screen.width}x${screen.height}, display scale: $density\n" +
                            "windows open while presenting:\n$open\n" +
                            "windows open after leaving: ${java.awt.Window.getWindows().count { it.isShowing }}\n" +
                            "picture: ${picture.width}x${picture.height}\n"
                    )
                    exitApplication()
                }
            }
        }
    }
}
