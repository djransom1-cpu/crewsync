package com.djransom.crewsync.util

import com.djransom.crewsync.data.model.Task
import com.djransom.crewsync.ui.screens.formatDate

/** One line of the printed Planner outline: a bucket header, a task title (with its completion
 * shown inline), or a checklist item nested under that task. */
data class PlannerPrintLine(
    val text: String,
    val indent: Int,
    val isChecklistItem: Boolean = false,
    val isChecked: Boolean = false,
    val bold: Boolean = false
)

/** Page heading shared by every platform's print output - "Task Card" for a single-task
 * printout, "Task Planner" for the whole board. */
fun plannerPrintHeading(projectName: String, detailed: Boolean): String =
    "${projectName.ifBlank { "Project" }} - ${if (detailed) "Task Card" else "Task Planner"}"

/** Prints one task card on its own: its bucket, description, assigned crew, dates and every
 * checklist (with each named checklist's title) - the full-board print only lists the steps.
 *
 * [userMap] (email -> display name) resolves the assignees to names. The printed copy of the
 * task carries those names in place of the emails, so the platform print code doesn't need a
 * separate lookup - it's never saved back. */
fun printPlannerTask(projectName: String, task: Task, userMap: Map<String, String>) {
    val printTask = task.copy(
        assignedTo = null,
        assignedMembers = task.getAllAssignedEmails().map { userMap[it] ?: it }
    )
    printPlannerOutline(projectName, listOf(task.status), listOf(printTask), detailed = true)
}

// Shared by every platform's actual printPlannerOutline (see PrintUtils.kt), so "what goes on
// the printed page" has exactly one implementation - each platform only decides how to lay these
// lines out (PDF text on Desktop/Android, an HTML string on iOS/Web).
//
// detailed = true (single-task print) adds the description, assigned crew, start/due dates, and each checklist
// group's title as its own sub-header above that group's items.
fun buildPlannerPrintLines(buckets: List<String>, tasks: List<Task>, detailed: Boolean = false): List<PlannerPrintLine> {
    val lines = mutableListOf<PlannerPrintLine>()
    buckets.forEach { bucket ->
        val bucketTasks = tasks.filter { it.status == bucket }
        if (bucketTasks.isEmpty()) return@forEach
        lines += PlannerPrintLine(bucket, indent = 0, bold = true)
        bucketTasks.forEach { task ->
            val items = task.allChecklistItems()
            val titleText = if (items.isEmpty()) {
                task.title
            } else {
                val pct = taskCompletionPercent(task)
                "${task.title}   (${items.count { it.isDone }}/${items.size} - $pct%)"
            }
            lines += PlannerPrintLine(titleText, indent = 1, bold = true)
            if (detailed) {
                wrapPrintText(task.description).forEach { lines += PlannerPrintLine(it, indent = 2) }
                val assigned = task.getAllAssignedEmails()
                wrapPrintText("Assigned: " + (if (assigned.isEmpty()) "Unassigned" else assigned.joinToString(", ")))
                    .forEach { lines += PlannerPrintLine(it, indent = 2) }
                val dates = listOfNotNull(
                    task.startDate?.let { "Start: ${formatDate(it)}" },
                    task.dueDate?.let { "Due: ${formatDate(it)}" }
                )
                if (dates.isNotEmpty()) lines += PlannerPrintLine(dates.joinToString("    "), indent = 2)
                task.checklistGroups.filter { it.items.isNotEmpty() }.forEach { group ->
                    lines += PlannerPrintLine(
                        "${group.title.ifBlank { "Checklist" }}   (${group.items.count { it.isDone }}/${group.items.size})",
                        indent = 2,
                        bold = true
                    )
                    group.items.forEach { item ->
                        lines += PlannerPrintLine(item.text, indent = 3, isChecklistItem = true, isChecked = item.isDone)
                    }
                }
            } else {
                items.forEach { item ->
                    lines += PlannerPrintLine(item.text, indent = 2, isChecklistItem = true, isChecked = item.isDone)
                }
            }
        }
    }
    return lines
}

// The PDF renderers draw each line as a single run of text with no wrapping, so a multi-sentence
// description would run off the right edge of the page - break it into page-width lines here.
private fun wrapPrintText(text: String, maxChars: Int = 90): List<String> =
    text.lines().filter { it.isNotBlank() }.flatMap { paragraph ->
        val out = mutableListOf<String>()
        var current = StringBuilder()
        paragraph.trim().split(Regex("\\s+")).forEach { word ->
            if (current.isNotEmpty() && current.length + 1 + word.length > maxChars) {
                out += current.toString()
                current = StringBuilder()
            }
            if (current.isNotEmpty()) current.append(' ')
            current.append(word)
        }
        if (current.isNotEmpty()) out += current.toString()
        out
    }
