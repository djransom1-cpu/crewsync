package com.example.crewsync.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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

sealed class MarkupAction {
    abstract val pageIndex: Int
    data class Draw(override val pageIndex: Int, val path: Path, val color: Color, val strokeWidth: Float) : MarkupAction()
    data class Erase(override val pageIndex: Int, val path: Path, val strokeWidth: Float) : MarkupAction()
    data class Text(override val pageIndex: Int, val text: String, val position: Offset, val color: Color, val size: Float) : MarkupAction()
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
    val graphicsLayer = rememberGraphicsLayer()
    
    var projectFile by remember { mutableStateOf<ProjectFile?>(null) }
    var currentPath by remember { mutableStateOf<Path?>(null) }
    var selectedColor by remember { mutableStateOf(Color.Red) }
    var strokeWidth by remember { mutableStateOf(10f) }
    var toolMode by remember { mutableStateOf("Pen") } 
    
    var showTextDialog by remember { mutableStateOf(false) }
    var tempTextPos by remember { mutableStateOf(Offset.Zero) }
    var isSaving by remember { mutableStateOf(false) }

    // Multi-page Support
    var currentPageIndex by remember { mutableStateOf(0) }
    var zoomScale by remember { mutableStateOf(1f) }
    var zoomOffset by remember { mutableStateOf(Offset.Zero) }

    LaunchedEffect(fileId) {
        firestore.collection("projects").document(projectId).collection("files").document(fileId).snapshots.collect { snap ->
            if (snap.exists) {
                try {
                    projectFile = snap.data<ProjectFile>()
                } catch (e: Exception) {}
            }
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
                        Text(projectFile?.name ?: "Loading...", style = MaterialTheme.typography.titleMedium)
                        if (isPdf && pdfRenderer != null) {
                            Text("Page ${currentPageIndex + 1} of ${pdfRenderer.pageCount}", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { 
                        val lastOnPage = actions.findLast { it.pageIndex == currentPageIndex }
                        if (lastOnPage != null) actions.remove(lastOnPage)
                    }) {
                        Icon(Icons.Default.Build, contentDescription = "Undo")
                    }
                    TextButton(onClick = { actions.removeAll { it.pageIndex == currentPageIndex } }) {
                        Text("Clear Page", color = MaterialTheme.colorScheme.error)
                    }
                }
            )
        },
        bottomBar = {
            Column(modifier = Modifier.background(MaterialTheme.colorScheme.surface)) {
                // Page Navigation for PDFs
                if (isPdf && pdfRenderer != null && pdfRenderer.pageCount > 1) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { if (currentPageIndex > 0) currentPageIndex-- }, enabled = currentPageIndex > 0) {
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Prev Page")
                        }
                        Text("Blueprint Page Selector", style = MaterialTheme.typography.labelMedium)
                        IconButton(onClick = { if (currentPageIndex < pdfRenderer.pageCount - 1) currentPageIndex++ }, enabled = currentPageIndex < pdfRenderer.pageCount - 1) {
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next Page")
                        }
                    }
                }

                Row(modifier = Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(20.dp))
                    Slider(
                        value = strokeWidth,
                        onValueChange = { strokeWidth = it },
                        valueRange = 2f..50f,
                        modifier = Modifier.weight(1f).padding(horizontal = 16.dp)
                    )
                    IconButton(onClick = { zoomScale = 1f; zoomOffset = Offset.Zero }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Reset Zoom")
                    }
                }
                
                BottomAppBar {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        IconButton(onClick = { toolMode = "Pen" }) {
                            Icon(Icons.Default.Build, contentDescription = "Pen", tint = if (toolMode == "Pen") MaterialTheme.colorScheme.primary else LocalContentColor.current)
                        }
                        IconButton(onClick = { toolMode = "Eraser" }) {
                            Icon(Icons.Default.Delete, contentDescription = "Eraser", tint = if (toolMode == "Eraser") MaterialTheme.colorScheme.primary else LocalContentColor.current)
                        }
                        IconButton(onClick = { toolMode = "Text" }) {
                            Icon(Icons.Default.Add, contentDescription = "Text", tint = if (toolMode == "Text") MaterialTheme.colorScheme.primary else LocalContentColor.current)
                        }
                        VerticalDivider()
                        ColorButton(Color.Red, selectedColor) { selectedColor = Color.Red }
                        ColorButton(Color.Yellow, selectedColor) { selectedColor = Color.Yellow }
                        ColorButton(Color.Green, selectedColor) { selectedColor = Color.Green }
                        ColorButton(Color.Blue, selectedColor) { selectedColor = Color.Blue }
                    }
                }
            }
        },
        floatingActionButton = {
            FloatingActionButton(onClick = {
                if (isSaving || projectFile == null) return@FloatingActionButton
                scope.launch {
                    isSaving = true
                    try {
                        val markupFile = ProjectFile(
                            name = "Redlined_${projectFile!!.name}",
                            url = projectFile!!.url,
                            uploadedBy = auth.currentUser?.email ?: "Admin",
                            uploadedAt = Clock.System.now().toEpochMilliseconds()
                        )
                        firestore.collection("projects").document(projectId).collection("files").add(markupFile)
                        onSaveSuccess()
                    } catch (e: Exception) {
                    } finally {
                        isSaving = false
                    }
                }
            }) {
                if (isSaving) CircularProgressIndicator(modifier = Modifier.size(24.dp))
                else Icon(Icons.Default.Check, contentDescription = "Save")
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(Color(0xFFEEEEEE)) // Light grey background for better blueprint contrast
                .pointerInput(Unit) {
                    // Zoom & Pan detection
                    detectTransformGestures { _, pan, zoom, _ ->
                        zoomScale = (zoomScale * zoom).coerceIn(1f, 5f)
                        zoomOffset += pan
                    }
                }
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
                            translationY = zoomOffset.y
                        )
                        .drawWithContent {
                            graphicsLayer.record {
                                this@drawWithContent.drawContent()
                            }
                            drawLayer(graphicsLayer)
                        }
                ) {
                    if (isPdf) {
                        if (currentPdfPage != null) {
                            Image(
                                bitmap = currentPdfPage,
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Fit
                            )
                        } else {
                            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                        }
                    } else {
                        AsyncImage(
                            model = ImageRequest.Builder(LocalPlatformContext.current)
                                .data(projectFile!!.url)
                                .crossfade(true)
                                .build(),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit
                        )
                    }

                    Canvas(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(toolMode, currentPageIndex) {
                                detectDragGestures(
                                    onDragStart = { offset ->
                                        if (toolMode == "Text") {
                                            tempTextPos = offset
                                            showTextDialog = true
                                        } else {
                                            currentPath = Path().apply { moveTo(offset.x, offset.y) }
                                        }
                                    },
                                    onDrag = { change, _ ->
                                        if (toolMode != "Text") {
                                            currentPath?.lineTo(change.position.x, change.position.y)
                                            val p = currentPath
                                            currentPath = null
                                            currentPath = p
                                        }
                                    },
                                    onDragEnd = {
                                        currentPath?.let {
                                            if (toolMode == "Eraser") {
                                                actions.add(MarkupAction.Erase(currentPageIndex, it, strokeWidth))
                                            } else {
                                                actions.add(MarkupAction.Draw(currentPageIndex, it, selectedColor, strokeWidth))
                                            }
                                        }
                                        currentPath = null
                                    }
                                )
                            }
                    ) {
                        actions.filter { it.pageIndex == currentPageIndex }.forEach { action ->
                            when (action) {
                                is MarkupAction.Draw -> drawPath(
                                    path = action.path,
                                    color = action.color,
                                    style = Stroke(width = action.strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
                                )
                                is MarkupAction.Erase -> drawPath(
                                    path = action.path,
                                    color = Color(0xFFEEEEEE), 
                                    style = Stroke(width = action.strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
                                )
                                else -> {}
                            }
                        }
                        
                        currentPath?.let { path ->
                            drawPath(
                                path = path,
                                color = if (toolMode == "Eraser") Color.White.copy(alpha = 0.5f) else selectedColor,
                                style = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
                            )
                        }
                    }
                    
                    val density = LocalDensity.current
                    actions.filterIsInstance<MarkupAction.Text>().filter { it.pageIndex == currentPageIndex }.forEach { textAction ->
                        Box(
                            modifier = Modifier.offset(
                                x = with(density) { textAction.position.x.toDp() },
                                y = with(density) { textAction.position.y.toDp() }
                            )
                            .background(Color.White.copy(alpha = 0.9f), RoundedCornerShape(4.dp))
                            .border(1.5.dp, textAction.color, RoundedCornerShape(4.dp))
                            .padding(8.dp)
                        ) {
                            Text(
                                text = textAction.text,
                                color = Color.Black, // Dark text for readability on white box
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }

        if (showTextDialog) {
            AddTextDialog(
                onDismiss = { showTextDialog = false },
                onConfirm = { content ->
                    if (content.isNotEmpty()) {
                        actions.add(MarkupAction.Text(currentPageIndex, content, tempTextPos, selectedColor, strokeWidth + 10))
                    }
                    showTextDialog = false
                }
            )
        }
    }
}

@Composable
fun AddTextDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Note") },
        text = {
            TextField(value = text, onValueChange = { text = it }, placeholder = { Text("Type here...") })
        },
        confirmButton = {
            Button(onClick = { onConfirm(text) }) { Text("Add") }
        }
    )
}

@Composable
fun ColorButton(color: Color, selected: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(color)
            .border(
                width = if (color == selected) 3.dp else 0.dp,
                color = if (color == selected) MaterialTheme.colorScheme.outline else Color.Transparent,
                shape = CircleShape
            )
            .clickable { onClick() }
    )
}
