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
import com.example.crewsync.data.model.ChecklistGroup
import com.example.crewsync.data.model.ChecklistItem
import com.example.crewsync.data.model.DEFAULT_TASK_TEMPLATES
import com.example.crewsync.data.model.ProjectFile
import com.example.crewsync.data.model.Task
import com.example.crewsync.data.model.TaskTemplate
import com.example.crewsync.data.model.User
import com.example.crewsync.ui.components.ReorderableColumn
import com.example.crewsync.util.*
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.auth.auth
import dev.gitlive.firebase.firestore.firestore
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
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
            onConfirm = { title, desc, start, due, assignedList, color, saveToDb, checklistItems ->
                showAddTaskDialog = false
                scope.launch {
                    // A new task starts with at most one checklist group, seeded from the
                    // chosen template (if any) - additional groups can be added afterward from
                    // the card's edit dialog, which is where the fuller multi-checklist
                    // authoring UX lives.
                    val initialGroups = if (checklistItems.isEmpty()) {
                        emptyList()
                    } else {
                        listOf(
                            ChecklistGroup(
                                id = "chk_grp_${Clock.System.now().toEpochMilliseconds()}",
                                title = "Checklist",
                                items = checklistItems.mapIndexed { idx, text ->
                                    ChecklistItem(id = "chk_${idx}_${Clock.System.now().toEpochMilliseconds()}", text = text, isDone = false)
                                }
                            )
                        )
                    }
                    val newTask = Task(
                        projectId = projectId,
                        title = title,
                        description = desc,
                        assignedTo = assignedList.firstOrNull(),
                        assignedMembers = assignedList,
                        status = projectBuckets.firstOrNull() ?: "Not Started",
                        startDate = start,
                        dueDate = due,
                        color = color,
                        checklistGroups = initialGroups
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
            .width(310.dp)
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
    val assignedList = remember(task) { task.getAllAssignedEmails() }

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

            val allItems = task.allChecklistItems()
            if (allItems.isNotEmpty()) {
                val doneCount = allItems.count { it.isDone }
                Spacer(modifier = Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("$doneCount/${allItems.size} steps done", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                }
            }

            if (task.attachments.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(14.dp), tint = Color(0xFFE11D48))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("${task.attachments.size} site photos", fontSize = 11.sp, color = Color(0xFFE11D48))
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    if (assignedList.isEmpty()) {
                        Text("Unassigned", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                    } else {
                        val namesStr = assignedList.joinToString(", ") { userMap[it] ?: it }
                        Text(
                            text = "Assigned: $namesStr",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1
                        )
                    }
                }

                if (task.dueDate != null) {
                    Spacer(modifier = Modifier.width(6.dp))
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
    onConfirm: (title: String, desc: String, start: Long?, due: Long?, assignedList: List<String>, color: String, saveToDb: Boolean, checklist: List<String>) -> Unit
) {
    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var selectedMembers by remember { mutableStateOf<List<String>>(emptyList()) }
    var selectedColor by remember { mutableStateOf("#FFFFFF") }
    var saveToDb by remember { mutableStateOf(false) }
    var checklistItems by remember { mutableStateOf<List<String>>(emptyList()) }
    
    var startDate by remember { mutableStateOf<Long?>(null) }
    var dueDate by remember { mutableStateOf<Long?>(null) }
    
    var showTemplateDropdown by remember { mutableStateOf(false) }
    var showStartDatePicker by remember { mutableStateOf(false) }
    var showDueDatePicker by remember { mutableStateOf(false) }

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
                
                // Multi-Assignee Selection
                Column {
                    Text("Assign Team Members (${selectedMembers.size}):", style = MaterialTheme.typography.labelMedium)
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        members.forEach { email ->
                            val name = userMap[email] ?: email
                            val isAssigned = selectedMembers.contains(email)
                            FilterChip(
                                selected = isAssigned,
                                onClick = {
                                    selectedMembers = if (isAssigned) selectedMembers.filter { it != email } else selectedMembers + email
                                },
                                label = { Text(name, fontSize = 11.sp) }
                            )
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
                val state = rememberDatePickerState(initialSelectedDateMillis = startDate?.let { toPickerMillis(it) })
                DatePickerDialog(
                    onDismissRequest = { showStartDatePicker = false },
                    confirmButton = {
                        TextButton(onClick = {
                            state.selectedDateMillis?.let { startDate = fromPickerMillis(it) }
                            showStartDatePicker = false
                        }) { Text("OK") }
                    }
                ) { DatePicker(state = state) }
            }

            if (showDueDatePicker) {
                val state = rememberDatePickerState(initialSelectedDateMillis = dueDate?.let { toPickerMillis(it) })
                DatePickerDialog(
                    onDismissRequest = { showDueDatePicker = false },
                    confirmButton = {
                        TextButton(onClick = {
                            state.selectedDateMillis?.let { dueDate = fromPickerMillis(it) }
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
                        onConfirm(title, description, startDate, dueDate, selectedMembers, selectedColor, saveToDb, checklistItems)
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
    var selectedMembers by remember { mutableStateOf(task.getAllAssignedEmails()) }
    var status by remember { mutableStateOf(task.status) }
    var selectedColor by remember { mutableStateOf(task.color) }
    var checklistGroups by remember { mutableStateOf(task.checklistGroups) }
    var newGroupTitle by remember { mutableStateOf("") }
    val newItemTextByGroup = remember { mutableStateMapOf<String, String>() }
    var attachments by remember { mutableStateOf(task.attachments) }
    var newPhotoName by remember { mutableStateOf("") }

    var startDate by remember { mutableStateOf(task.startDate) }
    var dueDate by remember { mutableStateOf(task.dueDate) }

    var showStartDatePicker by remember { mutableStateOf(false) }
    var showDueDatePicker by remember { mutableStateOf(false) }

    val colors = listOf("#FFFFFF", "#FFCDD2", "#C8E6C9", "#BBDEFB", "#FFF9C4", "#E1BEE7", "#F5F5F5", "#212121", "#38BDF8", "#EF4444", "#F59E0B", "#10B981")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Task Card Details & Site Photos", fontWeight = FontWeight.Bold) },
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

                // Multi-Assignee Picker
                Column {
                    Text("Assigned Crew Members (${selectedMembers.size}):", style = MaterialTheme.typography.labelMedium)
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        members.forEach { email ->
                            val name = userMap[email] ?: email
                            val isAssigned = selectedMembers.contains(email)
                            FilterChip(
                                selected = isAssigned,
                                onClick = {
                                    selectedMembers = if (isAssigned) selectedMembers.filter { it != email } else selectedMembers + email
                                },
                                label = { Text(name, fontSize = 11.sp) }
                            )
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

                // Checklist Groups - a card can hold several named checklists (e.g. "Materials",
                // "Safety"), each with its own drag-reorderable items.
                HorizontalDivider()
                val allChecklistItems = checklistGroups.flatMap { it.items }
                Text(
                    "Checklists (${allChecklistItems.count { it.isDone }}/${allChecklistItems.size} steps done):",
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp
                )

                checklistGroups.forEach { group ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(
                                    value = group.title,
                                    onValueChange = { newTitle ->
                                        checklistGroups = checklistGroups.map { if (it.id == group.id) it.copy(title = newTitle) else it }
                                    },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true,
                                    textStyle = MaterialTheme.typography.labelLarge
                                )
                                Text(
                                    "${group.items.count { it.isDone }}/${group.items.size}",
                                    fontSize = 11.sp,
                                    color = Color.Gray,
                                    modifier = Modifier.padding(horizontal = 6.dp)
                                )
                                IconButton(onClick = {
                                    checklistGroups = checklistGroups.filter { it.id != group.id }
                                    newItemTextByGroup.remove(group.id)
                                }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Remove Checklist \"${group.title}\"", tint = Color.Red, modifier = Modifier.size(18.dp))
                                }
                            }

                            ReorderableColumn(
                                items = group.items,
                                onReorder = { reordered ->
                                    checklistGroups = checklistGroups.map { if (it.id == group.id) it.copy(items = reordered) else it }
                                }
                            ) { item, dragHandleModifier ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Menu,
                                        contentDescription = "Drag to reorder",
                                        modifier = dragHandleModifier.size(18.dp),
                                        tint = Color.Gray
                                    )
                                    Checkbox(
                                        checked = item.isDone,
                                        onCheckedChange = { isDone ->
                                            val updatedItems = group.items.map { if (it.id == item.id) it.copy(isDone = isDone) else it }
                                            checklistGroups = checklistGroups.map { if (it.id == group.id) it.copy(items = updatedItems) else it }
                                        }
                                    )
                                    OutlinedTextField(
                                        value = item.text,
                                        onValueChange = { updatedText ->
                                            val updatedItems = group.items.map { if (it.id == item.id) it.copy(text = updatedText) else it }
                                            checklistGroups = checklistGroups.map { if (it.id == group.id) it.copy(items = updatedItems) else it }
                                        },
                                        modifier = Modifier.weight(1f),
                                        singleLine = true
                                    )
                                    IconButton(onClick = {
                                        val updatedItems = group.items.filter { it.id != item.id }
                                        checklistGroups = checklistGroups.map { if (it.id == group.id) it.copy(items = updatedItems) else it }
                                    }) {
                                        Icon(Icons.Default.Delete, contentDescription = "Remove Step", tint = Color.Red, modifier = Modifier.size(18.dp))
                                    }
                                }
                            }

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(
                                    value = newItemTextByGroup[group.id] ?: "",
                                    onValueChange = { newItemTextByGroup[group.id] = it },
                                    label = { Text("Add Step") },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true
                                )
                                Spacer(Modifier.width(6.dp))
                                Button(onClick = {
                                    val text = (newItemTextByGroup[group.id] ?: "").trim()
                                    if (text.isNotBlank()) {
                                        val newItem = ChecklistItem(id = "chk_" + Clock.System.now().toEpochMilliseconds(), text = text, isDone = false)
                                        checklistGroups = checklistGroups.map { if (it.id == group.id) it.copy(items = it.items + newItem) else it }
                                        newItemTextByGroup[group.id] = ""
                                    }
                                }) { Text("Add") }
                            }
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newGroupTitle,
                        onValueChange = { newGroupTitle = it },
                        label = { Text("New Checklist Name (e.g. Materials, Safety)") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    Spacer(Modifier.width(6.dp))
                    Button(onClick = {
                        val title = newGroupTitle.trim().ifBlank { "Checklist ${checklistGroups.size + 1}" }
                        checklistGroups = checklistGroups + ChecklistGroup(id = "chk_grp_" + Clock.System.now().toEpochMilliseconds(), title = title)
                        newGroupTitle = ""
                    }) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Add Checklist")
                    }
                }

                // Site Photos & Attachments
                HorizontalDivider()
                Text("Project Site Photos (${attachments.size}):", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newPhotoName,
                        onValueChange = { newPhotoName = it },
                        label = { Text("Photo Title / Description") },
                        placeholder = { Text("e.g. Rough framing inspection photo") },
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(6.dp))
                    Button(
                        onClick = {
                            val titleStr = newPhotoName.ifBlank { "Site Photo ${attachments.size + 1}" }
                            val newAtt = ProjectFile(
                                id = "att_" + Clock.System.now().toEpochMilliseconds(),
                                name = "$titleStr.jpg",
                                url = "",
                                uploadedBy = "Crew Member",
                                uploadedAt = Clock.System.now().toEpochMilliseconds()
                            )
                            attachments = attachments + newAtt
                            newPhotoName = ""
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Attach Photo")
                    }
                }

                attachments.forEach { file ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Add, contentDescription = null, tint = Color(0xFFE11D48), modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(file.name, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                            IconButton(onClick = { attachments = attachments.filter { it.id != file.id } }) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete Photo", tint = Color.Red, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }

            if (showStartDatePicker) {
                val state = rememberDatePickerState(initialSelectedDateMillis = startDate?.let { toPickerMillis(it) })
                DatePickerDialog(
                    onDismissRequest = { showStartDatePicker = false },
                    confirmButton = {
                        TextButton(onClick = {
                            state.selectedDateMillis?.let { startDate = fromPickerMillis(it) }
                            showStartDatePicker = false
                        }) { Text("OK") }
                    }
                ) { DatePicker(state = state) }
            }

            if (showDueDatePicker) {
                val state = rememberDatePickerState(initialSelectedDateMillis = dueDate?.let { toPickerMillis(it) })
                DatePickerDialog(
                    onDismissRequest = { showDueDatePicker = false },
                    confirmButton = {
                        TextButton(onClick = {
                            state.selectedDateMillis?.let { dueDate = fromPickerMillis(it) }
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
                    assignedTo = selectedMembers.firstOrNull(),
                    assignedMembers = selectedMembers,
                    status = status,
                    color = selectedColor,
                    startDate = startDate,
                    dueDate = dueDate,
                    checklistGroups = checklistGroups,
                    attachments = attachments
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

// Material3's DatePicker always represents the selected day as UTC-midnight millis
// internally, regardless of the device's real timezone - see AppointmentDialog in
// CalendarScreen.kt for the same pattern already used there. Task.startDate/dueDate, on the
// other hand, are read everywhere else in this app (formatDate above, isSameDay,
// timestampToDate in CalendarScreen.kt) as local-midnight millis via
// TimeZone.currentSystemDefault(). Passing/reading DatePicker's raw millis directly - which
// the Task dialogs used to do - silently shifts the picked date back a day in any negative
// UTC-offset timezone the moment you hit OK. These two helpers convert between the two
// conventions so the picker always shows the right day and writes back the right day.
fun toPickerMillis(storedMillis: Long): Long =
    Instant.fromEpochMilliseconds(storedMillis).toLocalDateTime(TimeZone.currentSystemDefault()).date
        .atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds()

fun fromPickerMillis(pickerMillis: Long): Long =
    Instant.fromEpochMilliseconds(pickerMillis).toLocalDateTime(TimeZone.UTC).date
        .atStartOfDayIn(TimeZone.currentSystemDefault()).toEpochMilliseconds()

fun parseColor(hex: String): Long {
    return try {
        val clean = hex.removePrefix("#")
        if (clean.length == 6) ("FF$clean").toLong(16) else clean.toLong(16)
    } catch (_: Exception) {
        0xFFFFFFFF
    }
}
