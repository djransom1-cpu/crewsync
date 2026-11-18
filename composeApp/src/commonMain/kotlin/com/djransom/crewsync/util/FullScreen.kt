package com.djransom.crewsync.util

import androidx.compose.runtime.Composable

/**
 * Shows [content] over the whole display for as long as this is in the composition, and gives the
 * display back when it leaves. Used by the schedule presentation mode.
 *
 *  - Desktop: a separate borderless window covering the screen the app is on (so the title bar
 *    and the taskbar are gone, which a normal window can not manage on Windows).
 *  - Android: the same screen, turned to landscape with the system bars hidden.
 *  - Web: the same page, asking the browser for its full-screen mode.
 *
 * [onExit] is called when the person uses the platform way out: the Back button on Android, or
 * closing the full-screen window on desktop. The caller handles its own Close button and Esc.
 */
@Composable
expect fun FullScreenHost(onExit: () -> Unit, content: @Composable () -> Unit)
