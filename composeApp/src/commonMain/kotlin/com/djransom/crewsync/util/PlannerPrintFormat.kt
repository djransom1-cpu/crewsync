package com.djransom.crewsync.util

import com.djransom.crewsync.data.model.Task

/** One line of the printed Planner outline: a bucket header, a task title (with its completion
 * shown inline), or a checklist item nested under that task. */
data class PlannerPrintLine(
    val text: String,
    val indent: Int,
    val isChecklistItem: Boolean = false,
    val isChecked: Boolean = false,
    val bold: Boolean = false
)

// Shared by every platform's actual printPlannerOutline (see PrintUtils.kt), so "what goes on
// the printed page" has exactly one implementation - each platform only decides how to lay these
// lines out (PDF text on Desktop/Android, an HTML string on iOS).
fun buildPlannerPrintLines(buckets: List<String>, tasks: List<Task>): List<PlannerPrintLine> {
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
            items.forEach { item ->
                lines += PlannerPrintLine(item.text, indent = 2, isChecklistItem = true, isChecked = item.isDone)
            }
        }
    }
    return lines
}
