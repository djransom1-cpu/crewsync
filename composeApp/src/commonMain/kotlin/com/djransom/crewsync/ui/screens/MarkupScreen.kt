package com.djransom.crewsync.ui.screens

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
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.djransom.crewsync.data.model.ProjectFile
import com.djransom.crewsync.util.*
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.firestore.firestore
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.math.*

sealed class MarkupAction {
    abstract val pageIndex: Int
    data class Draw(override val pageIndex: Int, val path: Path, val points: List<Offset>, val color: Color, val strokeWidth: Float) : MarkupAction()
    data class Highlight(override val pageIndex: Int, val path: Path, val points: List<Offset>, val color: Color, val strokeWidth: Float) : MarkupAction()
    data class Erase(override val pageIndex: Int, val path: Path, val points: List<Offset>, val strokeWidth: Float) : MarkupAction()
    data class Arrow(override val pageIndex: Int, val start: Offset, val end: Offset, val color: Color, val strokeWidth: Float) : MarkupAction()
    data class Line(override val pageIndex: Int, val start: Offset, val end: Offset, val color: Color, val strokeWidth: Float) : MarkupAction()
    data class Rectangle(override val pageIndex: Int, val start: Offset, val end: Offset, val color: Color, val strokeWidth: Float) : MarkupAction()
    data class Ellipse(override val pageIndex: Int, val start: Offset, val end: Offset, val color: Color, val strokeWidth: Float) : MarkupAction()
    data class Cloud(override val pageIndex: Int, val start: Offset, val end: Offset, val color: Color, val strokeWidth: Float) : MarkupAction()
    data class Curve(override val pageIndex: Int, val path: Path, val points: List<Offset>, val color: Color, val strokeWidth: Float) : MarkupAction()
    data class TextNote(override val pageIndex: Int, val text: String, val position: Offset, val color: Color, val size: Float, val transparent: Boolean = false) : MarkupAction()
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
    val scope = rememberCoroutineScope()
    val textMeasurer = rememberTextMeasurer()
    val actions = remember { mutableStateListOf<MarkupAction>() }
    val currentPoints = remember { mutableStateListOf<Offset>() }
    val arcPoints = remember { mutableStateListOf<Offset>() }
    val graphicsLayer = rememberGraphicsLayer()
    
    var projectFile by remember { mutableStateOf<ProjectFile?>(null) }
    var currentPath by remember { mutableStateOf<Path?>(null) }
    var startOffset by remember { mutableStateOf<Offset?>(null) }
    var currentDragOffset by remember { mutableStateOf<Offset?>(null) }

    var selectedColor by remember { mutableStateOf(Color.Red) }
    var strokeWidth by remember { mutableStateOf(8f) }
    var toolMode by remember { mutableStateOf("Pen") } // Pen, Highlight, Arrow, Rect, Cloud, Text, Stamp, Measure, Pan, Eraser
    LaunchedEffect(toolMode) { arcPoints.clear() }

    var selectedStamp by remember { mutableStateOf("APPROVED") }
    var showStampMenu by remember { mutableStateOf(false) }
    var showTextDialog by remember { mutableStateOf(false) }
    var showScaleDialog by remember { mutableStateOf(false) }
    var showCalibrationInputDialog by remember { mutableStateOf(false) }
    var calibrationPixelDistance by remember { mutableStateOf(0.0) }

    // Move tool: the action currently grabbed (by reference, not value - two visually
    // identical shapes are otherwise indistinguishable) and how far it's been dragged so far
    // this gesture. Committed into `actions` only on release.
    var movingAction by remember { mutableStateOf<MarkupAction?>(null) }
    var moveDragOffset by remember { mutableStateOf(Offset.Zero) }
    // >= 0 when the Move tool grabbed one specific anchor of a 3-point Curve (an Arc) rather
    // than the whole shape, so a mistapped point can be nudged instead of redrawing the curve.
    var movingPointIndex by remember { mutableStateOf(-1) }

    // The pixel size of the on-screen drawing surface, captured live via onSizeChanged below.
    // Markup coordinates are recorded relative to THIS (whatever the window/viewport happened
    // to be while drawing), which is very unlikely to match the PDF page's native rendered
    // resolution (capped/scaled independently by PdfRenderer) - exporting a flattened page
    // needs to remap through this to land markups in the right place instead of off in a
    // corner at the wrong scale.
    var canvasSizePx by remember { mutableStateOf(IntSize.Zero) }
    var tempTextPos by remember { mutableStateOf(Offset.Zero) }
    var isSaving by remember { mutableStateOf(false) }
    var isExporting by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    // Saving deletes the old markup docs and re-adds the current ones (see the FAB below) - the
    // live listener a few lines down would otherwise see that intermediate empty state and
    // clear the on-screen work before the re-add even finishes. Suppressed for the duration of
    // a save; re-enabled after, when the listener's next snapshot will already match what's on
    // screen.
    var suppressMarkupSync by remember { mutableStateOf(false) }

    // Page Rotation, Zoom, & Scale
    var currentPageIndex by remember { mutableStateOf(0) }
    var rotationDegrees by remember { mutableStateOf(0f) }
    var zoomScale by remember { mutableStateOf(1f) }
    var zoomOffset by remember { mutableStateOf(Offset.Zero) }

    // Calibration: Pixels per Foot (Default 40.0 px = 1 ft)
    var pixelsPerFoot by remember { mutableStateOf(40.0) }
    var scalePresetName by remember { mutableStateOf("1/4\" = 1'-0\"") }

    LaunchedEffect(fileId) {
        // Fallback initialization to prevent blank screen hangs
        projectFile = ProjectFile(
            id = fileId,
            name = if (fileId.contains("/")) fileId.substringAfterLast("/") else "Blueprint Plan",
            url = if (fileId.startsWith("http")) fileId else ""
        )

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
            println("MARKUP SYNC: snapshot for fileId=$fileId has ${snap.documents.size} doc(s), suppressed=$suppressMarkupSync")
            val loadedActions = snap.documents.mapNotNull { doc ->
                try {
                    // Every field is read with its exact concrete stored type (Double for
                    // numbers, String for text) rather than Any/Any? - kotlinx.serialization
                    // (which GitLive's Firestore client decodes through) has no serializer for
                    // a bare Any, so `get<Any?>(...)` throws on every call, silently failing
                    // every single markup on load even though the save succeeded.
                    val pageIdx = try { doc.get<Double>("pageIndex").toInt() } catch (_: Exception) { 0 }
                    val type = doc.get<String>("type")
                    val colorHex = try { doc.get<String>("colorHex") } catch (_: Exception) { "#FF0000" }
                    val strokeW = try { doc.get<Double>("strokeWidth").toFloat() } catch (_: Exception) { 8f }
                    val color = try { Color(colorHex.removePrefix("#").toLong(16) or 0xFF000000) } catch (_: Exception) { Color.Red }

                    val startX = try { doc.get<Double>("startX").toFloat() } catch (_: Exception) { 0f }
                    val startY = try { doc.get<Double>("startY").toFloat() } catch (_: Exception) { 0f }
                    val endX = try { doc.get<Double>("endX").toFloat() } catch (_: Exception) { 0f }
                    val endY = try { doc.get<Double>("endY").toFloat() } catch (_: Exception) { 0f }
                    val start = Offset(startX, startY)
                    val end = Offset(endX, endY)

                    val text = try { doc.get<String>("text") } catch (_: Exception) { "" }
                    val transparent = try { doc.get<Boolean>("transparent") } catch (_: Exception) { false }
                    val stampType = try { doc.get<String>("stampType") } catch (_: Exception) { "APPROVED" }
                    val distStr = try { doc.get<String>("distanceStr") } catch (_: Exception) { "" }
                    val pts = try { doc.get<List<Double>>("points").map { it.toFloat() } } catch (_: Exception) { emptyList() }
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
                        "Line" -> MarkupAction.Line(pageIdx, start, end, color, strokeW)
                        "Rect" -> MarkupAction.Rectangle(pageIdx, start, end, color, strokeW)
                        "Ellipse" -> MarkupAction.Ellipse(pageIdx, start, end, color, strokeW)
                        "Cloud" -> MarkupAction.Cloud(pageIdx, start, end, color, strokeW)
                        "Curve" -> MarkupAction.Curve(pageIdx, buildSmoothPath(offsets), offsets, color, strokeW)
                        "TextNote" -> MarkupAction.TextNote(pageIdx, text, start, color, strokeW, transparent)
                        "Stamp" -> MarkupAction.Stamp(pageIdx, stampType, start)
                        "Dimension" -> MarkupAction.Dimension(pageIdx, start, end, distStr, color)
                        else -> {
                            println("MARKUP SYNC: doc ${doc.id} has unrecognized type=$type")
                            null
                        }
                    }
                } catch (e: Exception) {
                    println("MARKUP SYNC: failed to parse doc ${doc.id}: $e")
                    null
                }
            }
            println("MARKUP SYNC: parsed ${loadedActions.size}/${snap.documents.size} doc(s) into actions")
            if (!suppressMarkupSync) {
                actions.clear()
                actions.addAll(loadedActions)
            }
        }
    }

    val isPdf = projectFile?.name?.lowercase()?.endsWith(".pdf") == true
    val pdfRenderer = if (isPdf) rememberPdfRenderer(projectFile?.url ?: "") else null
    val currentPdfPage = remember(pdfRenderer, currentPageIndex) {
        pdfRenderer?.renderPage(currentPageIndex)
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            Column(modifier = Modifier.background(MaterialTheme.colorScheme.surface)) {
                TopAppBar(
                    title = {
                        Column {
                            Text(projectFile?.name ?: "Blueprint Redline", style = MaterialTheme.typography.titleMedium, maxLines = 1)
                            Text("Scale: $scalePresetName", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
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
                        // Export Redlined PDF - bakes every page's markups into a real PDF you
                        // can open outside the app; only available where a PDF is loaded and
                        // exportMarkedUpPdf is actually implemented for this platform.
                        if (isPdf && pdfRenderer != null) {
                            IconButton(
                                enabled = !isExporting,
                                onClick = {
                                    scope.launch {
                                        isExporting = true
                                        println("MARKUP EXPORT: starting, canvasSizePx=$canvasSizePx, totalActions=${actions.size}")
                                        try {
                                            val renderer = pdfRenderer
                                            val pageBitmaps = (0 until renderer.pageCount).mapNotNull { idx ->
                                                val bg = renderer.renderPage(idx) ?: return@mapNotNull null
                                                val rawPageActions = actions.filter { it.pageIndex == idx }
                                                val pageActions = rawPageActions
                                                    .map { remapActionToExportSpace(it, canvasSizePx, bg.width, bg.height) }
                                                println("MARKUP EXPORT: page $idx bgSize=${bg.width}x${bg.height} rawActions=${rawPageActions.size} remappedActions=${pageActions.size}")
                                                flattenPage(bg, pageActions, bg.width, bg.height, textMeasurer)
                                            }
                                            println("MARKUP EXPORT: flattened ${pageBitmaps.size} page(s)")
                                            val safeName = (projectFile?.name ?: "file.pdf")
                                            val storagePath = "projects/$projectId/redlined_${Clock.System.now().toEpochMilliseconds()}_$safeName"
                                            val url = exportMarkedUpPdf(storagePath, pageBitmaps)
                                            println("MARKUP EXPORT: upload result url=$url")
                                            if (url != null) {
                                                val exportedFile = ProjectFile(
                                                    name = "Redlined_$safeName",
                                                    url = url,
                                                    uploadedBy = "Markup Export",
                                                    uploadedAt = Clock.System.now().toEpochMilliseconds()
                                                )
                                                firestore.collection("projects").document(projectId).collection("files").add(exportedFile.toFirestoreMap())
                                                snackbarHostState.showSnackbar("Exported Redlined_$safeName")
                                            } else {
                                                snackbarHostState.showSnackbar("PDF export isn't available on this platform yet")
                                            }
                                        } catch (e: Exception) {
                                            e.printStackTrace()
                                            snackbarHostState.showSnackbar("Export failed: ${e.message ?: "unknown error"}")
                                        } finally {
                                            isExporting = false
                                        }
                                    }
                                }
                            ) {
                                if (isExporting) CircularProgressIndicator(modifier = Modifier.size(20.dp))
                                else Icon(Icons.Default.DateRange, contentDescription = "Export Redlined PDF")
                            }
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

                // Primary Architectural Tools Bar (Safely at Top)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    ToolChip("Pen", toolMode == "Pen", Icons.Default.Edit) { toolMode = "Pen" }
                    ToolChip("Curve", toolMode == "Curve", Icons.Default.Refresh) { toolMode = "Curve" }
                    ToolChip("Arc", toolMode == "Arc", Icons.Default.Info) { toolMode = "Arc" }
                    ToolChip("Highlight", toolMode == "Highlight", Icons.Default.Star) { toolMode = "Highlight" }
                    ToolChip("Line", toolMode == "Line", Icons.Default.Menu) { toolMode = "Line" }
                    ToolChip("Arrow", toolMode == "Arrow", Icons.Default.PlayArrow) { toolMode = "Arrow" }
                    ToolChip("Rect", toolMode == "Rect", Icons.Default.Place) { toolMode = "Rect" }
                    ToolChip("Circle", toolMode == "Circle", Icons.Default.Info) { toolMode = "Circle" }
                    ToolChip("Cloud", toolMode == "Cloud", Icons.Default.AccountBox) { toolMode = "Cloud" }
                    ToolChip("Text", toolMode == "Text", Icons.Default.Add) { toolMode = "Text" }
                    ToolChip("Stamp", toolMode == "Stamp", Icons.Default.CheckCircle) { 
                        toolMode = "Stamp"
                        showStampMenu = true
                    }
                    ToolChip("Measure", toolMode == "Measure", Icons.Default.Info) { toolMode = "Measure" }
                    ToolChip("Calibrate", toolMode == "Calibrate", Icons.Default.Info) { toolMode = "Calibrate" }
                    ToolChip("Move", toolMode == "Move", Icons.Default.Build) { toolMode = "Move" }
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

                HorizontalDivider()
            }
        },
        bottomBar = {
            Column(
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.surface)
                    .navigationBarsPadding()
                    .padding(bottom = 8.dp)
            ) {
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
                    Spacer(modifier = Modifier.width(6.dp))
                    listOf(3f, 8f, 16f, 28f).forEach { presetWidth ->
                        StrokeWidthButton(presetWidth, strokeWidth == presetWidth) { strokeWidth = presetWidth }
                        Spacer(modifier = Modifier.width(4.dp))
                    }
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
            }
        },
        floatingActionButton = {
            FloatingActionButton(onClick = {
                if (isSaving || projectFile == null) return@FloatingActionButton
                scope.launch {
                    isSaving = true
                    suppressMarkupSync = true
                    try {
                        val markupsColl = firestore.collection("projects").document(projectId).collection("files").document(fileId).collection("markups")

                        // Snapshot to an immutable list before any suspending calls - `actions`
                        // is live-updated by this screen's own Firestore listener, and the loop
                        // below awaits a network write per item, so a snapshot arriving mid-save
                        // could otherwise clear/replace the very list this loop is reading from.
                        val actionsToSave = actions.toList()
                        println("MARKUP SAVE: starting, ${actionsToSave.size} action(s) to save for fileId=$fileId")

                        // Replace rather than append: since `actions` already includes markups
                        // loaded from a previous save, blindly re-adding them every time would
                        // duplicate everything already saved on every subsequent save.
                        val existingDocs = markupsColl.get().documents
                        println("MARKUP SAVE: found ${existingDocs.size} existing doc(s) to delete")
                        existingDocs.forEach { doc -> doc.reference.delete() }
                        println("MARKUP SAVE: delete pass complete")

                        actionsToSave.forEachIndexed { i, action ->
                            val map = mutableMapOf<String, Any?>()
                            map["pageIndex"] = action.pageIndex.toDouble()
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
                                is MarkupAction.Curve -> {
                                    map["type"] = "Curve"
                                    map["colorHex"] = "#" + action.color.toArgb().toUInt().toString(16)
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
                                is MarkupAction.Line -> {
                                    map["type"] = "Line"
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
                                is MarkupAction.Ellipse -> {
                                    map["type"] = "Ellipse"
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
                                    map["transparent"] = action.transparent
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
                            val added = markupsColl.add(map)
                            println("MARKUP SAVE: wrote action $i/${actionsToSave.size} type=${map["type"]} id=${added.id}")
                        }
                        println("MARKUP SAVE: all writes complete")

                        onSaveSuccess()
                    } catch (e: Exception) {
                        e.printStackTrace()
                        scope.launch { snackbarHostState.showSnackbar("Save failed: ${e.message ?: "unknown error"}") }
                    } finally {
                        isSaving = false
                        suppressMarkupSync = false
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
                        .onSizeChanged { canvasSizePx = it }
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
                        // Clean Architectural Blueprint Canvas Grid
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color(0xFF1E293B)),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Default.Build, contentDescription = null, tint = Color(0xFF38BDF8), modifier = Modifier.size(48.dp))
                                Spacer(modifier = Modifier.height(8.dp))
                                Text("Architectural Blueprint Studio", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 16.sp)
                                Text("Tap any tool at the top to draw redlines, add callouts, stamps & dimensions", color = Color.LightGray, fontSize = 12.sp)
                            }
                        }
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
                                            } else if (toolMode == "Arc") {
                                                // 3-point curve: each tap places one point; the
                                                // 3rd commits a smooth curve through all three
                                                // (same buildSmoothPath used by the freehand Curve
                                                // tool, so it renders/moves/exports identically).
                                                arcPoints.add(offset)
                                                if (arcPoints.size >= 3) {
                                                    val pts = arcPoints.toList()
                                                    actions.add(MarkupAction.Curve(currentPageIndex, buildSmoothPath(pts), pts, selectedColor, strokeWidth))
                                                    arcPoints.clear()
                                                }
                                            } else if (toolMode == "Move") {
                                                val candidates = actions.filter { it.pageIndex == currentPageIndex }.asReversed()
                                                // Prefer grabbing a single anchor of a 3-point
                                                // Curve (an Arc) when the tap lands right on one
                                                // of its points, so a slightly-off point can be
                                                // nudged instead of redrawing the whole curve.
                                                val pointHit = candidates.firstNotNullOfOrNull { action ->
                                                    if (action is MarkupAction.Curve && action.points.size == 3) {
                                                        val idx = action.points.indexOfFirst { hypot((it.x - offset.x).toDouble(), (it.y - offset.y).toDouble()) <= 20.0 }
                                                        if (idx >= 0) action to idx else null
                                                    } else null
                                                }
                                                if (pointHit != null) {
                                                    movingAction = pointHit.first
                                                    movingPointIndex = pointHit.second
                                                } else {
                                                    movingAction = candidates.firstOrNull { hitTestAction(it, offset, 24f) }
                                                    movingPointIndex = -1
                                                }
                                                moveDragOffset = Offset.Zero
                                            } else if (toolMode in listOf("Arrow", "Line", "Rect", "Circle", "Cloud", "Measure", "Calibrate")) {
                                                startOffset = offset
                                                currentDragOffset = offset
                                            } else {
                                                currentPath = Path().apply { moveTo(offset.x, offset.y) }
                                            }
                                        },
                                        onDrag = { change, dragAmount ->
                                            currentPoints.add(change.position)
                                            if (toolMode == "Move") {
                                                if (movingAction != null) moveDragOffset += dragAmount
                                            } else if (toolMode in listOf("Arrow", "Line", "Rect", "Circle", "Cloud", "Measure", "Calibrate")) {
                                                currentDragOffset = change.position
                                            } else if (toolMode == "Curve") {
                                                currentPath = buildSmoothPath(currentPoints)
                                            } else if (toolMode in listOf("Pen", "Highlight", "Eraser")) {
                                                currentPath?.lineTo(change.position.x, change.position.y)
                                                val p = currentPath
                                                currentPath = null
                                                currentPath = p
                                            }
                                        },
                                        onDragEnd = {
                                            if (toolMode == "Move") {
                                                val ref = movingAction
                                                if (ref != null && (moveDragOffset.x != 0f || moveDragOffset.y != 0f)) {
                                                    val idx = actions.indexOfFirst { it === ref }
                                                    if (idx >= 0) actions[idx] = applyMoveDrag(ref, movingPointIndex, moveDragOffset)
                                                }
                                                movingAction = null
                                                movingPointIndex = -1
                                                moveDragOffset = Offset.Zero
                                            } else if (toolMode in listOf("Arrow", "Line", "Rect", "Circle", "Cloud", "Measure", "Calibrate") && startOffset != null && currentDragOffset != null) {
                                                val s = startOffset!!
                                                val e = currentDragOffset!!
                                                when (toolMode) {
                                                    "Arrow" -> actions.add(MarkupAction.Arrow(currentPageIndex, s, e, selectedColor, strokeWidth))
                                                    "Line" -> actions.add(MarkupAction.Line(currentPageIndex, s, e, selectedColor, strokeWidth))
                                                    "Rect" -> actions.add(MarkupAction.Rectangle(currentPageIndex, s, e, selectedColor, strokeWidth))
                                                    "Circle" -> actions.add(MarkupAction.Ellipse(currentPageIndex, s, e, selectedColor, strokeWidth))
                                                    "Cloud" -> actions.add(MarkupAction.Cloud(currentPageIndex, s, e, selectedColor, strokeWidth))
                                                    "Measure" -> {
                                                        val distPx = hypot(e.x - s.x, e.y - s.y).toDouble()
                                                        val feet = distPx / pixelsPerFoot
                                                        val ftInt = feet.toInt()
                                                        val inInt = ((feet - ftInt) * 12.0).roundToInt()
                                                        val label = "$ftInt'-$inInt\""
                                                        actions.add(MarkupAction.Dimension(currentPageIndex, s, e, label, selectedColor))
                                                    }
                                                    "Calibrate" -> {
                                                        val distPx = hypot(e.x - s.x, e.y - s.y).toDouble()
                                                        if (distPx > 1.0) {
                                                            calibrationPixelDistance = distPx
                                                            showCalibrationInputDialog = true
                                                        }
                                                    }
                                                }
                                            } else {
                                                currentPath?.let { path ->
                                                    val pts = currentPoints.toList()
                                                    when (toolMode) {
                                                        "Eraser" -> actions.add(MarkupAction.Erase(currentPageIndex, path, pts, strokeWidth))
                                                        "Highlight" -> actions.add(MarkupAction.Highlight(currentPageIndex, path, pts, selectedColor.copy(alpha = 0.4f), strokeWidth * 2.5f))
                                                        "Curve" -> actions.add(MarkupAction.Curve(currentPageIndex, path, pts, selectedColor, strokeWidth))
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
                        // Render Committed Actions for Current Page (the one being actively
                        // dragged by the Move tool is skipped here and drawn separately below
                        // at its live offset, so it doesn't flash at its old spot mid-drag).
                        actions.filter { it.pageIndex == currentPageIndex }.forEach { action ->
                            if (action === movingAction) return@forEach
                            renderCanvasAction(action)
                        }

                        // Live Move Preview
                        movingAction?.let { renderCanvasAction(applyMoveDrag(it, movingPointIndex, moveDragOffset)) }

                        // Render Active Drag Preview
                        if (startOffset != null && currentDragOffset != null) {
                            val s = startOffset!!
                            val e = currentDragOffset!!
                            when (toolMode) {
                                "Arrow" -> drawArrow(s, e, selectedColor, strokeWidth)
                                "Line" -> drawLine(selectedColor, s, e, strokeWidth = strokeWidth, cap = StrokeCap.Round)
                                "Rect" -> drawRect(selectedColor, s, Size(e.x - s.x, e.y - s.y), style = Stroke(strokeWidth))
                                "Circle" -> drawOval(selectedColor, s, Size(e.x - s.x, e.y - s.y), style = Stroke(strokeWidth))
                                "Cloud" -> drawRevisionCloud(s, e, selectedColor, strokeWidth)
                                "Measure" -> {
                                    val distPx = hypot(e.x - s.x, e.y - s.y).toDouble()
                                    val feet = distPx / pixelsPerFoot
                                    val ftInt = feet.toInt()
                                    val inInt = ((feet - ftInt) * 12.0).roundToInt()
                                    drawDimensionLine(s, e, "$ftInt'-$inInt\"", selectedColor)
                                }
                                "Calibrate" -> drawDimensionLine(s, e, "?", Color(0xFF9C27B0))
                            }
                        }

                        // Live Arc (3-point curve) Preview - shows the points placed so far and
                        // the curve through them, so the shape is visible before the 3rd tap
                        // commits it.
                        if (toolMode == "Arc" && arcPoints.isNotEmpty()) {
                            if (arcPoints.size >= 2) {
                                drawPath(buildSmoothPath(arcPoints.toList()), selectedColor, style = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round))
                            }
                            arcPoints.forEach { drawEndpointMarker(it, selectedColor) }
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

                    // Render Text Notes & Stamps Overlay - always draggable (regardless of the
                    // active tool) since they're small discrete elements, like moving a sticky
                    // note; no need to switch to a dedicated tool first.
                    val density = LocalDensity.current
                    actions.filter { it.pageIndex == currentPageIndex }.forEach { action ->
                        when (action) {
                            is MarkupAction.TextNote -> {
                                // pointerInput(Unit) installs its gesture-detection coroutine
                                // once and keeps reusing it across every subsequent drag on this
                                // note, so onDrag/onDragEnd close over whatever `action` was at
                                // that first composition - a second drag would otherwise commit
                                // relative to the pre-first-drag position. rememberUpdatedState
                                // gives the closures a reference that's kept current across
                                // recompositions instead.
                                val currentAction by rememberUpdatedState(action)
                                var dragOffset by remember { mutableStateOf(Offset.Zero) }
                                var sizeDrag by remember { mutableStateOf(0f) }
                                val pos = action.position + dragOffset
                                val liveSize = (action.size + sizeDrag).coerceIn(10f, 72f)
                                Box(
                                    modifier = Modifier
                                        .offset(x = with(density) { pos.x.toDp() }, y = with(density) { pos.y.toDp() })
                                        .pointerInput(Unit) {
                                            detectDragGestures(
                                                onDrag = { change, amount -> change.consume(); dragOffset += amount },
                                                onDragEnd = {
                                                    val idx = actions.indexOfFirst { it === currentAction }
                                                    if (idx >= 0) actions[idx] = currentAction.copy(position = currentAction.position + dragOffset)
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
                                    // Transparency toggle - small dot in the top-right corner.
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .offset(x = 8.dp, y = (-8).dp)
                                            .size(16.dp)
                                            .clip(CircleShape)
                                            .background(action.color)
                                            .clickable {
                                                val idx = actions.indexOfFirst { it === currentAction }
                                                if (idx >= 0) actions[idx] = currentAction.copy(transparent = !currentAction.transparent)
                                            }
                                    )
                                    // Resize handle - drag the bottom-right corner to change font size.
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.BottomEnd)
                                            .offset(x = 8.dp, y = 8.dp)
                                            .size(16.dp)
                                            .clip(CircleShape)
                                            .background(Color.White)
                                            .border(1.5.dp, action.color, CircleShape)
                                            .pointerInput(Unit) {
                                                detectDragGestures(
                                                    onDrag = { change, amount ->
                                                        change.consume()
                                                        sizeDrag += (amount.x + amount.y) / 2f
                                                    },
                                                    onDragEnd = {
                                                        val idx = actions.indexOfFirst { it === currentAction }
                                                        val newSize = (currentAction.size + sizeDrag).coerceIn(10f, 72f)
                                                        if (idx >= 0) actions[idx] = currentAction.copy(size = newSize)
                                                        sizeDrag = 0f
                                                    }
                                                )
                                            }
                                    )
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
                                val currentAction by rememberUpdatedState(action)
                                var dragOffset by remember { mutableStateOf(Offset.Zero) }
                                val pos = action.position + dragOffset
                                Box(
                                    modifier = Modifier
                                        .offset(x = with(density) { pos.x.toDp() }, y = with(density) { pos.y.toDp() })
                                        .pointerInput(Unit) {
                                            detectDragGestures(
                                                onDrag = { change, amount -> change.consume(); dragOffset += amount },
                                                onDragEnd = {
                                                    val idx = actions.indexOfFirst { it === currentAction }
                                                    if (idx >= 0) actions[idx] = currentAction.copy(position = currentAction.position + dragOffset)
                                                    dragOffset = Offset.Zero
                                                }
                                            )
                                        }
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
                onConfirm = { content, size, transparent ->
                    if (content.isNotEmpty()) {
                        actions.add(MarkupAction.TextNote(currentPageIndex, content, tempTextPos, selectedColor, size, transparent))
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
                        OutlinedButton(
                            onClick = {
                                showScaleDialog = false
                                toolMode = "Calibrate"
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Calibrate by Drawing a Known Wall/Line", fontWeight = FontWeight.SemiBold)
                        }
                        HorizontalDivider()
                        Text("Or select a standard architectural drawing scale:")
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

        // Known-Length Input Dialog - shown right after drawing a Calibrate line, to convert
        // its pixel length into pixelsPerFoot using a real-world measurement the user supplies
        // (e.g. the length of a wall on the plan).
        if (showCalibrationInputDialog) {
            var feetInput by remember { mutableStateOf("") }
            var inchesInput by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { showCalibrationInputDialog = false },
                title = { Text("Set Scale From Drawn Line") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Enter the real-world length of the line you just drew (e.g. a known wall):")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = feetInput,
                                onValueChange = { feetInput = it.filter(Char::isDigit) },
                                label = { Text("Feet") },
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = inchesInput,
                                onValueChange = { inchesInput = it.filter(Char::isDigit) },
                                label = { Text("Inches") },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            val feet = (feetInput.toIntOrNull() ?: 0) + (inchesInput.toIntOrNull() ?: 0) / 12.0
                            if (feet > 0.0) {
                                pixelsPerFoot = calibrationPixelDistance / feet
                                scalePresetName = "Custom (calibrated)"
                            }
                            showCalibrationInputDialog = false
                            toolMode = "Pen"
                        },
                        enabled = (feetInput.toIntOrNull() ?: 0) > 0 || (inchesInput.toIntOrNull() ?: 0) > 0
                    ) { Text("Set Scale") }
                },
                dismissButton = {
                    TextButton(onClick = {
                        showCalibrationInputDialog = false
                        toolMode = "Pen"
                    }) { Text("Cancel") }
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
    drawEndpointMarker(start, color)
    drawEndpointMarker(end, color)
}

// A hollow ring + small crosshair instead of a solid dot, so the exact endpoint pixel stays
// visible underneath the marker instead of being covered by it.
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawEndpointMarker(center: Offset, color: Color) {
    drawCircle(color, radius = 7f, center = center, style = Stroke(width = 1.5f))
    val crossLen = 4f
    drawLine(color, Offset(center.x - crossLen, center.y), Offset(center.x + crossLen, center.y), strokeWidth = 1.5f)
    drawLine(color, Offset(center.x, center.y - crossLen), Offset(center.x, center.y + crossLen), strokeWidth = 1.5f)
}

// Smooths a raw freehand point trail (jittery from mouse/finger input) into a curved path by
// running a quadratic Bezier through the midpoint of each consecutive pair, using the actual
// recorded point as the curve's control point - the standard "smooth freehand line" technique.
private fun buildSmoothPath(points: List<Offset>): Path {
    val path = Path()
    if (points.isEmpty()) return path
    if (points.size < 3) {
        path.moveTo(points[0].x, points[0].y)
        points.drop(1).forEach { path.lineTo(it.x, it.y) }
        return path
    }
    if (points.size == 3) {
        // Exactly 3 points (the tap-to-place Arc tool) means "pass through all three anchors",
        // not freehand smoothing - solve the quadratic control point so the curve's midpoint
        // (t=0.5) lands exactly on points[1], instead of just bending toward it like the
        // freehand case below does (which would leave the middle tap looking like it "moved").
        val p0 = points[0]; val p1 = points[1]; val p2 = points[2]
        val control = Offset(2f * p1.x - 0.5f * p0.x - 0.5f * p2.x, 2f * p1.y - 0.5f * p0.y - 0.5f * p2.y)
        path.moveTo(p0.x, p0.y)
        path.quadraticTo(control.x, control.y, p2.x, p2.y)
        return path
    }
    path.moveTo(points[0].x, points[0].y)
    for (i in 1 until points.size - 1) {
        val mid = Offset((points[i].x + points[i + 1].x) / 2f, (points[i].y + points[i + 1].y) / 2f)
        path.quadraticTo(points[i].x, points[i].y, mid.x, mid.y)
    }
    path.lineTo(points.last().x, points.last().y)
    return path
}

// Straight-segment counterpart to buildSmoothPath - used to rebuild Draw/Highlight/Erase paths
// after their points are translated by the Move tool.
private fun buildStraightPath(points: List<Offset>): Path {
    val path = Path()
    if (points.isNotEmpty()) {
        path.moveTo(points[0].x, points[0].y)
        for (i in 1 until points.size) path.lineTo(points[i].x, points[i].y)
    }
    return path
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.renderCanvasAction(action: MarkupAction) {
    when (action) {
        is MarkupAction.Draw -> drawPath(action.path, action.color, style = Stroke(width = action.strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round))
        is MarkupAction.Highlight -> drawPath(action.path, action.color, style = Stroke(width = action.strokeWidth, cap = StrokeCap.Square, join = StrokeJoin.Bevel))
        is MarkupAction.Erase -> drawPath(action.path, Color(0xFFDCDCDC), style = Stroke(width = action.strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round))
        is MarkupAction.Arrow -> drawArrow(action.start, action.end, action.color, action.strokeWidth)
        is MarkupAction.Line -> drawLine(action.color, action.start, action.end, strokeWidth = action.strokeWidth, cap = StrokeCap.Round)
        is MarkupAction.Rectangle -> drawRect(action.color, action.start, Size(action.end.x - action.start.x, action.end.y - action.start.y), style = Stroke(action.strokeWidth))
        is MarkupAction.Ellipse -> drawOval(action.color, action.start, Size(action.end.x - action.start.x, action.end.y - action.start.y), style = Stroke(action.strokeWidth))
        is MarkupAction.Cloud -> drawRevisionCloud(action.start, action.end, action.color, action.strokeWidth)
        is MarkupAction.Curve -> drawPath(action.path, action.color, style = Stroke(width = action.strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round))
        is MarkupAction.Dimension -> drawDimensionLine(action.start, action.end, action.distanceStr, action.color)
        else -> {}
    }
}

// Renders one page's background plus its markups into a standalone offscreen bitmap, for
// exporting a real flattened file - reuses the exact same renderCanvasAction draw calls the
// live editor uses, so the export always matches what's on screen. TextNote is normally a
// Compose overlay Box on screen (see its rendering further up) rather than a DrawScope call, so
// it's baked in separately here via renderTextNoteForExport using the same TextMeasurer/style.
private fun flattenPage(background: ImageBitmap, pageActions: List<MarkupAction>, width: Int, height: Int, textMeasurer: TextMeasurer): ImageBitmap {
    val bitmap = ImageBitmap(width, height)
    val canvas = androidx.compose.ui.graphics.Canvas(bitmap)
    CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, canvas, Size(width.toFloat(), height.toFloat())) {
        drawImage(background, dstSize = IntSize(width, height))
        pageActions.forEach {
            renderCanvasAction(it)
            if (it is MarkupAction.TextNote) renderTextNoteForExport(it, textMeasurer)
        }
    }
    return bitmap
}

// Mirrors the on-screen TextNote Box (background/border/padding + bold text) using plain
// DrawScope calls, since that overlay is a Compose Box rather than a DrawScope draw call and
// so isn't picked up by renderCanvasAction/flattenPage on its own.
private fun androidx.compose.ui.graphics.drawscope.DrawScope.renderTextNoteForExport(action: MarkupAction.TextNote, textMeasurer: TextMeasurer) {
    val style = TextStyle(
        fontSize = action.size.sp,
        fontWeight = FontWeight.Bold,
        color = if (action.transparent) action.color else Color.Black
    )
    val layout = textMeasurer.measure(action.text, style)
    val padding = 6f
    val boxTopLeft = action.position - Offset(padding, padding)
    val boxSize = Size(layout.size.width + padding * 2, layout.size.height + padding * 2)
    if (!action.transparent) {
        drawRect(color = Color.White.copy(alpha = 0.95f), topLeft = boxTopLeft, size = boxSize)
    }
    drawRect(color = action.color, topLeft = boxTopLeft, size = boxSize, style = Stroke(width = 2f))
    drawText(layout, topLeft = action.position)
}

// Distance from point p to the segment a-b, for hit-testing thin lines/arrows/dimensions
// where a plain bounding-box test would be too generous.
private fun distanceToSegment(p: Offset, a: Offset, b: Offset): Float {
    val abx = b.x - a.x
    val aby = b.y - a.y
    val lenSq = abx * abx + aby * aby
    if (lenSq < 0.0001f) return hypot((p.x - a.x).toDouble(), (p.y - a.y).toDouble()).toFloat()
    val t = (((p.x - a.x) * abx + (p.y - a.y) * aby) / lenSq).coerceIn(0f, 1f)
    val projX = a.x + t * abx
    val projY = a.y + t * aby
    return hypot((p.x - projX).toDouble(), (p.y - projY).toDouble()).toFloat()
}

private fun boundsHit(point: Offset, start: Offset, end: Offset, tolerance: Float): Boolean {
    val left = min(start.x, end.x) - tolerance
    val right = max(start.x, end.x) + tolerance
    val top = min(start.y, end.y) - tolerance
    val bottom = max(start.y, end.y) + tolerance
    return point.x in left..right && point.y in top..bottom
}

// Hit-test for the Move tool: is `point` close enough to this action to grab it? Freehand
// strokes check proximity to any recorded point; lines/arrows/dimensions check distance to the
// segment; filled/bounded shapes accept a tap anywhere inside their bounding box (easier to
// grab than tracing the exact outline, and matches how selection normally works in vector
// editors). Text/Stamp aren't handled here - they're always-draggable overlay Boxes instead.
private fun hitTestAction(action: MarkupAction, point: Offset, tolerance: Float): Boolean = when (action) {
    is MarkupAction.Draw -> action.points.any { hypot((it.x - point.x).toDouble(), (it.y - point.y).toDouble()) <= tolerance }
    is MarkupAction.Highlight -> action.points.any { hypot((it.x - point.x).toDouble(), (it.y - point.y).toDouble()) <= tolerance }
    is MarkupAction.Curve -> action.points.any { hypot((it.x - point.x).toDouble(), (it.y - point.y).toDouble()) <= tolerance }
    is MarkupAction.Arrow -> distanceToSegment(point, action.start, action.end) <= tolerance
    is MarkupAction.Line -> distanceToSegment(point, action.start, action.end) <= tolerance
    is MarkupAction.Dimension -> distanceToSegment(point, action.start, action.end) <= tolerance
    is MarkupAction.Rectangle -> boundsHit(point, action.start, action.end, tolerance)
    is MarkupAction.Ellipse -> boundsHit(point, action.start, action.end, tolerance)
    is MarkupAction.Cloud -> boundsHit(point, action.start, action.end, tolerance)
    else -> false
}

// Returns a copy of `action` shifted by `delta`, rebuilding its Path for the point-list based
// types so the rendered stroke actually moves along with its stored points.
private fun translateAction(action: MarkupAction, delta: Offset): MarkupAction = when (action) {
    is MarkupAction.Draw -> action.points.map { it + delta }.let { action.copy(path = buildStraightPath(it), points = it) }
    is MarkupAction.Highlight -> action.points.map { it + delta }.let { action.copy(path = buildStraightPath(it), points = it) }
    is MarkupAction.Erase -> action.points.map { it + delta }.let { action.copy(path = buildStraightPath(it), points = it) }
    is MarkupAction.Curve -> action.points.map { it + delta }.let { action.copy(path = buildSmoothPath(it), points = it) }
    is MarkupAction.Arrow -> action.copy(start = action.start + delta, end = action.end + delta)
    is MarkupAction.Line -> action.copy(start = action.start + delta, end = action.end + delta)
    is MarkupAction.Rectangle -> action.copy(start = action.start + delta, end = action.end + delta)
    is MarkupAction.Ellipse -> action.copy(start = action.start + delta, end = action.end + delta)
    is MarkupAction.Cloud -> action.copy(start = action.start + delta, end = action.end + delta)
    is MarkupAction.Dimension -> action.copy(start = action.start + delta, end = action.end + delta)
    is MarkupAction.TextNote -> action.copy(position = action.position + delta)
    is MarkupAction.Stamp -> action.copy(position = action.position + delta)
}

// What the Move tool actually applies on drag: shifts one specific anchor of a 3-point Curve
// (pointIndex >= 0, grabbed when the tap landed on that exact point - see the Arc tool) or
// falls back to moving the whole shape via translateAction otherwise. Shared by the live drag
// preview and the committed result so they never disagree.
private fun applyMoveDrag(action: MarkupAction, pointIndex: Int, delta: Offset): MarkupAction {
    if (pointIndex >= 0 && action is MarkupAction.Curve) {
        val newPoints = action.points.toMutableList().also { it[pointIndex] = it[pointIndex] + delta }
        return action.copy(path = buildSmoothPath(newPoints), points = newPoints)
    }
    return translateAction(action, delta)
}

// Markup coordinates are recorded relative to the live on-screen drawing surface (canvasSize -
// whatever the window/viewport happened to be), but exporting flattens onto a canvas sized to
// the PDF page's own native resolution (bgWidth x bgHeight) - independent of viewport size.
// Without remapping through this, markups land at the wrong position/scale on export, often far
// enough off to look like the export has no markups at all. Mirrors how Image(...,
// contentScale = ContentScale.Fit) letterboxes the background on screen, so the remap has to
// invert that same fit-scale-and-center transform.
private fun remapActionToExportSpace(action: MarkupAction, canvasSize: IntSize, bgWidth: Int, bgHeight: Int): MarkupAction {
    if (canvasSize.width <= 0 || canvasSize.height <= 0 || bgWidth <= 0 || bgHeight <= 0) return action
    val scale = minOf(canvasSize.width.toFloat() / bgWidth, canvasSize.height.toFloat() / bgHeight)
    if (scale <= 0f) return action
    val letterboxX = (canvasSize.width - bgWidth * scale) / 2f
    val letterboxY = (canvasSize.height - bgHeight * scale) / 2f
    fun remap(o: Offset) = Offset((o.x - letterboxX) / scale, (o.y - letterboxY) / scale)
    // Text/Stamp markups are small discrete anchors rather than strokes, so a position dropped
    // just inside the letterbox margin - which looks like plain page whitespace on screen and is
    // an easy place to place a callout near the top/bottom edge - gets pinned to the nearest page
    // edge instead of landing at a negative/out-of-bounds coordinate that clips it off the
    // exported bitmap entirely.
    fun remapClamped(o: Offset) = remap(o).let { Offset(it.x.coerceIn(0f, bgWidth.toFloat()), it.y.coerceIn(0f, bgHeight.toFloat())) }
    return when (action) {
        is MarkupAction.Draw -> action.points.map(::remap).let { action.copy(path = buildStraightPath(it), points = it, strokeWidth = action.strokeWidth / scale) }
        is MarkupAction.Highlight -> action.points.map(::remap).let { action.copy(path = buildStraightPath(it), points = it, strokeWidth = action.strokeWidth / scale) }
        is MarkupAction.Erase -> action.points.map(::remap).let { action.copy(path = buildStraightPath(it), points = it, strokeWidth = action.strokeWidth / scale) }
        is MarkupAction.Curve -> action.points.map(::remap).let { action.copy(path = buildSmoothPath(it), points = it, strokeWidth = action.strokeWidth / scale) }
        is MarkupAction.Arrow -> action.copy(start = remap(action.start), end = remap(action.end), strokeWidth = action.strokeWidth / scale)
        is MarkupAction.Line -> action.copy(start = remap(action.start), end = remap(action.end), strokeWidth = action.strokeWidth / scale)
        is MarkupAction.Rectangle -> action.copy(start = remap(action.start), end = remap(action.end), strokeWidth = action.strokeWidth / scale)
        is MarkupAction.Ellipse -> action.copy(start = remap(action.start), end = remap(action.end), strokeWidth = action.strokeWidth / scale)
        is MarkupAction.Cloud -> action.copy(start = remap(action.start), end = remap(action.end), strokeWidth = action.strokeWidth / scale)
        is MarkupAction.Dimension -> action.copy(start = remap(action.start), end = remap(action.end))
        is MarkupAction.TextNote -> action.copy(position = remapClamped(action.position), size = action.size / scale)
        is MarkupAction.Stamp -> action.copy(position = remapClamped(action.position))
    }
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
fun StrokeWidthButton(width: Float, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else Color.Transparent)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary else Color.Gray,
                shape = CircleShape
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size((width / 2.2f).dp.coerceIn(4.dp, 20.dp))
                .clip(CircleShape)
                .background(Color.DarkGray)
        )
    }
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
fun AddTextDialog(onDismiss: () -> Unit, onConfirm: (text: String, size: Float, transparent: Boolean) -> Unit) {
    var text by remember { mutableStateOf("") }
    var size by remember { mutableStateOf(18f) }
    var transparent by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Plan Callout Note") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Note / Callout Text") },
                    modifier = Modifier.fillMaxWidth()
                )
                Column {
                    Text("Text size: ${size.toInt()}sp", style = MaterialTheme.typography.labelMedium)
                    Slider(value = size, onValueChange = { size = it }, valueRange = 10f..48f)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = transparent, onCheckedChange = { transparent = it })
                    Text("Transparent background")
                }
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(text, size, transparent) }) { Text("Add Callout") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
