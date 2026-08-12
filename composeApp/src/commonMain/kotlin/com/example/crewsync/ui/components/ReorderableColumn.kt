package com.example.crewsync.ui.components

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
 * A plain (non-lazy) column of drag-reorderable rows. Every row is given the same fixed
 * [rowHeight] so the dragged row's position can be converted to a target index with simple
 * arithmetic instead of measuring each row - there's no LazyColumn here (these lists live
 * inside a single scrollable dialog Column, where a nested LazyColumn would need its own
 * bounded height), so this keeps the reorder math self-contained and dependency-free.
 *
 * [itemContent] receives a `dragHandleModifier` to attach to whichever part of the row (e.g. a
 * drag-handle icon) should start the drag on long-press - the rest of the row stays free for
 * normal taps (checkbox, text field, delete button) without fighting the drag gesture.
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
                    .height(rowHeight)
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
                    detectDragGesturesAfterLongPress(
                        onDragStart = {
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
