package com.example.crewsync.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.crewsync.data.model.ChecklistItem
import com.example.crewsync.data.model.DEFAULT_TASK_TEMPLATES
import com.example.crewsync.data.model.ProjectFile
import com.example.crewsync.data.model.Task
import com.example.crewsync.data.model.TaskTemplate
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
    var showManageTemplatesDialog by remember { mutableStateOf(false) }
    var selectedTask by remember { mutableStateOf<Task?>(null) }
    
    val tasksFlow = remember(projectId) {
        firestore.collection("tasks")
            .snapshots
            .map { snapshot -> 
                snapshot.documents.mapNotNull { doc -> 
                    try {
                        doc.toTaskSafe()
                    } catch (e: Exception) { null }
                }.filter { it.projectId == projectId }
            }
    }
    val tasks by tasksFlow.collectAsState(initial = emptyList())

    val allUsersFlow = remember {
        firestore.collection("users")
            .snapshots
            .map { snapshot ->
                snapshot.documents.mapNotNull { doc ->
                    try {
                        doc.data<User>().let { if (it.email.isEmpty()) it.copy(email = doc.id) else it }
                    } catch (e: Exception) { null }
                }
            }
    }
    val allUsers by allUsersFlow.collectAsState(initial = emptyList())

    val userMap = remember(allUsers) { allUsers.associate { it.email to it.name.ifEmpty { it.email } } }

    val customTemplatesFlow = remember {
        firestore.collection("task_templates")
            .snapshots
            .map { snapshot ->
                snapshot.documents.mapNotNull { doc ->
                    try { doc.toTaskTemplateSafe() } catch (e: Exception) { null }
                }
            }
    }
    val customTemplates by customTemplatesFlow.collectAsState(initial = emptyList())

    val allTemplates = remember(customTemplates) {
        val customMap = customTemplates.associateBy { it.title.lowercase() }
        val merged = DEFAULT_TASK_TEMPLATES.map { defaultTpl ->
            customMap[defaultTpl.title.lowercase()] ?: defaultTpl
        } + customTemplates.filter { c -> DEFAULT_TASK_TEMPLATES.none { d -> d.title.equals(c.title, ignoreCase = true) } }
        merged
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = { showManageTemplatesDialog = true }) {
                Icon(Icons.Default.Star, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Trade Task Database", fontSize = 12.sp)
            }
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = { showManageBucketsDialog = true }) {
                Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Manage Buckets", fontSize = 12.sp)
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
                Icon(Icons.Default.Add, contentDescription = "Add Task Card")
            }
        }
    }

    if (showAddTaskDialog) {
        AddTaskDialog(
            members = projectMembers,
            userMap = userMap,
            templates = allTemplates,
            onDismiss = { showAddTaskDialog = false },
            onConfirm = { title, desc, start, due, assigned, color, saveToDb, checklistItems ->
                showAddTaskDialog = false
                scope.launch {
                    val initialChecklist = checklistItems.mapIndexed { idx, text ->
                        ChecklistItem(id = "chk_${idx}_${Clock.System.now().toEpochMilliseconds()}", text = text, isDone = false)
                    }
                    val newTask = Task(
                        projectId = projectId,
                        title = title,
                        description = desc,
                        assignedTo = assigned,
                        status = projectBuckets.firstOrNull() ?: "Not Started",
                        startDate = start,
                        dueDate = due,
                        color = color,
                        checklist = initialChecklist
                    )
                    firestore.collection("tasks").add(newTask.toFirestoreMap())

                    if (saveToDb && title.isNotBlank()) {
                        val newTemplate = TaskTemplate(
                            id = "tpl_" + title.lowercase().replace(" ", "_"),
                            title = title,
                            trade = title,
                            description = desc,
                            defaultChecklist = checklistItems,
                            colorHex = color
                        )
                        firestore.collection("task_templates").document(newTemplate.id).set(newTemplate.toFirestoreMap())
                    }
                }
            }
        )
    }

    if (showManageTemplatesDialog) {
        ManageTaskTemplatesDialog(
            templates = allTemplates,
            onDismiss = { showManageTemplatesDialog = false },
            onAddTemplate = { newTpl ->
                scope.launch {
                    firestore.collection("task_templates").document(newTpl.id).set(newTpl.toFirestoreMap())
                }
            },
            onDeleteTemplate = { tplId ->
                scope.launch {
                    firestore.collection("task_templates").document(tplId).delete()
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
            members = projectMembers,
            userMap = userMap,
            allBuckets = projectBuckets,
            onDismiss = { selectedTask = null },
            onSave = { updatedTask ->
                selectedTask = null
                scope.launch {
                    firestore.collection("tasks").document(updatedTask.id).set(updatedTask.toFirestoreMap())
                }
            },
            onDelete = { taskId ->
                selectedTask = null
                scope.launch {
                    firestore.collection("tasks").document(taskId).delete()
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
    Card(
        modifier = Modifier
            .width(300.dp)
            .fillMaxHeight(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Badge(containerColor = MaterialTheme.colorScheme.secondaryContainer) {
                    Text(tasks.size.toString())
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(tasks, key = { it.id }) { task ->
                    TaskCard(
                        task = task,
                        onClick = { onTaskClick(task) },
                        onMoveTask = { newStatus -> onMoveTask(task, newStatus) },
                        allBuckets = allBuckets,
                        userMap = userMap
                    )
                }
            }
        }
    }
}

@Composable
fun TaskCard(
    task: Task,
    onClick: () -> Unit,
    onMoveTask: (String) -> Unit,
    allBuckets: List<String>,
    userMap: Map<String, String>
) {
    var showMenu by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = Color(parseColor(task.color))),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Text(
                    text = task.title,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f)
                )

                Box {
                    IconButton(
                        onClick = { showMenu = true },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Move", modifier = Modifier.size(16.dp))
                    }

                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        allBuckets.filter { it != task.status }.forEach { targetBucket ->
                            DropdownMenuItem(
                                text = { Text("Move to $targetBucket") },
                                onClick = {
                                    showMenu = false
                                    onMoveTask(targetBucket)
                                }
                            )
                        }
                    }
                }
            }

            if (task.description.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = task.description,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val assignedName = userMap[task.assignedTo ?: ""] ?: "Unassigned"
                Text(
                    text = assignedName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )

                if (task.dueDate != null) {
                    Text(
                        text = formatDate(task.dueDate),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddTaskDialog(
    members: List<String>,
    userMap: Map<String, String>,
    templates: List<TaskTemplate>,
    onDismiss: () -> Unit,
    onConfirm: (title: String, desc: String, start: Long?, due: Long?, assigned: String?, color: String, saveToDb: Boolean, checklist: List<String>) -> Unit
) {
    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var assignedTo by remember { mutableStateOf<String?>(null) }
    var selectedColor by remember { mutableStateOf("#FFFFFF") }
    var saveToDb by remember { mutableStateOf(false) }
    var checklistItems by remember { mutableStateOf<List<String>>(emptyList()) }
    
    var startDate by remember { mutableStateOf<Long?>(null) }
    var dueDate by remember { mutableStateOf<Long?>(null) }
    
    var showTemplateDropdown by remember { mutableStateOf(false) }
    var showStartDatePicker by remember { mutableStateOf(false) }
    var showDueDatePicker by remember { mutableStateOf(false) }
    var showMemberPicker by remember { mutableStateOf(false) }

    val colors = listOf("#FFFFFF", "#FFCDD2", "#C8E6C9", "#BBDEFB", "#FFF9C4", "#E1BEE7", "#F5F5F5", "#212121")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New Project Task Card", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                // Template Selector Dropdown
                Column {
                    Text("Select from Master Trade Task Database:", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Box {
                        OutlinedButton(
                            onClick = { showTemplateDropdown = true },
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Default.Star, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(6.dp))
                            Text(
                                if (title.isEmpty()) "Choose Trade Template (Framing, Demo, Drywall, etc.)" else "Template: $title",
                                fontSize = 12.sp
                            )
                        }
                        DropdownMenu(
                            expanded = showTemplateDropdown,
                            onDismissRequest = { showTemplateDropdown = false },
                            modifier = Modifier.heightIn(max = 300.dp)
                        ) {
                            templates.forEach { tpl ->
                                DropdownMenuItem(
                                    text = {
                                        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                                            Text(tpl.title, fontWeight = FontWeight.Bold)
                                            Text(tpl.trade, fontSize = 11.sp, color = Color.Gray)
                                        }
                                    },
                                    onClick = {
                                        title = tpl.title
                                        description = tpl.description
                                        selectedColor = tpl.colorHex
                                        checklistItems = tpl.defaultChecklist
                                        showTemplateDropdown = false
                                    }
                                )
                            }
                        }
                    }
                }

                HorizontalDivider()

                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Task Title") },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, autoCorrectEnabled = true)
                )
                OutlinedTextField(
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

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = saveToDb,
                        onCheckedChange = { saveToDb = it }
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Save this task to Master Database for future projects", fontSize = 11.sp)
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
            Button(
                onClick = {
                    if (title.isNotBlank()) {
                        onConfirm(title, description, startDate, dueDate, assignedTo, selectedColor, saveToDb, checklistItems)
                    }
                },
                enabled = title.isNotBlank()
            ) {
                Text("Create Task Card")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
fun ManageTaskTemplatesDialog(
    templates: List<TaskTemplate>,
    onDismiss: () -> Unit,
    onAddTemplate: (TaskTemplate) -> Unit,
    onDeleteTemplate: (String) -> Unit
) {
    var newTitle by remember { mutableStateOf("") }
    var newTrade by remember { mutableStateOf("") }
    var newDesc by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Master Trade Task Database", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().height(400.dp)) {
                Text("Pre-built & Custom Task Templates (${templates.size} available):", fontSize = 12.sp, color = Color.Gray)
                
                LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(templates) { tpl ->
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(tpl.title, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                    Text(tpl.description, fontSize = 11.sp, color = Color.Gray, maxLines = 1)
                                }
                                if (!tpl.id.startsWith("tpl_")) {
                                    IconButton(onClick = { onDeleteTemplate(tpl.id) }) {
                                        Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color.Red, modifier = Modifier.size(18.dp))
                                    }
                                }
                            }
                        }
                    }
                }

                HorizontalDivider()
                Text("Add New Custom Trade Template:", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                OutlinedTextField(
                    value = newTitle,
                    onValueChange = { newTitle = it },
                    label = { Text("Task Card Title (e.g. Solar Panels)") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = newDesc,
                    onValueChange = { newDesc = it },
                    label = { Text("Short Description") },
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = {
                        if (newTitle.isNotBlank()) {
                            val newTpl = TaskTemplate(
                                id = "tpl_" + Clock.System.now().toEpochMilliseconds(),
                                title = newTitle,
                                trade = newTrade.ifEmpty { newTitle },
                                description = newDesc,
                                colorHex = "#38BDF8"
                            )
                            onAddTemplate(newTpl)
                            newTitle = ""
                            newTrade = ""
                            newDesc = ""
                        }
                    },
                    enabled = newTitle.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Add to Master Database")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

@Composable
fun ManageBucketsDialog(
    currentBuckets: List<String>,
    onDismiss: () -> Unit,
    onUpdateBuckets: (List<String>) -> Unit
) {
    var buckets by remember { mutableStateOf(currentBuckets.toMutableList()) }
    var newBucketName by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Manage Task Buckets") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = newBucketName,
                        onValueChange = { newBucketName = it },
                        label = { Text("New Bucket Name") },
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            if (newBucketName.isNotBlank() && !buckets.contains(newBucketName.trim())) {
                                buckets = (buckets + newBucketName.trim()).toMutableList()
                                newBucketName = ""
                            }
                        },
                        modifier = Modifier.align(Alignment.CenterVertically)
                    ) { Text("Add") }
                }

                LazyColumn {
                    items(buckets) { bucket ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(bucket)
                            if (buckets.size > 1) {
                                IconButton(onClick = { buckets = buckets.filter { it != bucket }.toMutableList() }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Delete")
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onUpdateBuckets(buckets) }) { Text("Save Buckets") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskDetailsDialog(
    task: Task,
    members: List<String>,
    userMap: Map<String, String>,
    allBuckets: List<String>,
    onDismiss: () -> Unit,
    onSave: (Task) -> Unit,
    onDelete: (String) -> Unit
) {
    var title by remember { mutableStateOf(task.title) }
    var description by remember { mutableStateOf(task.description) }
    var assignedTo by remember { mutableStateOf(task.assignedTo) }
    var status by remember { mutableStateOf(task.status) }
    var selectedColor by remember { mutableStateOf(task.color) }
    var checklist by remember { mutableStateOf(task.checklist) }
    var newCheckitemText by remember { mutableStateOf("") }

    var startDate by remember { mutableStateOf(task.startDate) }
    var dueDate by remember { mutableStateOf(task.dueDate) }

    var showStartDatePicker by remember { mutableStateOf(false) }
    var showDueDatePicker by remember { mutableStateOf(false) }
    var showMemberPicker by remember { mutableStateOf(false) }

    val colors = listOf("#FFFFFF", "#FFCDD2", "#C8E6C9", "#BBDEFB", "#FFF9C4", "#E1BEE7", "#F5F5F5", "#212121", "#38BDF8", "#EF4444", "#F59E0B", "#10B981")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Task Card Details & Subtasks", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Title") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Description") },
                    modifier = Modifier.fillMaxWidth()
                )

                // Assignee Picker
                Column {
                    Text("Assigned Crew Member", style = MaterialTheme.typography.labelMedium)
                    Box {
                        OutlinedButton(onClick = { showMemberPicker = true }, modifier = Modifier.fillMaxWidth()) {
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

                // Status Bucket
                Text("Status Bucket", style = MaterialTheme.typography.labelMedium)
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    allBuckets.forEach { bucket ->
                        FilterChip(
                            selected = status == bucket,
                            onClick = { status = bucket },
                            label = { Text(bucket) }
                        )
                    }
                }

                // Card Color Picker
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

                // Dates
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { showStartDatePicker = true }, modifier = Modifier.weight(1f)) {
                        Text(startDate?.let { "Start: ${formatDate(it)}" } ?: "Set Start Date")
                    }
                    Button(onClick = { showDueDatePicker = true }, modifier = Modifier.weight(1f)) {
                        Text(dueDate?.let { "Due: ${formatDate(it)}" } ?: "Set Due Date")
                    }
                }

                // Checklist Editing
                HorizontalDivider()
                Text("Checklist & Steps (${checklist.count { it.isDone }}/${checklist.size}):", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                checklist.forEachIndexed { idx, item ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Checkbox(
                                checked = item.isDone,
                                onCheckedChange = { isDone ->
                                    val updated = checklist.toMutableList()
                                    updated[idx] = item.copy(isDone = isDone)
                                    checklist = updated
                                }
                            )
                            Text(item.text, fontSize = 12.sp)
                        }
                        IconButton(onClick = {
                            checklist = checklist.filterIndexed { i, _ -> i != idx }
                        }) {
                            Icon(Icons.Default.Delete, contentDescription = "Remove Step", tint = Color.Red, modifier = Modifier.size(16.dp))
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newCheckitemText,
                        onValueChange = { newCheckitemText = it },
                        label = { Text("Add New Checklist Step") },
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(6.dp))
                    Button(onClick = {
                        if (newCheckitemText.isNotBlank()) {
                            checklist = checklist + ChecklistItem(id = "chk_" + Clock.System.now().toEpochMilliseconds(), text = newCheckitemText.trim(), isDone = false)
                            newCheckitemText = ""
                        }
                    }) { Text("Add") }
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
            Button(onClick = {
                val updated = task.copy(
                    title = title,
                    description = description,
                    assignedTo = assignedTo,
                    status = status,
                    color = selectedColor,
                    startDate = startDate,
                    dueDate = dueDate,
                    checklist = checklist
                )
                onSave(updated)
            }) { Text("Save Changes") }
        },
        dismissButton = {
            TextButton(onClick = { onDelete(task.id) }, colors = ButtonDefaults.textButtonColors(contentColor = Color.Red)) {
                Text("Delete Card")
            }
        }
    )
}

fun formatDate(millis: Long): String {
    val instant = Instant.fromEpochMilliseconds(millis)
    val date = instant.toLocalDateTime(TimeZone.currentSystemDefault()).date
    return "${date.monthNumber}/${date.dayOfMonth}/${date.year}"
}

fun parseColor(hex: String): Long {
    return try {
        val clean = hex.removePrefix("#")
        if (clean.length == 6) ("FF$clean").toLong(16) else clean.toLong(16)
    } catch (_: Exception) {
        0xFFFFFFFF
    }
}
