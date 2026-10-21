package com.djransom.crewsync.util

expect fun getAppVersion(): String

/**
 * True on phones/tablets (Android, iOS), where the user scrolls by touch. False on web and
 * desktop, where there's a mouse wheel and some controls only make sense there - e.g. the
 * Planner board's < > bucket buttons.
 */
expect val isTouchPlatform: Boolean
