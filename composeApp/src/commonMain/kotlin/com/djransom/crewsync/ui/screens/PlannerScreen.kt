package com.djransom.crewsync.ui.screens

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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.djransom.crewsync.data.model.ChecklistGroup
import com.djransom.crewsync.data.model.ChecklistItem
import com.djransom.crewsync.data.model.DEFAULT_TASK_TEMPLATES
import com.djransom.crewsync.data.model.ProjectFile
import com.djransom.crewsync.data.model.Task
import com.djransom.crewsync.data.model.TaskTemplate
import com.djransom.crewsync.data.model.User
import com.djransom.crewsync.ui.components.ReorderableColumn
import com.djransom.crewsync.util.*
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.auth.auth
import dev.gitlive.firebase.firestore.firestore
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlannerScreen(projectId: String, environmentId: String, projectName: String, projectBuckets: List<String>, projectMembers: List<String>) {
    val firestore = Firebase.firestore
    val scope = rememberCoroutineScope()

    var showAddTaskDialog by remember { mutableStateOf(false) }
    var showManageBucketsDialog by remember { mutableStateOf(false) }
    var showManageTemplatesDialog by remember { mutableStateOf(false) }
    var showSummaryDialog by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var selectedTask by remember { mutableStateOf<Task?>(null) }
    var pendingTaskId by remember { mutableStateOf<String?>(null) }
    var pendingSaveTask by remember { mutableStateOf<Task?>(null) }
    var pendingDeleteTaskId by remember { mutableStateOf<String?>(null) }
    // rememberSaveable, not remember - matches selectedTab in ProjectDetailsScreen: a rotation
    // shouldn't silently bounce the user from the outline view back to the board.
    var viewMode by rememberSaveable { mutableStateOf("Board") }

    val tasksFlow = remember(projectId, environmentId) {
        if (environmentId.isEmpty()) return@remember kotlinx.coroutines.flow.flowOf(emptyList())
        firestore.collection("tasks")
            .where { "environmentId" equalTo environmentId }
            .snapshots
            .map { snapshot ->
                snapshot.documents.mapNotNull { doc ->
                    try {
                        doc.toTaskSafe()
                    } catch (e: Exception) { null }
                }.filter { it.projectId == projectId }
            }
            .catch { emit(emptyList()) }
    }
    val tasks by tasksFlow.collectAsState(initial = emptyList())

    val allUsersFlow = remember(environmentId) {
        if (environmentId.isEmpty()) return@remember kotlinx.coroutines.flow.flowOf(emptyList())
        firestore.collection("users")
            .where { "environmentIds" contains environmentId }
            .snapshots
            .map { snapshot ->
                snapshot.documents.mapNotNull { doc ->
                    try {
                        doc.data<User>().let { if (it.email.isEmpty()) it.copy(email = doc.id) else it }
                    } catch (e: Exception) { null }
                }
            }
            .catch { emit(emptyList()) }
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

    val visibleTasks = remember(tasks, searchQuery) {
        if (searchQuery.isBlank()) tasks
        else tasks.filter {
            it.title.contains(searchQuery, ignoreCase = true) || it.description.contains(searchQuery, ignoreCase = true)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search cards...", fontSize = 13.sp) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear search", modifier = Modifier.size(16.dp))
                        }
                    }
                },
                singleLine = true,
                textStyle = LocalTextStyle.current.copy(fontSize = 13.sp),
                modifier = Modifier.weight(1f).height(52.dp)
            )
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = { showSummaryDialog = true }) {
                Icon(Icons.AutoMirrored.Filled.List, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Project Summary", fontSize = 12.sp)
            }
            Spacer(Modifier.width(8.dp))
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
            Spacer(Modifier.width(8.dp))
            SegmentedButtonRow(viewMode = viewMode, onViewModeChange = { viewMode = it })
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = { printPlannerOutline(projectName, projectBuckets, visibleTasks) }) {
                Icon(Icons.Default.Send, contentDescription = "Print Planner")
            }
        }

        Box(modifier = Modifier.weight(1f)) {
            when (viewMode) {
                "List" -> PlannerListView(
                    buckets = projectBuckets,
                    tasks = visibleTasks,
                    userMap = userMap,
                    onTaskClick = { selectedTask = it },
                    onToggleChecklistItem = { task, groupId, itemId, checked ->
                        val updatedGroups = task.checklistGroups.map { group ->
                            if (group.id == groupId) {
                                group.copy(items = group.items.map { item ->
                                    if (item.id == itemId) item.copy(isDone = checked) else item
                                })
                            } else group
                        }
                        scope.launch {
                            firestore.collection("tasks").document(task.id)
                                .set(task.copy(checklistGroups = updatedGroups).toFirestoreMap())
                        }
                    },
                    onReorderTasks = { reorderedBucketTasks ->
                        scope.launch {
                            // Sequential index per bucket is enough - order only ever gets
                            // compared within a single status bucket (sortedBy { it.order }
                            // after filtering by status), so values overlapping across different
                            // buckets is fine.
                            reorderedBucketTasks.forEachIndexed { index, task ->
                                firestore.collection("tasks").document(task.id).update("order" to index.toLong().toDouble())
                            }
                        }
                    }
                )
                else -> LazyRow(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    items(projectBuckets) { bucketName ->
                        val columnTasks = visibleTasks.filter { it.status == bucketName }.sortedBy { it.order }
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
                        environmentId = environmentId,
                        projectId = projectId,
                        title = title,
                        description = desc,
                        assignedTo = assignedList.firstOrNull(),
                        assignedMembers = assignedList,
                        status = projectBuckets.firstOrNull() ?: "Not Started",
                        startDate = start,
                        dueDate = due,
                        color = color,
                        checklistGroups = initialGroups,
                        order = Clock.System.now().toEpochMilliseconds() // sorts new cards to the end of their bucket by default
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

    if (showSummaryDialog) {
        ProjectSummaryDialog(
            tasks = tasks,
            onDismiss = { showSummaryDialog = false },
            onTaskClick = { task ->
                pendingTaskId = task.id
                showSummaryDialog = false
            }
        )
    }

    // The summary dialog must fully unmount before the details dialog mounts - closing one
    // Dialog and opening another in the same click handler (same recomposition frame) left the
    // web target's canvas in an unresponsive state (every click after silently no-opped). This
    // effect only opens the details dialog once showSummaryDialog has actually gone false and
    // that recomposition has committed, one frame later.
    LaunchedEffect(pendingTaskId, showSummaryDialog) {
        if (pendingTaskId != null && !showSummaryDialog) {
            selectedTask = tasks.find { it.id == pendingTaskId }
            pendingTaskId = null
        }
    }

    // Same fix as above, applied to Save/Delete: closing this dialog cancels a burst of its
    // own child coroutines (ripple/animation/text-field state) in the same frame that used to
    // also launch a brand-new Firestore-write coroutine on the parent scope. That collision -
    // a pile of children completing/cancelling at once while a new one starts - is exactly the
    // shape of thing that was landing inside kotlinx-coroutines' own JobSupport.tryMakeCompleting/
    // finalizeFinishingState and throwing "RangeError: Invalid array length" there, wedging the
    // whole recomposition loop. Deferring the write to the next frame, after the dialog has
    // actually finished tearing down, avoids the collision.
    LaunchedEffect(pendingSaveTask) {
        pendingSaveTask?.let { updatedTask ->
            firestore.collection("tasks").document(updatedTask.id).set(updatedTask.toFirestoreMap())
            pendingSaveTask = null
        }
    }
    LaunchedEffect(pendingDeleteTaskId) {
        pendingDeleteTaskId?.let { taskId ->
            firestore.collection("tasks").document(taskId).delete()
            pendingDeleteTaskId = null
        }
    }

    if (selectedTask != null) {
        TaskDetailsDialog(
            task = selectedTask!!,
            projectId = projectId,
            members = projectMembers,
            userMap = userMap,
            allBuckets = projectBuckets,
            onDismiss = { selectedTask = null },
            onSave = { updatedTask ->
                selectedTask = null
                pendingSaveTask = updatedTask
            },
            onDelete = { taskId ->
                selectedTask = null
                pendingDeleteTaskId = taskId
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

@Composable
fun SegmentedButtonRow(viewMode: String, onViewModeChange: (String) -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
    ) {
        listOf("Board", "List").forEach { mode ->
            val selected = viewMode == mode
            Box(
                modifier = Modifier
                    .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                    .clickable { onViewModeChange(mode) }
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Text(
                    mode,
                    fontSize = 12.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// The outline/list view: every task grouped by its status bucket, each expandable to show its
// checklist items with interactive checkboxes right there (no need to open the task's edit
// dialog just to check a step off) plus a completion percentage per task and overall.
@Composable
fun PlannerListView(
    buckets: List<String>,
    tasks: List<Task>,
    userMap: Map<String, String>,
    onTaskClick: (Task) -> Unit,
    onToggleChecklistItem: (task: Task, groupId: String, itemId: String, checked: Boolean) -> Unit,
    onReorderTasks: (List<Task>) -> Unit
) {
    val overallDone = tasks.count { isDoneStatus(it.status) }
    val overallTotal = tasks.size
    val overallProgress = if (overallTotal > 0) overallDone.toFloat() / overallTotal else 0f

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        item {
            Column(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Overall Progress", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text(
                        "${(overallProgress * 100).roundToInt()}%  ($overallDone/$overallTotal tasks)",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { overallProgress },
                    modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(50))
                )
            }
        }

        if (tasks.isEmpty()) {
            item {
                Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text("No task cards yet.", color = Color.Gray)
                }
            }
        }

        buckets.forEach { bucket ->
            val bucketTasks = tasks.filter { it.status == bucket }.sortedBy { it.order }
            if (bucketTasks.isNotEmpty()) {
                item(key = "bucket_$bucket") {
                    Text(
                        text = "$bucket (${bucketTasks.size})",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }
                // A plain (non-lazy) ReorderableColumn nested in a single LazyColumn item slot -
                // it manages its own drag state internally and only needs a bounded list, which
                // "one bucket's tasks" is (see ReorderableColumn's own doc comment).
                item(key = "bucket_body_$bucket") {
                    ReorderableColumn(
                        items = bucketTasks,
                        onReorder = { reordered -> onReorderTasks(reordered) },
                        rowHeight = 56.dp
                    ) { task, dragHandleModifier ->
                        PlannerOutlineTaskRow(
                            task = task,
                            userMap = userMap,
                            onTaskClick = { onTaskClick(task) },
                            onToggleChecklistItem = { groupId, itemId, checked -> onToggleChecklistItem(task, groupId, itemId, checked) },
                            dragHandleModifier = dragHandleModifier
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun PlannerOutlineTaskRow(
    task: Task,
    userMap: Map<String, String>,
    onTaskClick: () -> Unit,
    onToggleChecklistItem: (groupId: String, itemId: String, checked: Boolean) -> Unit,
    dragHandleModifier: Modifier = Modifier
) {
    var expanded by remember(task.id) { mutableStateOf(false) }
    val items = remember(task) { task.allChecklistItems() }
    val doneCount = items.count { it.isDone }
    val percent = remember(task) { taskCompletionPercent(task) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { if (items.isNotEmpty()) expanded = !expanded }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Menu,
                    contentDescription = "Drag to reorder within ${task.status}",
                    modifier = dragHandleModifier.size(18.dp),
                    tint = Color.Gray
                )
                Spacer(Modifier.width(6.dp))

                if (items.isNotEmpty()) {
                    Icon(
                        if (expanded) Icons.Default.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = if (expanded) "Collapse" else "Expand",
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                } else {
                    Spacer(Modifier.width(24.dp))
                }

                Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(Color(parseColor(task.color))))
                Spacer(Modifier.width(8.dp))

                Column(modifier = Modifier.weight(1f).clickable { onTaskClick() }) {
                    Text(
                        text = task.title,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold,
                        textDecoration = if (isDoneStatus(task.status)) TextDecoration.LineThrough else null
                    )
                    val assignedList = task.getAllAssignedEmails()
                    val subtitle = buildString {
                        append(task.status)
                        if (assignedList.isNotEmpty()) append("  -  " + assignedList.joinToString(", ") { userMap[it] ?: it })
                    }
                    Text(subtitle, style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                }

                if (items.isNotEmpty()) {
                    Spacer(Modifier.width(8.dp))
                    Text("$doneCount/${items.size}", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    "$percent%",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (percent >= 100) Color(0xFF10B981) else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.width(44.dp),
                    textAlign = TextAlign.End
                )
            }

            if (expanded) {
                Column(modifier = Modifier.padding(start = 44.dp, end = 12.dp, bottom = 8.dp)) {
                    task.checklistGroups.forEach { group ->
                        if (group.items.isNotEmpty()) {
                            Text(
                                group.title,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color.Gray,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                            group.items.forEach { item ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onToggleChecklistItem(group.id, item.id, !item.isDone) },
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Checkbox(
                                        checked = item.isDone,
                                        onCheckedChange = { checked -> onToggleChecklistItem(group.id, item.id, checked) }
                                    )
                                    Text(
                                        item.text,
                                        style = MaterialTheme.typography.bodyMedium,
                                        textDecoration = if (item.isDone) TextDecoration.LineThrough else null,
                                        color = if (item.isDone) Color.Gray else MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// A single pull-up view of every task card on the board, sorted so due-dated cards
// lead (earliest due date first) and undated cards follow alphabetically, with
// Done cards pushed to the bottom of the list regardless of due date - a clean
// read on overall project progress without hunting across bucket columns.
// (isDoneStatus lives in util/TaskUtils.kt - shared with PlannerListView's progress bar above.)
@Composable
fun ProjectSummaryDialog(tasks: List<Task>, onDismiss: () -> Unit, onTaskClick: (Task) -> Unit) {
    val now = remember { Clock.System.now().toEpochMilliseconds() }
    val total = tasks.size
    val done = tasks.count { isDoneStatus(it.status) }

    val sortedTasks = remember(tasks) {
        fun sortByDueDateThenTitle(list: List<Task>): List<Task> {
            val (dated, undated) = list.partition { it.dueDate != null }
            return dated.sortedBy { it.dueDate } + undated.sortedBy { it.title.lowercase() }
        }
        val (doneTasks, activeTasks) = tasks.partition { isDoneStatus(it.status) }
        sortByDueDateThenTitle(activeTasks) + sortByDueDateThenTitle(doneTasks)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Project Summary", fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.heightIn(max = 480.dp)) {
                val progress = if (total > 0) done.toFloat() / total.toFloat() else 0f
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(50))
                )
                Spacer(Modifier.height(4.dp))
                Text("$done / $total task cards done", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                Spacer(Modifier.height(8.dp))

                if (sortedTasks.isEmpty()) {
                    Text("No task cards yet.", color = Color.Gray)
                } else {
                    LazyColumn {
                        items(sortedTasks, key = { it.id }) { task ->
                            val isDone = isDoneStatus(task.status)
                            val isOverdue = !isDone && task.dueDate != null && task.dueDate < now
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onTaskClick(task) }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(Color(parseColor(task.color))))
                                Spacer(Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = task.title,
                                        style = MaterialTheme.typography.bodyMedium,
                                        textDecoration = if (isDone) TextDecoration.LineThrough else null,
                                        color = if (isDone) Color.Gray else MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(task.status, style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                                }
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = task.dueDate?.let { formatDate(it) } ?: "No due date",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (isOverdue) MaterialTheme.colorScheme.error else Color.Gray
                                )
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
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
                    itemsIndexed(buckets) { index, bucket ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(bucket, modifier = Modifier.weight(1f))
                            IconButton(
                                onClick = {
                                    buckets = buckets.toMutableList().apply {
                                        add(index - 1, removeAt(index))
                                    }
                                },
                                enabled = index > 0
                            ) {
                                Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Move up")
                            }
                            IconButton(
                                onClick = {
                                    buckets = buckets.toMutableList().apply {
                                        add(index + 1, removeAt(index))
                                    }
                                },
                                enabled = index < buckets.size - 1
                            ) {
                                Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Move down")
                            }
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
    projectId: String,
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
    var isUploadingPhoto by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun uploadAttachment(pickedFile: PickedFile) {
        scope.launch {
            isUploadingPhoto = true
            try {
                val path = "task_attachments/$projectId/${task.id}/${Clock.System.now().toEpochMilliseconds()}_${pickedFile.name}"
                val downloadUrl = uploadFile(path, pickedFile.platformFile)
                attachments = attachments + ProjectFile(
                    id = "att_" + Clock.System.now().toEpochMilliseconds(),
                    name = pickedFile.name,
                    url = downloadUrl,
                    uploadedBy = Firebase.auth.currentUser?.email ?: "Unknown",
                    uploadedAt = Clock.System.now().toEpochMilliseconds()
                )
            } catch (_: Exception) {
            } finally {
                isUploadingPhoto = false
            }
        }
    }

    val filePickerLauncher = rememberFilePickerLauncher { uploadAttachment(it) }
    val cameraLauncher = rememberCameraLauncher { uploadAttachment(it) }

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
                                        singleLine = false,
                                        maxLines = 5
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
                                    singleLine = false,
                                    maxLines = 5
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

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { cameraLauncher() },
                        enabled = !isUploadingPhoto,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                    ) {
                        Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Camera")
                    }
                    Button(
                        onClick = { filePickerLauncher() },
                        enabled = !isUploadingPhoto,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Gallery / Browse")
                    }
                    if (isUploadingPhoto) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
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
