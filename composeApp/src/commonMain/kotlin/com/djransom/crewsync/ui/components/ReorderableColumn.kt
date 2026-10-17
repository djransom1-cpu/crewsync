package com.djransom.crewsync.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalHapticFeedback
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
 * separate tap action that would otherwise compete with the drag.
 *
 * With a finger, a row only picks up after a long press (with a buzz), so a plain swipe across
 * the cards scrolls the list instead of dragging a card - set [longPressOnTouch] false for a
 * small dedicated handle icon, where an immediate drag is what you want. With a mouse or pen the
 * drag starts as soon as the pointer moves (long-press didn't reliably continue past the
 * initial grab with desktop mouse input).
 *
 * [itemKey] must return a stable identity for each item (e.g. its id) - NOT the item itself when
 * the item's content can be edited in place. Keying on a data class that changes on every
 * keystroke (a checklist step's text) tears down and rebuilds that row's TextField each time,
 * dropping focus after a single character.
 */
@Composable
fun <T> ReorderableColumn(
    items: List<T>,
    onReorder: (List<T>) -> Unit,
    rowHeight: Dp = 48.dp,
    itemKey: (T) -> Any = { it as Any },
    longPressOnTouch: Boolean = true,
    itemContent: @Composable (item: T, dragHandleModifier: Modifier) -> Unit
) {
    val density = LocalDensity.current
    val rowHeightPx = with(density) { rowHeight.toPx() }
    val haptics = LocalHapticFeedback.current

    var draggingIndex by remember { mutableStateOf(-1) }
    var dragOffsetPx by remember { mutableStateOf(0f) }
    var localItems by remember { mutableStateOf(items) }
    val currentItems by rememberUpdatedState(items)
    // The [items] list a finished drag was committed against. Until the caller hands back a new
    // list (e.g. the Firestore write round-trips), keep showing the dropped order instead of
    // snapping back to the old one for a moment.
    var committedAgainst by remember { mutableStateOf<List<T>?>(null) }

    // Outside a drag, render straight from [items] so in-place edits (typing into a row's
    // TextField) show up in the same frame - lagging a frame behind via a LaunchedEffect copy
    // made the field briefly revert to stale text on every keystroke. Mid-drag, localItems is
    // the source of truth until the drag finishes and onReorder commits it back up.
    val displayItems = when {
        draggingIndex != -1 -> localItems
        committedAgainst === items -> localItems
        else -> items
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        displayItems.forEachIndexed { index, item ->
            // Keyed on the item's stable identity (itemKey), not the loop index - without this, a reorder mid-drag
            // (localItems mutated in onDrag below) makes Compose reuse each positional slot for
            // whatever item now lands at that index instead of moving the existing node with its
            // data, tearing down and rebuilding the displaced rows' composables (losing their
            // remembered state and any in-flight styling) instead of smoothly repositioning them
            // - exactly the flicker/stutter on siblings seen during a drag.
            key(itemKey(item)) {
                // The drag gesture below is only restarted when this row's key changes, so it
                // reads the row's current index through this rather than a stale capture.
                val currentIndex by rememberUpdatedState(index)
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
                    val handleModifier = Modifier.pointerInput(itemKey(item), longPressOnTouch) {
                        val start = {
                            localItems = currentItems
                            draggingIndex = currentIndex
                            dragOffsetPx = 0f
                        }
                        val moveBy = { dy: Float ->
                            dragOffsetPx += dy
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
                        }
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val touch = down.type == PointerType.Touch
                            // finger: hold to pick up (a quick swipe is left to the list to scroll);
                            // mouse/pen: move past the slop and it's a drag
                            val grabbed = if (touch && longPressOnTouch) {
                                awaitLongPressOrCancellation(down.id)?.also { haptics.performHapticFeedback(HapticFeedbackType.LongPress) }
                            } else {
                                var over = 0f
                                awaitTouchSlopOrCancellation(down.id) { change, overSlop -> change.consume(); over = overSlop.y }
                                    ?.also { start(); moveBy(over) }
                            }
                            if (grabbed != null) {
                                if (draggingIndex == -1) start()
                                val finished = drag(grabbed.id) { change ->
                                    moveBy(change.positionChange().y)
                                    change.consume()
                                }
                                if (finished && localItems != currentItems) {
                                    committedAgainst = currentItems
                                    draggingIndex = -1
                                    dragOffsetPx = 0f
                                    onReorder(localItems)
                                } else {
                                    draggingIndex = -1
                                    dragOffsetPx = 0f
                                    localItems = currentItems
                                }
                            }
                        }
                    }
                    itemContent(item, handleModifier)
                }
            }
        }
    }
}
