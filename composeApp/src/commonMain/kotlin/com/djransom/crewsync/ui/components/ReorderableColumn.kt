package com.djransom.crewsync.ui.components

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt

/**
 * A plain (non-lazy) column of drag-reorderable rows. [rowHeight] is only a *minimum* row
 * height - rows grow taller to fit their content (e.g. a checklist item's text wrapping to
 * multiple lines) - and it doubles as the fixed step size the drag gesture uses to convert
 * dragged distance into a target index, instead of measuring each row - there's no LazyColumn
 * here (these lists live inside a single scrollable dialog Column, where a nested LazyColumn
 * would need its own bounded height), so this keeps the reorder math self-contained and
 * dependency-free. Because that step size doesn't account for taller wrapped rows, dragging past
 * one is approximate rather than exact.
 *
 * [itemContent] receives a `dragHandleModifier` to attach wherever the row's drag surface should
 * be - the whole row content, or just a dedicated handle icon if some part of the row needs a
 * separate tap action that would otherwise compete with the drag. Drag starts immediately on
 * pointer movement (no long-press gate): long-press detection was tried first but didn't
 * reliably continue past the initial grab with desktop mouse input.
 */
@Composable
fun <T> ReorderableColumn(
    items: List<T>,
    onReorder: (List<T>) -> Unit,
    rowHeight: Dp = 48.dp,
    itemContent: @Composable (item: T, dragHandleModifier: Modifier) -> Unit
) {
    val density = LocalDensity.current
    val rowHeightPx = with(density) { rowHeight.toPx() }

    var draggingIndex by remember { mutableStateOf(-1) }
    var dragOffsetPx by remember { mutableStateOf(0f) }
    var localItems by remember { mutableStateOf(items) }

    // Pick up external changes (add/remove/edit elsewhere) except mid-drag, where localItems
    // is the source of truth until the drag finishes and onReorder commits it back up.
    LaunchedEffect(items) {
        if (draggingIndex == -1) localItems = items
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        localItems.forEachIndexed { index, item ->
            val isDragging = index == draggingIndex
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = rowHeight)
                    .zIndex(if (isDragging) 1f else 0f)
                    .then(
                        if (isDragging) {
                            Modifier.offset { IntOffset(0, dragOffsetPx.roundToInt()) }
                        } else {
                            Modifier
                        }
                    )
            ) {
                val handleModifier = Modifier.pointerInput(item) {
                    detectDragGestures(
                        onDragStart = { _ ->
                            draggingIndex = index
                            dragOffsetPx = 0f
                        },
                        onDrag = { change, delta ->
                            change.consume()
                            dragOffsetPx += delta.y
                            val shift = (dragOffsetPx / rowHeightPx).roundToInt()
                            if (shift != 0) {
                                val from = draggingIndex
                                val to = (from + shift).coerceIn(0, localItems.lastIndex)
                                if (to != from) {
                                    val mutable = localItems.toMutableList()
                                    val moved = mutable.removeAt(from)
                                    mutable.add(to, moved)
                                    localItems = mutable
                                    draggingIndex = to
                                    dragOffsetPx -= shift * rowHeightPx
                                }
                            }
                        },
                        onDragEnd = {
                            draggingIndex = -1
                            dragOffsetPx = 0f
                            onReorder(localItems)
                        },
                        onDragCancel = {
                            draggingIndex = -1
                            dragOffsetPx = 0f
                            localItems = items
                        }
                    )
                }
                itemContent(item, handleModifier)
            }
        }
    }
}
