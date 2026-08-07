package com.example.crewsync.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.example.crewsync.data.model.ProjectFile
import com.example.crewsync.util.*
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.auth.auth
import dev.gitlive.firebase.firestore.firestore
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlin.math.*

sealed class MarkupAction {
    abstract val pageIndex: Int
    data class Draw(override val pageIndex: Int, val path: Path, val points: List<Offset>, val color: Color, val strokeWidth: Float) : MarkupAction()
    data class Highlight(override val pageIndex: Int, val path: Path, val points: List<Offset>, val color: Color, val strokeWidth: Float) : MarkupAction()
    data class Erase(override val pageIndex: Int, val path: Path, val points: List<Offset>, val strokeWidth: Float) : MarkupAction()
    data class Arrow(override val pageIndex: Int, val start: Offset, val end: Offset, val color: Color, val strokeWidth: Float) : MarkupAction()
    data class Rectangle(override val pageIndex: Int, val start: Offset, val end: Offset, val color: Color, val strokeWidth: Float) : MarkupAction()
    data class Cloud(override val pageIndex: Int, val start: Offset, val end: Offset, val color: Color, val strokeWidth: Float) : MarkupAction()
    data class TextNote(override val pageIndex: Int, val text: String, val position: Offset, val color: Color, val size: Float) : MarkupAction()
    data class Stamp(override val pageIndex: Int, val stampType: String, val position: Offset) : MarkupAction()
    data class Dimension(override val pageIndex: Int, val start: Offset, val end: Offset, val distanceStr: String, val color: Color) : MarkupAction()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MarkupScreen(
    projectId: String,
    fileId: String,
    onBack: () -> Unit,
    onSaveSuccess: () -> Unit
) {
    val firestore = Firebase.firestore
    val auth = Firebase.auth
    val scope = rememberCoroutineScope()
    val actions = remember { mutableStateListOf<MarkupAction>() }
    val currentPoints = remember { mutableStateListOf<Offset>() }
    val graphicsLayer = rememberGraphicsLayer()
    
    var projectFile by remember { mutableStateOf<ProjectFile?>(null) }
    var currentPath by remember { mutableStateOf<Path?>(null) }
    var startOffset by remember { mutableStateOf<Offset?>(null) }
    var currentDragOffset by remember { mutableStateOf<Offset?>(null) }

    var selectedColor by remember { mutableStateOf(Color.Red) }
    var strokeWidth by remember { mutableStateOf(8f) }
    var toolMode by remember { mutableStateOf("Pen") } // Pen, Highlight, Arrow, Rect, Cloud, Text, Stamp, Measure, Pan, Eraser
    
    var selectedStamp by remember { mutableStateOf("APPROVED") }
    var showStampMenu by remember { mutableStateOf(false) }
    var showTextDialog by remember { mutableStateOf(false) }
    var showScaleDialog by remember { mutableStateOf(false) }
    var tempTextPos by remember { mutableStateOf(Offset.Zero) }
    var isSaving by remember { mutableStateOf(false) }

    // Page Rotation, Zoom, & Scale
    var currentPageIndex by remember { mutableStateOf(0) }
    var rotationDegrees by remember { mutableStateOf(0f) }
    var zoomScale by remember { mutableStateOf(1f) }
    var zoomOffset by remember { mutableStateOf(Offset.Zero) }

    // Calibration: Pixels per Foot (Default 40.0 px = 1 ft)
    var pixelsPerFoot by remember { mutableStateOf(40.0) }
    var scalePresetName by remember { mutableStateOf("1/4\" = 1'-0\"") }

    LaunchedEffect(fileId) {
        try {
            val getSnap = firestore.collection("projects").document(projectId).collection("files").document(fileId).get()
            if (getSnap.exists) {
                projectFile = getSnap.toProjectFileSafe()
            }
        } catch (_: Exception) {}

        firestore.collection("projects").document(projectId).collection("files").document(fileId).snapshots.collect { snap ->
            if (snap.exists) {
                try {
                    projectFile = snap.toProjectFileSafe()
                } catch (_: Exception) {}
            }
        }
    }

    LaunchedEffect(fileId) {
        firestore.collection("projects").document(projectId).collection("files").document(fileId).collection("markups").snapshots.collect { snap ->
            val loadedActions = snap.documents.mapNotNull { doc ->
                try {
                    val pageIdx = (doc.get<Any?>("pageIndex") as? Number)?.toInt() ?: 0
                    val type = doc.get<String>("type")
                    val colorHex = try { doc.get<String>("colorHex") } catch (_: Exception) { "#FF0000" }
                    val strokeW = (doc.get<Any?>("strokeWidth") as? Number)?.toFloat() ?: 8f
                    val color = try { Color(colorHex.removePrefix("#").toLong(16) or 0xFF000000) } catch (_: Exception) { Color.Red }
                    
                    val startX = (doc.get<Any?>("startX") as? Number)?.toFloat() ?: 0f
                    val startY = (doc.get<Any?>("startY") as? Number)?.toFloat() ?: 0f
                    val endX = (doc.get<Any?>("endX") as? Number)?.toFloat() ?: 0f
                    val endY = (doc.get<Any?>("endY") as? Number)?.toFloat() ?: 0f
                    val start = Offset(startX, startY)
                    val end = Offset(endX, endY)
                    
                    val text = try { doc.get<String>("text") } catch (_: Exception) { "" }
                    val stampType = try { doc.get<String>("stampType") } catch (_: Exception) { "APPROVED" }
                    val distStr = try { doc.get<String>("distanceStr") } catch (_: Exception) { "" }
                    val ptsRaw = try { doc.get<List<Any?>>("points") } catch (_: Exception) { emptyList() }
                    val pts = ptsRaw.mapNotNull { (it as? Number)?.toFloat() }
                    val offsets = mutableListOf<Offset>()
                    for (i in 0 until pts.size - 1 step 2) {
                        offsets.add(Offset(pts[i], pts[i+1]))
                    }

                    fun buildPath(offsets: List<Offset>): Path {
                        val p = Path()
                        if (offsets.isNotEmpty()) {
                            p.moveTo(offsets[0].x, offsets[0].y)
                            for (i in 1 until offsets.size) {
                                p.lineTo(offsets[i].x, offsets[i].y)
                            }
                        }
                        return p
                    }

                    when (type) {
                        "Draw" -> MarkupAction.Draw(pageIdx, buildPath(offsets), offsets, color, strokeW)
                        "Highlight" -> MarkupAction.Highlight(pageIdx, buildPath(offsets), offsets, color, strokeW)
                        "Erase" -> MarkupAction.Erase(pageIdx, buildPath(offsets), offsets, strokeW)
                        "Arrow" -> MarkupAction.Arrow(pageIdx, start, end, color, strokeW)
                        "Rect" -> MarkupAction.Rectangle(pageIdx, start, end, color, strokeW)
                        "Cloud" -> MarkupAction.Cloud(pageIdx, start, end, color, strokeW)
                        "TextNote" -> MarkupAction.TextNote(pageIdx, text, start, color, strokeW)
                        "Stamp" -> MarkupAction.Stamp(pageIdx, stampType, start)
                        "Dimension" -> MarkupAction.Dimension(pageIdx, start, end, distStr, color)
                        else -> null
                    }
                } catch (_: Exception) { null }
            }
            actions.clear()
            actions.addAll(loadedActions)
        }
    }

    val isPdf = projectFile?.name?.lowercase()?.endsWith(".pdf") == true
    val pdfRenderer = if (isPdf) rememberPdfRenderer(projectFile?.url ?: "") else null
    val currentPdfPage = remember(pdfRenderer, currentPageIndex) {
        pdfRenderer?.renderPage(currentPageIndex)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Column {
                        Text(projectFile?.name ?: "Blueprint Studio", style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (isPdf && pdfRenderer != null) "Page ${currentPageIndex + 1}/${pdfRenderer.pageCount} | Scale: $scalePresetName" else "Scale: $scalePresetName",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // Rotate Document
                    IconButton(onClick = { rotationDegrees = (rotationDegrees + 90f) % 360f }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Rotate 90°")
                    }
                    // Calibration Scale Button
                    IconButton(onClick = { showScaleDialog = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "Scale")
                    }
                    // Undo Button
                    IconButton(onClick = { 
                        val lastOnPage = actions.findLast { it.pageIndex == currentPageIndex }
                        if (lastOnPage != null) actions.remove(lastOnPage)
                    }) {
                        Icon(Icons.Default.Build, contentDescription = "Undo")
                    }
                    TextButton(onClick = { actions.removeAll { it.pageIndex == currentPageIndex } }) {
                        Text("Clear", color = MaterialTheme.colorScheme.error)
                    }
                }
            )
        },
        bottomBar = {
            Column(modifier = Modifier.background(MaterialTheme.colorScheme.surface)) {
                // PDF Multi-Page Controls
                if (isPdf && pdfRenderer != null && pdfRenderer.pageCount > 1) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { if (currentPageIndex > 0) currentPageIndex-- }, enabled = currentPageIndex > 0) {
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Prev Page")
                        }
                        Text("Page ${currentPageIndex + 1} of ${pdfRenderer.pageCount}", style = MaterialTheme.typography.labelMedium)
                        IconButton(onClick = { if (currentPageIndex < pdfRenderer.pageCount - 1) currentPageIndex++ }, enabled = currentPageIndex < pdfRenderer.pageCount - 1) {
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next Page")
                        }
                    }
                }

                // Tool Options Row (Stroke Thickness & Zoom controls)
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Size:", style = MaterialTheme.typography.labelSmall)
                    Slider(
                        value = strokeWidth,
                        onValueChange = { strokeWidth = it },
                        valueRange = 2f..40f,
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                    )
                    IconButton(onClick = { zoomScale = (zoomScale * 1.25f).coerceAtMost(5f) }) {
                        Icon(Icons.Default.Add, contentDescription = "Zoom In")
                    }
                    IconButton(onClick = { zoomScale = (zoomScale / 1.25f).coerceAtLeast(1f) }) {
                        Icon(Icons.Default.Menu, contentDescription = "Zoom Out")
                    }
                    IconButton(onClick = { zoomScale = 1f; zoomOffset = Offset.Zero; rotationDegrees = 0f }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Reset View")
                    }
                }

                // Primary Architectural Tools Bar
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    ToolChip("Pen", toolMode == "Pen", Icons.Default.Edit) { toolMode = "Pen" }
                    ToolChip("Highlight", toolMode == "Highlight", Icons.Default.Star) { toolMode = "Highlight" }
                    ToolChip("Arrow", toolMode == "Arrow", Icons.Default.PlayArrow) { toolMode = "Arrow" }
                    ToolChip("Rect", toolMode == "Rect", Icons.Default.Place) { toolMode = "Rect" }
                    ToolChip("Cloud", toolMode == "Cloud", Icons.Default.AccountBox) { toolMode = "Cloud" }
                    ToolChip("Text", toolMode == "Text", Icons.Default.Add) { toolMode = "Text" }
                    ToolChip("Stamp", toolMode == "Stamp", Icons.Default.CheckCircle) { 
                        toolMode = "Stamp"
                        showStampMenu = true
                    }
                    ToolChip("Measure", toolMode == "Measure", Icons.Default.Info) { toolMode = "Measure" }
                    ToolChip("Pan", toolMode == "Pan", Icons.Default.Lock) { toolMode = "Pan" }
                    ToolChip("Eraser", toolMode == "Eraser", Icons.Default.Delete) { toolMode = "Eraser" }

                    Spacer(modifier = Modifier.width(8.dp))

                    ColorButton(Color.Red, selectedColor) { selectedColor = Color.Red }
                    ColorButton(Color.Yellow, selectedColor) { selectedColor = Color.Yellow }
                    ColorButton(Color.Green, selectedColor) { selectedColor = Color.Green }
                    ColorButton(Color.Cyan, selectedColor) { selectedColor = Color.Cyan }
                    ColorButton(Color.Magenta, selectedColor) { selectedColor = Color.Magenta }
                    ColorButton(Color.Black, selectedColor) { selectedColor = Color.Black }
                }
            }
        },
        floatingActionButton = {
            FloatingActionButton(onClick = {
                if (isSaving || projectFile == null) return@FloatingActionButton
                scope.launch {
                    isSaving = true
                    try {
                        val markupsColl = firestore.collection("projects").document(projectId).collection("files").document(fileId).collection("markups")
                        actions.forEach { action ->
                            val map = mutableMapOf<String, Any?>()
                            map["pageIndex"] = action.pageIndex
                            when (action) {
                                is MarkupAction.Draw -> {
                                    map["type"] = "Draw"
                                    map["colorHex"] = "#" + action.color.toArgb().toUInt().toString(16)
                                    map["strokeWidth"] = action.strokeWidth.toDouble()
                                    map["points"] = action.points.flatMap { listOf(it.x.toDouble(), it.y.toDouble()) }
                                }
                                is MarkupAction.Highlight -> {
                                    map["type"] = "Highlight"
                                    map["colorHex"] = "#" + action.color.toArgb().toUInt().toString(16)
                                    map["strokeWidth"] = action.strokeWidth.toDouble()
                                    map["points"] = action.points.flatMap { listOf(it.x.toDouble(), it.y.toDouble()) }
                                }
                                is MarkupAction.Erase -> {
                                    map["type"] = "Erase"
                                    map["strokeWidth"] = action.strokeWidth.toDouble()
                                    map["points"] = action.points.flatMap { listOf(it.x.toDouble(), it.y.toDouble()) }
                                }
                                is MarkupAction.Arrow -> {
                                    map["type"] = "Arrow"
                                    map["startX"] = action.start.x.toDouble()
                                    map["startY"] = action.start.y.toDouble()
                                    map["endX"] = action.end.x.toDouble()
                                    map["endY"] = action.end.y.toDouble()
                                    map["colorHex"] = "#" + action.color.toArgb().toUInt().toString(16)
                                    map["strokeWidth"] = action.strokeWidth.toDouble()
                                }
                                is MarkupAction.Rectangle -> {
                                    map["type"] = "Rect"
                                    map["startX"] = action.start.x.toDouble()
                                    map["startY"] = action.start.y.toDouble()
                                    map["endX"] = action.end.x.toDouble()
                                    map["endY"] = action.end.y.toDouble()
                                    map["colorHex"] = "#" + action.color.toArgb().toUInt().toString(16)
                                    map["strokeWidth"] = action.strokeWidth.toDouble()
                                }
                                is MarkupAction.Cloud -> {
                                    map["type"] = "Cloud"
                                    map["startX"] = action.start.x.toDouble()
                                    map["startY"] = action.start.y.toDouble()
                                    map["endX"] = action.end.x.toDouble()
                                    map["endY"] = action.end.y.toDouble()
                                    map["colorHex"] = "#" + action.color.toArgb().toUInt().toString(16)
                                    map["strokeWidth"] = action.strokeWidth.toDouble()
                                }
                                is MarkupAction.TextNote -> {
                                    map["type"] = "TextNote"
                                    map["text"] = action.text
                                    map["startX"] = action.position.x.toDouble()
                                    map["startY"] = action.position.y.toDouble()
                                    map["colorHex"] = "#" + action.color.toArgb().toUInt().toString(16)
                                    map["strokeWidth"] = action.size.toDouble()
                                }
                                is MarkupAction.Stamp -> {
                                    map["type"] = "Stamp"
                                    map["stampType"] = action.stampType
                                    map["startX"] = action.position.x.toDouble()
                                    map["startY"] = action.position.y.toDouble()
                                }
                                is MarkupAction.Dimension -> {
                                    map["type"] = "Dimension"
                                    map["startX"] = action.start.x.toDouble()
                                    map["startY"] = action.start.y.toDouble()
                                    map["endX"] = action.end.x.toDouble()
                                    map["endY"] = action.end.y.toDouble()
                                    map["distanceStr"] = action.distanceStr
                                    map["colorHex"] = "#" + action.color.toArgb().toUInt().toString(16)
                                }
                            }
                            markupsColl.add(map)
                        }

                        val markupFile = ProjectFile(
                            name = "Redlined_${projectFile!!.name}",
                            url = projectFile!!.url,
                            uploadedBy = auth.currentUser?.email ?: "Admin",
                            uploadedAt = Clock.System.now().toEpochMilliseconds()
                        )
                        firestore.collection("projects").document(projectId).collection("files").add(markupFile.toFirestoreMap())
                        onSaveSuccess()
                    } catch (_: Exception) {
                    } finally {
                        isSaving = false
                    }
                }
            }) {
                if (isSaving) CircularProgressIndicator(modifier = Modifier.size(24.dp))
                else Icon(Icons.Default.Check, contentDescription = "Save Plan")
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(Color(0xFFDCDCDC))
        ) {
            if (projectFile == null) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer(
                            scaleX = zoomScale,
                            scaleY = zoomScale,
                            translationX = zoomOffset.x,
                            translationY = zoomOffset.y,
                            rotationZ = rotationDegrees
                        )
                        .drawWithContent {
                            graphicsLayer.record {
                                this@drawWithContent.drawContent()
                            }
                            drawLayer(graphicsLayer)
                        }
                ) {
                    // Blueprint Background Layer
                    if (currentPdfPage != null) {
                        Image(
                            bitmap = currentPdfPage,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit
                        )
                    } else if (projectFile?.url?.isNotEmpty() == true) {
                        AsyncImage(
                            model = ImageRequest.Builder(LocalPlatformContext.current)
                                .data(projectFile!!.url)
                                .crossfade(true)
                                .build(),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit
                        )
                    } else {
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                    }

                    // Interactive Drawing & Annotation Canvas Layer
                    Canvas(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(toolMode, currentPageIndex, selectedStamp, pixelsPerFoot) {
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
                                            } else if (toolMode == "Stamp") {
                                                actions.add(MarkupAction.Stamp(currentPageIndex, selectedStamp, offset))
                                            } else if (toolMode in listOf("Arrow", "Rect", "Cloud", "Measure")) {
                                                startOffset = offset
                                                currentDragOffset = offset
                                            } else {
                                                currentPath = Path().apply { moveTo(offset.x, offset.y) }
                                            }
                                        },
                                        onDrag = { change, _ ->
                                            currentPoints.add(change.position)
                                            if (toolMode in listOf("Arrow", "Rect", "Cloud", "Measure")) {
                                                currentDragOffset = change.position
                                            } else if (toolMode in listOf("Pen", "Highlight", "Eraser")) {
                                                currentPath?.lineTo(change.position.x, change.position.y)
                                                val p = currentPath
                                                currentPath = null
                                                currentPath = p
                                            }
                                        },
                                        onDragEnd = {
                                            if (toolMode in listOf("Arrow", "Rect", "Cloud", "Measure") && startOffset != null && currentDragOffset != null) {
                                                val s = startOffset!!
                                                val e = currentDragOffset!!
                                                when (toolMode) {
                                                    "Arrow" -> actions.add(MarkupAction.Arrow(currentPageIndex, s, e, selectedColor, strokeWidth))
                                                    "Rect" -> actions.add(MarkupAction.Rectangle(currentPageIndex, s, e, selectedColor, strokeWidth))
                                                    "Cloud" -> actions.add(MarkupAction.Cloud(currentPageIndex, s, e, selectedColor, strokeWidth))
                                                    "Measure" -> {
                                                        val distPx = hypot(e.x - s.x, e.y - s.y).toDouble()
                                                        val feet = distPx / pixelsPerFoot
                                                        val ftInt = feet.toInt()
                                                        val inInt = ((feet - ftInt) * 12.0).roundToInt()
                                                        val label = "$ftInt'-$inInt\""
                                                        actions.add(MarkupAction.Dimension(currentPageIndex, s, e, label, selectedColor))
                                                    }
                                                }
                                            } else {
                                                currentPath?.let { path ->
                                                    val pts = currentPoints.toList()
                                                    when (toolMode) {
                                                        "Eraser" -> actions.add(MarkupAction.Erase(currentPageIndex, path, pts, strokeWidth))
                                                        "Highlight" -> actions.add(MarkupAction.Highlight(currentPageIndex, path, pts, selectedColor.copy(alpha = 0.4f), strokeWidth * 2.5f))
                                                        else -> actions.add(MarkupAction.Draw(currentPageIndex, path, pts, selectedColor, strokeWidth))
                                                    }
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
                        // Render Committed Actions for Current Page
                        actions.filter { it.pageIndex == currentPageIndex }.forEach { action ->
                            when (action) {
                                is MarkupAction.Draw -> drawPath(action.path, action.color, style = Stroke(width = action.strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round))
                                is MarkupAction.Highlight -> drawPath(action.path, action.color, style = Stroke(width = action.strokeWidth, cap = StrokeCap.Square, join = StrokeJoin.Bevel))
                                is MarkupAction.Erase -> drawPath(action.path, Color(0xFFDCDCDC), style = Stroke(width = action.strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round))
                                is MarkupAction.Arrow -> drawArrow(action.start, action.end, action.color, action.strokeWidth)
                                is MarkupAction.Rectangle -> drawRect(action.color, action.start, Size(action.end.x - action.start.x, action.end.y - action.start.y), style = Stroke(action.strokeWidth))
                                is MarkupAction.Cloud -> drawRevisionCloud(action.start, action.end, action.color, action.strokeWidth)
                                is MarkupAction.Dimension -> drawDimensionLine(action.start, action.end, action.distanceStr, action.color)
                                else -> {}
                            }
                        }

                        // Render Active Drag Preview
                        if (startOffset != null && currentDragOffset != null) {
                            val s = startOffset!!
                            val e = currentDragOffset!!
                            when (toolMode) {
                                "Arrow" -> drawArrow(s, e, selectedColor, strokeWidth)
                                "Rect" -> drawRect(selectedColor, s, Size(e.x - s.x, e.y - s.y), style = Stroke(strokeWidth))
                                "Cloud" -> drawRevisionCloud(s, e, selectedColor, strokeWidth)
                                "Measure" -> {
                                    val distPx = hypot(e.x - s.x, e.y - s.y).toDouble()
                                    val feet = distPx / pixelsPerFoot
                                    val ftInt = feet.toInt()
                                    val inInt = ((feet - ftInt) * 12.0).roundToInt()
                                    drawDimensionLine(s, e, "$ftInt'-$inInt\"", selectedColor)
                                }
                            }
                        }

                        // Render Active Pen/Eraser Path
                        currentPath?.let { path ->
                            drawPath(
                                path = path,
                                color = if (toolMode == "Eraser") Color.White.copy(alpha = 0.6f) else if (toolMode == "Highlight") selectedColor.copy(alpha = 0.4f) else selectedColor,
                                style = Stroke(width = if (toolMode == "Highlight") strokeWidth * 2.5f else strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
                            )
                        }
                    }

                    // Render Text Notes, Stamps, and Dimension Badges Overlay
                    val density = LocalDensity.current
                    actions.filter { it.pageIndex == currentPageIndex }.forEach { action ->
                        when (action) {
                            is MarkupAction.TextNote -> {
                                Box(
                                    modifier = Modifier
                                        .offset(x = with(density) { action.position.x.toDp() }, y = with(density) { action.position.y.toDp() })
                                        .background(Color.White.copy(alpha = 0.95f), RoundedCornerShape(4.dp))
                                        .border(2.dp, action.color, RoundedCornerShape(4.dp))
                                        .padding(6.dp)
                                ) {
                                    Text(action.text, color = Color.Black, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                            is MarkupAction.Stamp -> {
                                val (bg, text) = when (action.stampType) {
                                    "APPROVED" -> Color(0xFF2E7D32) to "APPROVED"
                                    "REJECTED" -> Color(0xFFC62828) to "REJECTED"
                                    "REVISED" -> Color(0xFFE65100) to "REVISED"
                                    "PUNCH LIST" -> Color(0xFF6A1B9A) to "PUNCH LIST"
                                    else -> Color(0xFF1565C0) to "FOR REVIEW"
                                }
                                Box(
                                    modifier = Modifier
                                        .offset(x = with(density) { action.position.x.toDp() }, y = with(density) { action.position.y.toDp() })
                                        .border(3.dp, bg, RoundedCornerShape(6.dp))
                                        .background(bg.copy(alpha = 0.15f), RoundedCornerShape(6.dp))
                                        .padding(horizontal = 12.dp, vertical = 6.dp)
                                ) {
                                    Text(text, color = bg, fontSize = 16.sp, fontWeight = FontWeight.Black)
                                }
                            }
                            is MarkupAction.Dimension -> {
                                val midX = (action.start.x + action.end.x) / 2f
                                val midY = (action.start.y + action.end.y) / 2f
                                Box(
                                    modifier = Modifier
                                        .offset(x = with(density) { midX.toDp() - 24.dp }, y = with(density) { midY.toDp() - 14.dp })
                                        .background(Color.Yellow, RoundedCornerShape(4.dp))
                                        .border(1.5.dp, Color.Black, RoundedCornerShape(4.dp))
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(action.distanceStr, color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.Black)
                                }
                            }
                            else -> {}
                        }
                    }

                    // Live Drag Preview Badge for Measure Tool
                    if (toolMode == "Measure" && startOffset != null && currentDragOffset != null) {
                        val s = startOffset!!
                        val e = currentDragOffset!!
                        val midX = (s.x + e.x) / 2f
                        val midY = (s.y + e.y) / 2f
                        val distPx = hypot(e.x - s.x, e.y - s.y).toDouble()
                        val feet = distPx / pixelsPerFoot
                        val ftInt = feet.toInt()
                        val inInt = ((feet - ftInt) * 12.0).roundToInt()
                        val liveText = "$ftInt'-$inInt\""

                        Box(
                            modifier = Modifier
                                .offset(x = with(density) { midX.toDp() - 24.dp }, y = with(density) { midY.toDp() - 14.dp })
                                .background(Color.Yellow, RoundedCornerShape(4.dp))
                                .border(1.5.dp, Color.Black, RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(liveText, color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.Black)
                        }
                    }
                }
            }
        }

        // Add Text Callout Dialog
        if (showTextDialog) {
            AddTextDialog(
                onDismiss = { showTextDialog = false },
                onConfirm = { content ->
                    if (content.isNotEmpty()) {
                        actions.add(MarkupAction.TextNote(currentPageIndex, content, tempTextPos, selectedColor, strokeWidth + 10))
                    }
                    showTextDialog = false
                }
            )
        }

        // Stamp Picker Sheet
        if (showStampMenu) {
            AlertDialog(
                onDismissRequest = { showStampMenu = false },
                title = { Text("Select Architectural Stamp") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("APPROVED", "REJECTED", "REVISED", "FOR REVIEW", "PUNCH LIST").forEach { stamp ->
                            Button(
                                onClick = {
                                    selectedStamp = stamp
                                    showStampMenu = false
                                },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (selectedStamp == stamp) MaterialTheme.colorScheme.primary else Color.LightGray
                                )
                            ) {
                                Text(stamp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                },
                confirmButton = {}
            )
        }

        // Calibration Scale Dialog
        if (showScaleDialog) {
            AlertDialog(
                onDismissRequest = { showScaleDialog = false },
                title = { Text("Drawing Scale Calibration") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Select standard architectural drawing scale:")
                        listOf(
                            "1/4\" = 1'-0\"" to 40.0,
                            "1/8\" = 1'-0\"" to 20.0,
                            "1/2\" = 1'-0\"" to 80.0,
                            "1\" = 10'-0\"" to 12.0
                        ).forEach { (preset, pxPerFt) ->
                            OutlinedButton(
                                onClick = {
                                    pixelsPerFoot = pxPerFt
                                    scalePresetName = preset
                                    showScaleDialog = false
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(preset, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showScaleDialog = false }) { Text("Close") }
                }
            )
        }
    }
}

// Canvas Vector Drawing Helpers
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawArrow(start: Offset, end: Offset, color: Color, strokeWidth: Float) {
    drawLine(color, start, end, strokeWidth = strokeWidth, cap = StrokeCap.Round)
    val angle = atan2(end.y - start.y, end.x - start.x)
    val arrowLen = (strokeWidth * 3.5f).coerceAtLeast(18f)
    val wing1 = Offset(end.x - arrowLen * cos(angle - PI / 6).toFloat(), end.y - arrowLen * sin(angle - PI / 6).toFloat())
    val wing2 = Offset(end.x - arrowLen * cos(angle + PI / 6).toFloat(), end.y - arrowLen * sin(angle + PI / 6).toFloat())
    drawLine(color, end, wing1, strokeWidth = strokeWidth, cap = StrokeCap.Round)
    drawLine(color, end, wing2, strokeWidth = strokeWidth, cap = StrokeCap.Round)
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawRevisionCloud(start: Offset, end: Offset, color: Color, strokeWidth: Float) {
    val left = min(start.x, end.x)
    val top = min(start.y, end.y)
    val width = abs(end.x - start.x)
    val height = abs(end.y - start.y)
    if (width <= 0 || height <= 0) return

    val cloudPath = Path()
    val arcRadius = 15f
    var curX = left
    while (curX < left + width) {
        cloudPath.addArc(Rect(curX, top - arcRadius, curX + (arcRadius * 2), top + arcRadius), 180f, 180f)
        curX += arcRadius * 1.5f
    }
    var curY = top
    while (curY < top + height) {
        cloudPath.addArc(Rect(left + width - arcRadius, curY, left + width + arcRadius, curY + (arcRadius * 2)), 270f, 180f)
        curY += arcRadius * 1.5f
    }
    drawPath(cloudPath, color, style = Stroke(strokeWidth))
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawDimensionLine(start: Offset, end: Offset, label: String, color: Color) {
    drawLine(color, start, end, strokeWidth = 3f, cap = StrokeCap.Round)
    drawCircle(color, radius = 6f, center = start)
    drawCircle(color, radius = 6f, center = end)
}

@Composable
fun ToolChip(label: String, selected: Boolean, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, fontSize = 11.sp, fontWeight = FontWeight.Bold) },
        leadingIcon = { Icon(icon, contentDescription = label, modifier = Modifier.size(14.dp)) }
    )
}

@Composable
fun ColorButton(color: Color, selected: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(color)
            .border(
                width = if (color == selected) 3.dp else 1.dp,
                color = if (color == selected) MaterialTheme.colorScheme.primary else Color.Gray,
                shape = CircleShape
            )
            .clickable { onClick() }
    )
}

@Composable
fun AddTextDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Plan Callout Note") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("Note / Callout Text") },
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            Button(onClick = { onConfirm(text) }) { Text("Add Callout") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
