package com.djransom.crewsync.util

import com.djransom.crewsync.data.model.Task

/**
 * Renders the Planner's task/checklist outline as a real, paginated document and hands it to the
 * platform's native print flow. Deliberately not a screenshot of the on-screen list view - a
 * printed page needs its own layout (page breaks, readable in black-and-white, no scrolling).
 *
 * Deliberately just this one expect declaration and nothing else in this file - each platform's
 * actual file (desktopMain/androidMain/iosMain) shares this exact filename, and an expect
 * declaration alone generates no JVM bytecode, so there's nothing here to collide with their
 * real implementations. See buildPlannerPrintLines in PlannerPrintFormat.kt for the shared "what
 * goes on the page" logic every actual draws from.
 */
expect fun printPlannerOutline(projectName: String, buckets: List<String>, tasks: List<Task>)
