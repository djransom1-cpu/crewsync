package com.djransom.crewsync.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.firestore.DocumentSnapshot
import dev.gitlive.firebase.firestore.firestore
import kotlinx.coroutines.launch
import kotlin.time.Clock

sealed class NotepadAction {
    data class Draw(val path: Path, val points: List<Offset>, val color: Color, val strokeWidth: Float) : NotepadAction()
    data class Highlight(val path: Path, val points: List<Offset>, val color: Color, val strokeWidth: Float) : NotepadAction()
    data class Erase(val path: Path, val points: List<Offset>, val strokeWidth: Float) : NotepadAction()
    data class Line(val start: Offset, val end: Offset, val color: Color, val strokeWidth: Float) : NotepadAction()
    data class Rectangle(val start: Offset, val end: Offset, val color: Color, val strokeWidth: Float) : NotepadAction()
    data class Ellipse(val start: Offset, val end: Offset, val color: Color, val strokeWidth: Float) : NotepadAction()
    data class TextNote(val text: String, val position: Offset, val color: Color, val size: Float, val transparent: Boolean = false) : NotepadAction()
}

private data class NotepadItem(val id: String, val action: NotepadAction)

// The lined-paper background color the Eraser tool paints over strokes with, so an erased
// stroke actually disappears instead of just being drawn a different color on top of it.
private val PaperColor = Color(0xFFFFFEF7)

@Composable
fun NotepadScreen(projectId: String, noteId: String, noteTitle: String, onBack: () -> Unit) {
    val firestore = Firebase.firestore
    val scope = rememberCoroutineScope()
    val noteDoc = remember(projectId, noteId) { firestore.collection("projects").document(projectId).collection("notes").document(noteId) }
    val notepadColl = remember(noteDoc) { noteDoc.collection("actions") }
    suspend fun touchNote() { try { noteDoc.update("updatedAt" to Clock.System.now().toEpochMilliseconds()) } catch (_: Exception) {} }

    val items = remember { mutableStateListOf<NotepadItem>() }
    val currentPoints = remember { mutableStateListOf<Offset>() }

    var currentPath by remember { mutableStateOf<Path?>(null) }
    var startOffset by remember { mutableStateOf<Offset?>(null) }
    var currentDragOffset by remember { mutableStateOf<Offset?>(null) }

    var selectedColor by remember { mutableStateOf(Color.Black) }
    var strokeWidth by remember { mutableStateOf(6f) }
    var toolMode by remember { mutableStateOf("Pen") } // Pen, Highlight, Eraser, Line, Rect, Circle, Text, Pan

    var showTextDialog by remember { mutableStateOf(false) }
    var tempTextPos by remember { mutableStateOf(Offset.Zero) }
    var showClearConfirm by remember { mutableStateOf(false) }

    var zoomScale by remember { mutableStateOf(1f) }
    var zoomOffset by remember { mutableStateOf(Offset.Zero) }

    LaunchedEffect(noteDoc) {
        notepadColl.snapshots.collect { snap ->
            val loaded = snap.documents.mapNotNull { doc -> parseNotepadDoc(doc) }.sortedBy { it.first }
            items.clear()
            items.addAll(loaded.map { (_, idAction) -> NotepadItem(idAction.first, idAction.second) })
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.background(MaterialTheme.colorScheme.surface)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to Notes")
                }
                Text(
                    noteTitle.ifBlank { "Untitled Note" },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                ToolChip("Pen", toolMode == "Pen", Icons.Default.Edit) { toolMode = "Pen" }
                ToolChip("Highlight", toolMode == "Highlight", Icons.Default.Star) { toolMode = "Highlight" }
                ToolChip("Line", toolMode == "Line", Icons.Default.Menu) { toolMode = "Line" }
                ToolChip("Rect", toolMode == "Rect", Icons.Default.Place) { toolMode = "Rect" }
                ToolChip("Circle", toolMode == "Circle", Icons.Default.Info) { toolMode = "Circle" }
                ToolChip("Text", toolMode == "Text", Icons.Default.Add) { toolMode = "Text" }
                ToolChip("Pan", toolMode == "Pan", Icons.Default.Lock) { toolMode = "Pan" }
                ToolChip("Eraser", toolMode == "Eraser", Icons.Default.Delete) { toolMode = "Eraser" }

                Spacer(modifier = Modifier.width(8.dp))

                ColorButton(Color.Black, selectedColor) { selectedColor = Color.Black }
                ColorButton(Color.Red, selectedColor) { selectedColor = Color.Red }
                ColorButton(Color(0xFF1565C0), selectedColor) { selectedColor = Color(0xFF1565C0) }
                ColorButton(Color(0xFF2E7D32), selectedColor) { selectedColor = Color(0xFF2E7D32) }
                ColorButton(Color(0xFFEF6C00), selectedColor) { selectedColor = Color(0xFFEF6C00) }
                ColorButton(Color(0xFF6A1B9A), selectedColor) { selectedColor = Color(0xFF6A1B9A) }

                Spacer(modifier = Modifier.width(8.dp))

                IconButton(onClick = {
                    val last = items.lastOrNull()
                    if (last != null) {
                        scope.launch { notepadColl.document(last.id).delete(); touchNote() }
                    }
                }) {
                    Icon(Icons.Default.Build, contentDescription = "Undo")
                }
                TextButton(onClick = { showClearConfirm = true }) {
                    Text("Clear", color = MaterialTheme.colorScheme.error)
                }
            }
            HorizontalDivider()
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .weight(1f)
                .background(PaperColor)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = zoomScale,
                        scaleY = zoomScale,
                        translationX = zoomOffset.x,
                        translationY = zoomOffset.y
                    )
            ) {
                // Faint ruled-paper lines for a notepad feel.
                Canvas(modifier = Modifier.fillMaxSize()) {
                    var y = 0f
                    while (y < size.height) {
                        drawLine(Color(0xFFE3E1D6), Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
                        y += 32f
                    }
                }

                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(toolMode) {
                            if (toolMode == "Pan") {
                                detectTransformGestures { _, pan, zoom, _ ->
                                    zoomScale = (zoomScale * zoom).coerceIn(1f, 5f)
                                    zoomOffset += pan
                                }
                            } else {
                                detectDragGestures(
                                    onDragStart = { offset ->
                                        currentPoints.clear()
                                        currentPoints.add(offset)
                                        if (toolMode == "Text") {
                                            tempTextPos = offset
                                            showTextDialog = true
                                        } else if (toolMode in listOf("Line", "Rect", "Circle")) {
                                            startOffset = offset
                                            currentDragOffset = offset
                                        } else {
                                            currentPath = Path().apply { moveTo(offset.x, offset.y) }
                                        }
                                    },
                                    onDrag = { change, _ ->
                                        currentPoints.add(change.position)
                                        if (toolMode in listOf("Line", "Rect", "Circle")) {
                                            currentDragOffset = change.position
                                        } else if (toolMode in listOf("Pen", "Highlight", "Eraser")) {
                                            currentPath?.lineTo(change.position.x, change.position.y)
                                            val p = currentPath
                                            currentPath = null
                                            currentPath = p
                                        }
                                    },
                                    onDragEnd = {
                                        if (toolMode in listOf("Line", "Rect", "Circle") && startOffset != null && currentDragOffset != null) {
                                            val s = startOffset!!
                                            val e = currentDragOffset!!
                                            val action = when (toolMode) {
                                                "Line" -> NotepadAction.Line(s, e, selectedColor, strokeWidth)
                                                "Rect" -> NotepadAction.Rectangle(s, e, selectedColor, strokeWidth)
                                                else -> NotepadAction.Ellipse(s, e, selectedColor, strokeWidth)
                                            }
                                            scope.launch { notepadColl.add(notepadActionToMap(action)); touchNote() }
                                        } else {
                                            currentPath?.let { path ->
                                                val pts = currentPoints.toList()
                                                val action = when (toolMode) {
                                                    "Eraser" -> NotepadAction.Erase(path, pts, strokeWidth)
                                                    "Highlight" -> NotepadAction.Highlight(path, pts, selectedColor.copy(alpha = 0.4f), strokeWidth * 2.5f)
                                                    else -> NotepadAction.Draw(path, pts, selectedColor, strokeWidth)
                                                }
                                                scope.launch { notepadColl.add(notepadActionToMap(action)); touchNote() }
                                            }
                                        }
                                        currentPath = null
                                        startOffset = null
                                        currentDragOffset = null
                                    }
                                )
                            }
                        }
                ) {
                    items.forEach { renderNotepadAction(it.action) }

                    if (startOffset != null && currentDragOffset != null) {
                        val s = startOffset!!
                        val e = currentDragOffset!!
                        when (toolMode) {
                            "Line" -> drawLine(selectedColor, s, e, strokeWidth = strokeWidth, cap = StrokeCap.Round)
                            "Rect" -> drawRect(selectedColor, s, Size(e.x - s.x, e.y - s.y), style = Stroke(strokeWidth))
                            "Circle" -> drawOval(selectedColor, s, Size(e.x - s.x, e.y - s.y), style = Stroke(strokeWidth))
                        }
                    }

                    currentPath?.let { path ->
                        drawPath(
                            path = path,
                            color = if (toolMode == "Eraser") PaperColor else if (toolMode == "Highlight") selectedColor.copy(alpha = 0.4f) else selectedColor,
                            style = Stroke(width = if (toolMode == "Highlight") strokeWidth * 2.5f else strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
                        )
                    }
                }

                // Draggable text-note overlays, always movable regardless of the active tool.
                val density = LocalDensity.current
                items.forEach { item ->
                    val action = item.action
                    if (action is NotepadAction.TextNote) {
                        val currentId = item.id
                        var dragOffset by remember(currentId) { mutableStateOf(Offset.Zero) }
                        var sizeDrag by remember(currentId) { mutableStateOf(0f) }
                        val pos = action.position + dragOffset
                        val liveSize = (action.size + sizeDrag).coerceIn(10f, 72f)
                        Box(
                            modifier = Modifier
                                .offset(x = with(density) { pos.x.toDp() }, y = with(density) { pos.y.toDp() })
                                .pointerInput(currentId) {
                                    detectDragGestures(
                                        onDrag = { change, amount -> change.consume(); dragOffset += amount },
                                        onDragEnd = {
                                            val newPos = action.position + dragOffset
                                            scope.launch {
                                                notepadColl.document(currentId).update("startX" to newPos.x.toDouble())
                                                notepadColl.document(currentId).update("startY" to newPos.y.toDouble())
                                                touchNote()
                                            }
                                            dragOffset = Offset.Zero
                                        }
                                    )
                                }
                                .background(
                                    if (action.transparent) Color.Transparent else Color.White.copy(alpha = 0.95f),
                                    RoundedCornerShape(4.dp)
                                )
                                .border(2.dp, action.color, RoundedCornerShape(4.dp))
                                .padding(6.dp)
                        ) {
                            Text(
                                action.text,
                                color = if (action.transparent) action.color else Color.Black,
                                fontSize = liveSize.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .offset(x = 8.dp, y = (-8).dp)
                                    .size(16.dp)
                                    .clip(CircleShape)
                                    .background(action.color)
                                    .clickable {
                                        scope.launch { notepadColl.document(currentId).update("transparent" to !action.transparent); touchNote() }
                                    }
                            )
                            Box(
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .offset(x = 8.dp, y = 8.dp)
                                    .size(16.dp)
                                    .clip(CircleShape)
                                    .background(Color.White)
                                    .border(1.5.dp, action.color, CircleShape)
                                    .pointerInput(currentId) {
                                        detectDragGestures(
                                            onDrag = { change, amount ->
                                                change.consume()
                                                sizeDrag += (amount.x + amount.y) / 2f
                                            },
                                            onDragEnd = {
                                                val newSize = (action.size + sizeDrag).coerceIn(10f, 72f)
                                                scope.launch { notepadColl.document(currentId).update("strokeWidth" to newSize.toDouble()); touchNote() }
                                                sizeDrag = 0f
                                            }
                                        )
                                    }
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                listOf(3f, 6f, 12f, 20f).forEach { presetWidth ->
                    StrokeWidthButton(presetWidth, strokeWidth == presetWidth) { strokeWidth = presetWidth }
                }
                Spacer(modifier = Modifier.width(8.dp))
                IconButton(
                    onClick = { zoomScale = (zoomScale * 1.25f).coerceAtMost(5f) },
                    modifier = Modifier.background(MaterialTheme.colorScheme.surface, CircleShape)
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Zoom In")
                }
                IconButton(
                    onClick = { zoomScale = (zoomScale / 1.25f).coerceAtLeast(1f); if (zoomScale <= 1f) zoomOffset = Offset.Zero },
                    modifier = Modifier.background(MaterialTheme.colorScheme.surface, CircleShape)
                ) {
                    Icon(Icons.Default.Menu, contentDescription = "Zoom Out")
                }
            }
        }
    }

    if (showTextDialog) {
        AddTextDialog(
            onDismiss = { showTextDialog = false },
            onConfirm = { content, size, transparent ->
                if (content.isNotEmpty()) {
                    val action = NotepadAction.TextNote(content, tempTextPos, selectedColor, size, transparent)
                    scope.launch { notepadColl.add(notepadActionToMap(action)); touchNote() }
                }
                showTextDialog = false
            }
        )
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("Clear Note?") },
            text = { Text("This erases everything drawn on this note for all project members. This can't be undone.") },
            confirmButton = {
                Button(
                    onClick = {
                        val toDelete = items.toList()
                        scope.launch { toDelete.forEach { notepadColl.document(it.id).delete() }; touchNote() }
                        showClearConfirm = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("Clear Everything") }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) { Text("Cancel") }
            }
        )
    }
}

private fun DrawScope.renderNotepadAction(action: NotepadAction) {
    when (action) {
        is NotepadAction.Draw -> drawPath(action.path, action.color, style = Stroke(width = action.strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round))
        is NotepadAction.Highlight -> drawPath(action.path, action.color, style = Stroke(width = action.strokeWidth, cap = StrokeCap.Square, join = StrokeJoin.Bevel))
        is NotepadAction.Erase -> drawPath(action.path, PaperColor, style = Stroke(width = action.strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round))
        is NotepadAction.Line -> drawLine(action.color, action.start, action.end, strokeWidth = action.strokeWidth, cap = StrokeCap.Round)
        is NotepadAction.Rectangle -> drawRect(action.color, action.start, Size(action.end.x - action.start.x, action.end.y - action.start.y), style = Stroke(action.strokeWidth))
        is NotepadAction.Ellipse -> drawOval(action.color, action.start, Size(action.end.x - action.start.x, action.end.y - action.start.y), style = Stroke(action.strokeWidth))
        is NotepadAction.TextNote -> {} // rendered as a draggable Compose overlay, not a DrawScope call
    }
}

private fun buildStraightPath(points: List<Offset>): Path {
    val path = Path()
    if (points.isNotEmpty()) {
        path.moveTo(points[0].x, points[0].y)
        for (i in 1 until points.size) path.lineTo(points[i].x, points[i].y)
    }
    return path
}

private fun colorHex(color: Color): String = "#" + color.toArgb().toUInt().toString(16)

private fun notepadActionToMap(action: NotepadAction): Map<String, Any?> {
    val map = mutableMapOf<String, Any?>("createdAt" to Clock.System.now().toEpochMilliseconds().toDouble())
    when (action) {
        is NotepadAction.Draw -> {
            map["type"] = "Draw"
            map["colorHex"] = colorHex(action.color)
            map["strokeWidth"] = action.strokeWidth.toDouble()
            map["points"] = action.points.flatMap { listOf(it.x.toDouble(), it.y.toDouble()) }
        }
        is NotepadAction.Highlight -> {
            map["type"] = "Highlight"
            map["colorHex"] = colorHex(action.color)
            map["strokeWidth"] = action.strokeWidth.toDouble()
            map["points"] = action.points.flatMap { listOf(it.x.toDouble(), it.y.toDouble()) }
        }
        is NotepadAction.Erase -> {
            map["type"] = "Erase"
            map["strokeWidth"] = action.strokeWidth.toDouble()
            map["points"] = action.points.flatMap { listOf(it.x.toDouble(), it.y.toDouble()) }
        }
        is NotepadAction.Line -> {
            map["type"] = "Line"
            map["startX"] = action.start.x.toDouble(); map["startY"] = action.start.y.toDouble()
            map["endX"] = action.end.x.toDouble(); map["endY"] = action.end.y.toDouble()
            map["colorHex"] = colorHex(action.color)
            map["strokeWidth"] = action.strokeWidth.toDouble()
        }
        is NotepadAction.Rectangle -> {
            map["type"] = "Rect"
            map["startX"] = action.start.x.toDouble(); map["startY"] = action.start.y.toDouble()
            map["endX"] = action.end.x.toDouble(); map["endY"] = action.end.y.toDouble()
            map["colorHex"] = colorHex(action.color)
            map["strokeWidth"] = action.strokeWidth.toDouble()
        }
        is NotepadAction.Ellipse -> {
            map["type"] = "Ellipse"
            map["startX"] = action.start.x.toDouble(); map["startY"] = action.start.y.toDouble()
            map["endX"] = action.end.x.toDouble(); map["endY"] = action.end.y.toDouble()
            map["colorHex"] = colorHex(action.color)
            map["strokeWidth"] = action.strokeWidth.toDouble()
        }
        is NotepadAction.TextNote -> {
            map["type"] = "TextNote"
            map["text"] = action.text
            map["startX"] = action.position.x.toDouble(); map["startY"] = action.position.y.toDouble()
            map["colorHex"] = colorHex(action.color)
            map["strokeWidth"] = action.size.toDouble()
            map["transparent"] = action.transparent
        }
    }
    return map
}

// Returns (createdAt, (docId, action)) so the caller can sort by creation order before dropping
// the timestamp - Firestore doesn't guarantee snapshot document order matches write order.
private fun parseNotepadDoc(doc: DocumentSnapshot): Pair<Double, Pair<String, NotepadAction>>? {
    return try {
        val createdAt = try { doc.get<Double>("createdAt") } catch (_: Exception) { 0.0 }
        val type = doc.get<String>("type")
        val colorHex = try { doc.get<String>("colorHex") } catch (_: Exception) { "#FF000000" }
        val strokeW = try { doc.get<Double>("strokeWidth").toFloat() } catch (_: Exception) { 6f }
        val color = try { Color(colorHex.removePrefix("#").toLong(16) or 0xFF000000) } catch (_: Exception) { Color.Black }

        val startX = try { doc.get<Double>("startX").toFloat() } catch (_: Exception) { 0f }
        val startY = try { doc.get<Double>("startY").toFloat() } catch (_: Exception) { 0f }
        val endX = try { doc.get<Double>("endX").toFloat() } catch (_: Exception) { 0f }
        val endY = try { doc.get<Double>("endY").toFloat() } catch (_: Exception) { 0f }
        val start = Offset(startX, startY)
        val end = Offset(endX, endY)

        val text = try { doc.get<String>("text") } catch (_: Exception) { "" }
        val transparent = try { doc.get<Boolean>("transparent") } catch (_: Exception) { false }
        val pts = try { doc.get<List<Double>>("points").map { it.toFloat() } } catch (_: Exception) { emptyList() }
        val offsets = mutableListOf<Offset>()
        for (i in 0 until pts.size - 1 step 2) offsets.add(Offset(pts[i], pts[i + 1]))

        val action = when (type) {
            "Draw" -> NotepadAction.Draw(buildStraightPath(offsets), offsets, color, strokeW)
            "Highlight" -> NotepadAction.Highlight(buildStraightPath(offsets), offsets, color, strokeW)
            "Erase" -> NotepadAction.Erase(buildStraightPath(offsets), offsets, strokeW)
            "Line" -> NotepadAction.Line(start, end, color, strokeW)
            "Rect" -> NotepadAction.Rectangle(start, end, color, strokeW)
            "Ellipse" -> NotepadAction.Ellipse(start, end, color, strokeW)
            "TextNote" -> NotepadAction.TextNote(text, start, color, strokeW, transparent)
            else -> null
        } ?: return null

        createdAt to (doc.id to action)
    } catch (_: Exception) { null }
}
