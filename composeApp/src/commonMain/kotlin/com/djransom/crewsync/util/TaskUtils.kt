package com.djransom.crewsync.util

import com.djransom.crewsync.data.model.Task
import kotlin.math.roundToInt

private val doneStatusSynonyms = setOf("done", "completed", "complete", "finished", "closed")

fun isDoneStatus(status: String): Boolean = status.trim().lowercase() in doneStatusSynonyms

// A task with no checklist has nothing to compute a ratio from, so its completion falls back to
// its status bucket: fully done if it's sitting in a Done-like bucket, otherwise not started.
fun taskCompletionPercent(task: Task): Int {
    val items = task.allChecklistItems()
    if (items.isEmpty()) return if (isDoneStatus(task.status)) 100 else 0
    return (items.count { it.isDone }.toFloat() / items.size * 100f).roundToInt()
}
