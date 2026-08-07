package com.example.crewsync.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.example.crewsync.data.model.ChecklistItem
import com.example.crewsync.data.model.ProjectFile
import com.example.crewsync.data.model.Task
import com.example.crewsync.data.model.User
import com.example.crewsync.util.*
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.auth.auth
import dev.gitlive.firebase.firestore.firestore
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlannerScreen(projectId: String, projectBuckets: List<String>, projectMembers: List<String>) {
    val firestore = Firebase.firestore
    val scope = rememberCoroutineScope()
    
    var showAddTaskDialog by remember { mutableStateOf(false) }
    var showManageBucketsDialog by remember { mutableStateOf(false) }
    var selectedTask by remember { mutableStateOf<Task?>(null) }
    
    val tasks by firestore.collection("tasks")
        .snapshots
        .map { snapshot -> 
            snapshot.documents.mapNotNull { doc -> 
                try {
                    doc.toTaskSafe()
                } catch (e: Exception) { null }
            }.filter { it.projectId == projectId }
        }
        .collectAsState(initial = emptyList())

    val allUsers by firestore.collection("users")
        .snapshots
        .map { snapshot ->
            snapshot.documents.mapNotNull { doc ->
                try {
                    doc.data<User>().let { if (it.email.isEmpty()) it.copy(email = doc.id) else it }
                } catch (e: Exception) { null }
            }
        }
        .collectAsState(initial = emptyList())

    val userMap = allUsers.associate { it.email to it.name.ifEmpty { it.email } }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(onClick = { showManageBucketsDialog = true }) {
                Icon(Icons.Default.Settings, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("Manage Buckets")
            }
        }

        Box(modifier = Modifier.weight(1f)) {
            LazyRow(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                items(projectBuckets) { bucketName ->
                    val columnTasks = tasks.filter { it.status == bucketName }
                    PlannerColumn(
                        title = bucketName,
                        tasks = columnTasks,
                        onTaskClick = { selectedTask = it },
                        onMoveTask = { task, newStatus ->
                            scope.launch {
                                firestore.collection("tasks").document(task.id).update("status" to newStatus)
                                if (newStatus == "In Progress") {
                                    notifyTaskUpdate(
                                        title = "Task In Progress",
                                        message = "The task '${task.title}' has been moved to In Progress."
                                    )
                                }
                            }
                        },
                        allBuckets = projectBuckets,
                        userMap = userMap
                    )
                }
            }

            FloatingActionButton(
                onClick = { showAddTaskDialog = true },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add Task")
            }
        }
    }

    if (showAddTaskDialog) {
        AddTaskDialog(
            members = projectMembers,
            userMap = userMap,
            onDismiss = { showAddTaskDialog = false },
            onConfirm = { title, desc, start, due, assigned, color ->
                showAddTaskDialog = false
                scope.launch {
                    val newTask = Task(
                        projectId = projectId,
                        title = title,
                        description = desc,
                        assignedTo = assigned,
                        status = projectBuckets.firstOrNull() ?: "Not Started",
                        startDate = start,
                        dueDate = due,
                        color = color
                    )
                    firestore.collection("tasks").add(newTask.toFirestoreMap())
                }
            }
        )
    }

    if (showManageBucketsDialog) {
        ManageBucketsDialog(
            currentBuckets = projectBuckets,
            onDismiss = { showManageBucketsDialog = false },
            onUpdateBuckets = { newBuckets ->
                showManageBucketsDialog = false
                scope.launch {
                    firestore.collection("projects").document(projectId).update("buckets" to newBuckets)
                }
            }
        )
    }

    if (selectedTask != null) {
        TaskDetailsDialog(
            task = selectedTask!!,
            buckets = projectBuckets,
            members = projectMembers,
            userMap = userMap,
            onDismiss = { selectedTask = null },
            onUpdateTask = { updatedTask ->
                selectedTask = updatedTask
                scope.launch {
                    firestore.collection("tasks").document(updatedTask.id).set(updatedTask.toFirestoreMap())
                }
            },
            onUploadAttachment = { pickedFile, onUrlReady ->
                scope.launch {
                    try {
                        val path = "task_attachments/${selectedTask!!.id}/${pickedFile.name}"
                        val url = uploadFile(path, pickedFile.platformFile)
                        onUrlReady(url)
                    } catch (e: Exception) {}
                }
            }
        )
    }
}

@Composable
fun PlannerColumn(
    title: String,
    tasks: List<Task>,
    onTaskClick: (Task) -> Unit,
    onMoveTask: (Task, String) -> Unit,
    allBuckets: List<String>,
    userMap: Map<String, String>
) {
    Column(
        modifier = Modifier
            .width(280.dp)
            .fillMaxHeight()
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = MaterialTheme.shapes.medium,
            tonalElevation = 2.dp
        ) {
            Column(modifier = Modifier.padding(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Badge(containerColor = MaterialTheme.colorScheme.secondaryContainer) {
                        Text(tasks.size.toString())
                    }
                }
                
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(tasks) { task ->
                        TaskCard(
                            task = task, 
                            onClick = { onTaskClick(task) },
                            onMove = { targetBucket -> onMoveTask(task, targetBucket) },
                            allBuckets = allBuckets,
                            userMap = userMap
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun TaskCard(task: Task, onClick: () -> Unit, onMove: (String) -> Unit, allBuckets: List<String>, userMap: Map<String, String>) {
    var showMoveMenu by remember { mutableStateOf(false) }
    val cardBg = try { Color(parseColor(task.color)) } catch (e: Exception) { MaterialTheme.colorScheme.surface }

    Card(
        modifier = Modifier.fillMaxWidth().clickable { onClick() },
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(containerColor = cardBg)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    text = task.title, 
                    style = MaterialTheme.typography.titleSmall, 
                    modifier = Modifier.weight(1f),
                    color = if (isDarkColor(cardBg)) Color.White else Color.Black
                )
                Box {
                    IconButton(onClick = { showMoveMenu = true }, modifier = Modifier.size(24.dp)) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowForward, 
                            contentDescription = "Move", 
                            modifier = Modifier.size(16.dp),
                            tint = if (isDarkColor(cardBg)) Color.White else Color.Black
                        )
                    }
                    DropdownMenu(expanded = showMoveMenu, onDismissRequest = { showMoveMenu = false }) {
                        allBuckets.filter { it != task.status }.forEach { bucket ->
                            DropdownMenuItem(
                                text = { Text("Move to $bucket") },
                                onClick = {
                                    onMove(bucket)
                                    showMoveMenu = false
                                }
                            )
                        }
                    }
                }
            }
            
            if (task.description.isNotEmpty()) {
                Text(
                    text = task.description,
                    maxLines = 2,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isDarkColor(cardBg)) Color.White.copy(alpha = 0.7f) else Color.Black.copy(alpha = 0.7f)
                )
            }
            
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
            if (task.dueDate != null) {
                val now = Clock.System.now().toEpochMilliseconds()
                val isPastDue = task.dueDate < now && task.status != "Done"
                Text(
                    text = "Due: ${formatDate(task.dueDate)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isPastDue) {
                        if (isDarkColor(cardBg)) Color(0xFFFF8A80) else Color.Red
                    } else {
                        if (isDarkColor(cardBg)) Color.White.copy(alpha = 0.7f) else Color.Black.copy(alpha = 0.7f)
                    },
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
                
                if (task.assignedTo != null) {
                    val displayName = userMap[task.assignedTo] ?: task.assignedTo
                    Text(
                        text = "@$displayName",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isDarkColor(cardBg)) Color.White else Color.DarkGray,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskDetailsDialog(
    task: Task,
    buckets: List<String>,
    members: List<String>,
    userMap: Map<String, String>,
    onDismiss: () -> Unit,
    onUpdateTask: (Task) -> Unit,
    onUploadAttachment: (PickedFile, (String) -> Unit) -> Unit
) {
    var isUploading by remember { mutableStateOf(false) }
    var newChecklistItem by remember { mutableStateOf("") }
    
    var editingDescription by remember { mutableStateOf(task.description) }
    var showMemberPicker by remember { mutableStateOf(false) }

    val colors = listOf("#FFFFFF", "#FFCDD2", "#C8E6C9", "#BBDEFB", "#FFF9C4", "#E1BEE7", "#F5F5F5", "#212121")

    val attachmentLauncher = rememberFilePickerLauncher { pickedFile ->
        isUploading = true
        onUploadAttachment(pickedFile) { url ->
            val newFile = ProjectFile(
                name = pickedFile.name,
                url = url,
                uploadedAt = Clock.System.now().toEpochMilliseconds()
            )
            onUpdateTask(task.copy(attachments = task.attachments + newFile))
            isUploading = false
        }
    }

    var showStartDatePicker by remember { mutableStateOf(false) }
    var showDueDatePicker by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(task.title) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Column {
                    Text("Description", style = MaterialTheme.typography.labelMedium)
                    TextField(
                        value = editingDescription,
                        onValueChange = { editingDescription = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Add detail...") },
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, autoCorrectEnabled = true)
                    )
                }

                Column {
                    Text("Assigned To", style = MaterialTheme.typography.labelMedium)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val assignedName = userMap[task.assignedTo ?: ""] ?: "Unassigned"
                        Text(assignedName, modifier = Modifier.weight(1f))
                        TextButton(onClick = { showMemberPicker = true }) {
                            Text("Change")
                        }
                    }
                    if (showMemberPicker) {
                        DropdownMenu(expanded = showMemberPicker, onDismissRequest = { showMemberPicker = false }) {
                            DropdownMenuItem(text = { Text("Unassigned") }, onClick = { 
                                onUpdateTask(task.copy(assignedTo = null))
                                showMemberPicker = false 
                            })
                            members.forEach { email ->
                                val name = userMap[email] ?: email
                                DropdownMenuItem(text = { Text(name) }, onClick = { 
                                    onUpdateTask(task.copy(assignedTo = email))
                                    showMemberPicker = false 
                                })
                            }
                        }
                    }
                }

                Column {
                    Text("Card Color", style = MaterialTheme.typography.labelMedium)
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        colors.forEach { hex ->
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(Color(parseColor(hex)))
                                    .border(
                                        width = if (task.color == hex) 2.dp else 1.dp,
                                        color = if (task.color == hex) MaterialTheme.colorScheme.primary else Color.LightGray,
                                        shape = CircleShape
                                    )
                                    .clickable { onUpdateTask(task.copy(color = hex)) }
                            )
                        }
                    }
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Start Date", style = MaterialTheme.typography.labelMedium)
                        Text(
                            text = task.startDate?.let { formatDate(it) } ?: "Set date",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.clickable { showStartDatePicker = true }
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Due Date", style = MaterialTheme.typography.labelMedium)
                        Text(
                            text = task.dueDate?.let { formatDate(it) } ?: "Set date",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.clickable { showDueDatePicker = true }
                        )
                    }
                }

                Column {
                    Text("Bucket (Status)", style = MaterialTheme.typography.labelMedium)
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        buckets.forEach { status ->
                            FilterChip(
                                selected = task.status == status,
                                onClick = { onUpdateTask(task.copy(status = status)) },
                                label = { Text(status) }
                            )
                        }
                    }
                }

                Column {
                    Text("Checklist", style = MaterialTheme.typography.labelMedium)
                    task.checklist.forEach { item ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = item.isDone,
                                onCheckedChange = { isChecked ->
                                    val newList = task.checklist.map {
                                        if (it.id == item.id) it.copy(isDone = isChecked) else it
                                    }
                                    onUpdateTask(task.copy(checklist = newList))
                                }
                            )
                            Text(item.text, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextField(
                            value = newChecklistItem,
                            onValueChange = { newChecklistItem = it },
                            placeholder = { Text("Add item...") },
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = {
                            if (newChecklistItem.isNotBlank()) {
                                val item = ChecklistItem(id = "${task.checklist.size}_${Clock.System.now().toEpochMilliseconds()}", text = newChecklistItem)
                                onUpdateTask(task.copy(checklist = task.checklist + item))
                                newChecklistItem = ""
                            }
                        }) {
                            Icon(Icons.Default.Add, contentDescription = "Add")
                        }
                    }
                }

                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Attachments", style = MaterialTheme.typography.labelMedium)
                        if (isUploading) CircularProgressIndicator(modifier = Modifier.size(20.dp))
                        else IconButton(onClick = { attachmentLauncher() }) {
                            Icon(Icons.Default.Add, contentDescription = "Upload")
                        }
                    }
                    task.attachments.forEach { file ->
                        ListItem(
                            headlineContent = { Text(file.name, style = MaterialTheme.typography.bodySmall) },
                            leadingContent = { Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(16.dp)) },
                            modifier = Modifier.clickable { openUrl(file.url) }
                        )
                    }
                }
            }

            if (showStartDatePicker) {
                val state = rememberDatePickerState(initialSelectedDateMillis = task.startDate ?: Clock.System.now().toEpochMilliseconds())
                DatePickerDialog(
                    onDismissRequest = { showStartDatePicker = false },
                    confirmButton = {
                        TextButton(onClick = {
                            onUpdateTask(task.copy(startDate = state.selectedDateMillis))
                            showStartDatePicker = false
                        }) { Text("OK") }
                    }
                ) { DatePicker(state = state) }
            }

            if (showDueDatePicker) {
                val state = rememberDatePickerState(initialSelectedDateMillis = task.dueDate ?: Clock.System.now().toEpochMilliseconds())
                DatePickerDialog(
                    onDismissRequest = { showDueDatePicker = false },
                    confirmButton = {
                        TextButton(onClick = {
                            onUpdateTask(task.copy(dueDate = state.selectedDateMillis))
                            showDueDatePicker = false
                        }) { Text("OK") }
                    }
                ) { DatePicker(state = state) }
            }
        },
        confirmButton = {
            Button(onClick = { 
                onUpdateTask(task.copy(description = editingDescription))
                onDismiss() 
            }) { Text("Save & Close") }
        }
    )
}

@Composable
fun ManageBucketsDialog(
    currentBuckets: List<String>,
    onDismiss: () -> Unit,
    onUpdateBuckets: (List<String>) -> Unit
) {
    var buckets by remember { mutableStateOf(currentBuckets) }
    var newBucketName by remember { mutableStateOf("") }
    var editingIndex by remember { mutableStateOf<Int?>(null) }
    var editingName by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Manage Planner Buckets") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LazyColumn(modifier = Modifier.heightIn(max = 300.dp)) {
                    items(buckets.size) { index ->
                        val bucket = buckets[index]
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (editingIndex == index) {
                                    TextField(
                                        value = editingName,
                                        onValueChange = { editingName = it },
                                        modifier = Modifier.weight(1f)
                                    )
                                    IconButton(onClick = {
                                        if (editingName.isNotBlank()) {
                                            buckets = buckets.toMutableList().apply { set(index, editingName) }
                                            editingIndex = null
                                        }
                                    }) {
                                        Icon(Icons.Default.Check, contentDescription = "Save")
                                    }
                                } else {
                                    Text(bucket, modifier = Modifier.weight(1f))
                                    Row {
                                        IconButton(onClick = { 
                                            editingIndex = index
                                            editingName = bucket 
                                        }) {
                                            Icon(Icons.Default.Edit, contentDescription = "Edit")
                                        }
                                        if (index > 0) {
                                            IconButton(onClick = {
                                                buckets = buckets.toMutableList().apply {
                                                    val temp = get(index)
                                                    set(index, get(index - 1))
                                                    set(index - 1, temp)
                                                }
                                            }) {
                                                Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Move Up")
                                            }
                                        }
                                        if (index < buckets.size - 1) {
                                            IconButton(onClick = {
                                                buckets = buckets.toMutableList().apply {
                                                    val temp = get(index)
                                                    set(index, get(index + 1))
                                                    set(index + 1, temp)
                                                }
                                            }) {
                                                Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Move Down")
                                            }
                                        }
                                        IconButton(onClick = { buckets = buckets.filterIndexed { i, _ -> i != index } }) {
                                            Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
                                        }
                                    }
                                }
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextField(
                        value = newBucketName,
                        onValueChange = { newBucketName = it },
                        label = { Text("New Bucket") },
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = {
                        if (newBucketName.isNotBlank()) {
                            buckets = buckets + newBucketName
                            newBucketName = ""
                        }
                    }) {
                        Icon(Icons.Default.Add, contentDescription = null)
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onUpdateBuckets(buckets) }) { Text("Save Changes") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddTaskDialog(members: List<String>, userMap: Map<String, String>, onDismiss: () -> Unit, onConfirm: (String, String, Long?, Long?, String?, String) -> Unit) {
    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var assignedTo by remember { mutableStateOf<String?>(null) }
    var selectedColor by remember { mutableStateOf("#FFFFFF") }
    
    var startDate by remember { mutableStateOf<Long?>(null) }
    var dueDate by remember { mutableStateOf<Long?>(null) }
    
    var showStartDatePicker by remember { mutableStateOf(false) }
    var showDueDatePicker by remember { mutableStateOf(false) }
    var showMemberPicker by remember { mutableStateOf(false) }

    val colors = listOf("#FFFFFF", "#FFCDD2", "#C8E6C9", "#BBDEFB", "#FFF9C4", "#E1BEE7", "#F5F5F5", "#212121")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New Project Task") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                TextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Task Title") },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, autoCorrectEnabled = true)
                )
                TextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Detailed Description") },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, autoCorrectEnabled = true)
                )
                
                Column {
                    Text("Assign To", style = MaterialTheme.typography.labelMedium)
                    Box {
                        Button(onClick = { showMemberPicker = true }, modifier = Modifier.fillMaxWidth()) {
                            val assignedName = userMap[assignedTo ?: ""] ?: "Unassigned"
                            Text(assignedName)
                        }
                        DropdownMenu(expanded = showMemberPicker, onDismissRequest = { showMemberPicker = false }) {
                            DropdownMenuItem(text = { Text("Unassigned") }, onClick = { 
                                assignedTo = null
                                showMemberPicker = false 
                            })
                            members.forEach { email ->
                                val name = userMap[email] ?: email
                                DropdownMenuItem(text = { Text(name) }, onClick = { 
                                    assignedTo = email
                                    showMemberPicker = false 
                                })
                            }
                        }
                    }
                }

                Column {
                    Text("Card Color", style = MaterialTheme.typography.labelMedium)
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        colors.forEach { hex ->
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(Color(parseColor(hex)))
                                    .border(
                                        width = if (selectedColor == hex) 2.dp else 1.dp,
                                        color = if (selectedColor == hex) MaterialTheme.colorScheme.primary else Color.LightGray,
                                        shape = CircleShape
                                    )
                                    .clickable { selectedColor = hex }
                            )
                        }
                    }
                }
                
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { showStartDatePicker = true }, modifier = Modifier.weight(1f)) {
                        Text(startDate?.let { "Start: ${formatDate(it)}" } ?: "Set Start")
                    }
                    Button(onClick = { showDueDatePicker = true }, modifier = Modifier.weight(1f)) {
                        Text(dueDate?.let { "Due: ${formatDate(it)}" } ?: "Set Due")
                    }
                }
            }
            
            if (showStartDatePicker) {
                val state = rememberDatePickerState()
                DatePickerDialog(
                    onDismissRequest = { showStartDatePicker = false },
                    confirmButton = {
                        TextButton(onClick = {
                            startDate = state.selectedDateMillis
                            showStartDatePicker = false
                        }) { Text("OK") }
                    }
                ) { DatePicker(state = state) }
            }
            
            if (showDueDatePicker) {
                val state = rememberDatePickerState()
                DatePickerDialog(
                    onDismissRequest = { showDueDatePicker = false },
                    confirmButton = {
                        TextButton(onClick = {
                            dueDate = state.selectedDateMillis
                            showDueDatePicker = false
                        }) { Text("OK") }
                    }
                ) { DatePicker(state = state) }
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(title, description, startDate, dueDate, assignedTo, selectedColor) }, enabled = title.isNotBlank()) {
                Text("Add to Planner")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

fun formatDate(timestamp: Long): String {
    val instant = Instant.fromEpochMilliseconds(timestamp)
    val date = instant.toLocalDateTime(TimeZone.currentSystemDefault())
    return "${date.monthNumber}/${date.dayOfMonth}/${date.year}"
}
