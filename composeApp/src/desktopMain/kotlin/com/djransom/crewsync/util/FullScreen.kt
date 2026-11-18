package com.djransom.crewsync.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import java.awt.GraphicsEnvironment
import java.awt.KeyboardFocusManager

@Composable
actual fun FullScreenHost(onExit: () -> Unit, content: @Composable () -> Unit) {
    // The screen the app window is on right now - the TV, if the app has been dragged over to it.
    // Failing that (no window of ours is up yet), the main display.
    val screen = remember {
        val appWindow = KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow
            ?: java.awt.Window.getWindows().firstOrNull { it.isShowing }
        val display = appWindow?.graphicsConfiguration
            ?: GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration
        display.bounds
    }
    // A borderless window laid exactly over that screen. A normal window asked to go full-screen
    // keeps its title bar on Windows and leaves a strip of taskbar showing; this does not.
    val state = rememberWindowState(
        placement = WindowPlacement.Floating,
        position = WindowPosition(screen.x.dp, screen.y.dp),
        size = DpSize(screen.width.dp, screen.height.dp)
    )
    // It also stays on top of everything else: over the taskbar, and still showing on the TV if the
    // presenter clicks on some other window on the laptop screen. Esc or the X closes it.
    Window(onCloseRequest = onExit, state = state, title = "Crewsync", undecorated = true, resizable = false, alwaysOnTop = true) {
        // Come to the front and take the keyboard, so Esc works without a click first.
        LaunchedEffect(Unit) {
            window.toFront()
            window.requestFocus()
        }
        content()
    }
}
