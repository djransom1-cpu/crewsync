package com.example.crewsync.ui.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.example.crewsync.data.model.Project
import com.example.crewsync.data.model.User
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.auth.auth
import dev.gitlive.firebase.firestore.firestore
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(onLogout: () -> Unit, onProjectClick: (String) -> Unit) {
    val firestore = Firebase.firestore
    val auth = Firebase.auth
    val scope = rememberCoroutineScope()
    
    var userProfile by remember { mutableStateOf<User?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }

    LaunchedEffect(auth.currentUser?.uid) {
        auth.currentUser?.uid?.let { uid ->
            firestore.collection("users").document(uid).snapshots.collect { snap ->
                if (snap.exists) {
                    try {
                        userProfile = snap.data<User>()
                    } catch (e: Exception) {}
                }
            }
        }
    }
    
    val isSuperAdmin = userProfile?.role == "SuperAdmin"
    val isAdmin = userProfile?.role == "Admin" || isSuperAdmin
    val currentUserEmail = auth.currentUser?.email?.lowercase() ?: ""
    val viewMode = userProfile?.dashboardViewMode ?: "Cards"

    // Real-time projects with reactive filtering
    val projects by remember(isAdmin, auth.currentUser?.uid) {
        firestore.collection("projects")
            .snapshots
            .map { snapshot -> 
                snapshot.documents.mapNotNull { doc -> 
                    try {
                        doc.data<Project>().copy(id = doc.id)
                    } catch (e: Exception) { null }
                }.filter { proj ->
                    isAdmin || 
                    proj.teamLeaderId == auth.currentUser?.uid || 
                    proj.members.any { it.lowercase() == currentUserEmail }
                }
            }
    }.collectAsState(initial = emptyList())

    // Sorted projects based on user's projectOrder
    val sortedProjects = remember(projects, userProfile?.projectOrder) {
        val order = userProfile?.projectOrder ?: emptyList()
        projects.sortedWith(compareBy({ 
            val idx = order.indexOf(it.id)
            if (idx == -1) Int.MAX_VALUE else idx 
        }, { it.createdAt }))
    }

    // For SuperAdmin diagnostic info
    val allUsersCount by if (isSuperAdmin) {
        firestore.collection("users").snapshots.map { it.documents.size }.collectAsState(0)
    } else {
        mutableStateOf(0)
    }
    
    val rawProjectsCount by firestore.collection("projects").snapshots.map { it.documents.size }.collectAsState(0)

    // Offline Status
    val isOnline by com.example.crewsync.util.rememberConnectivityState()

    // Check for Desktop Updates
    val latestVersionInfo by firestore.collection("app_config").document("desktop").snapshots
        .map { if (it.exists) it.data<Map<String, String>>() else null }
        .collectAsState(initial = null)

    val currentVersion = com.example.crewsync.util.getAppVersion()
    val needsUpdate = remember(latestVersionInfo) {
        val latest = latestVersionInfo?.get("version") ?: ""
        latest.isNotEmpty() && latest != currentVersion
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Dashboard") },
                actions = {
                    IconButton(onClick = onLogout) {
                        Icon(Icons.Default.ExitToApp, contentDescription = "Logout")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimary
                ),
                windowInsets = WindowInsets.statusBars
            )
        },
        floatingActionButton = {
            if (isAdmin) {
                FloatingActionButton(onClick = { showAddDialog = true }) {
                    Icon(Icons.Default.Add, contentDescription = "Add Project")
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            // Offline Banner
            if (!isOnline) {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Row(
                        modifier = Modifier.padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.width(8.dp))
                        Text("You are offline. Showing cached data.", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            // Update Banner for Desktop
            if (needsUpdate) {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    onClick = { 
                        latestVersionInfo?.get("url")?.let { com.example.crewsync.util.openUrl(it) }
                    }
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.KeyboardArrowDown, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text("New Version Available!", style = MaterialTheme.typography.labelLarge)
                            Text("Click to download Version ${latestVersionInfo?.get("version")}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (userProfile?.profilePictureUrl != null) {
                    AsyncImage(
                        model = userProfile?.profilePictureUrl,
                        contentDescription = null,
                        modifier = Modifier.size(50.dp).clip(CircleShape),
                        contentScale = ContentScale.Crop
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                }
                
                Column {
                    val displayName = userProfile?.name?.ifEmpty { auth.currentUser?.email } ?: "User"
                    Text(
                        text = "Welcome, $displayName",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Active Projects",
                    style = MaterialTheme.typography.headlineSmall
                )
                
                // View Mode Toggle
                Row {
                    IconButton(onClick = { 
                        scope.launch {
                            val uid = auth.currentUser?.uid ?: return@launch
                            firestore.collection("users").document(uid).update("dashboardViewMode" to "Cards")
                        }
                    }) {
                        Icon(Icons.Default.Info, contentDescription = "Cards", tint = if (viewMode == "Cards") MaterialTheme.colorScheme.primary else LocalContentColor.current)
                    }
                    IconButton(onClick = { 
                        scope.launch {
                            val uid = auth.currentUser?.uid ?: return@launch
                            firestore.collection("users").document(uid).update("dashboardViewMode" to "List")
                        }
                    }) {
                        Icon(Icons.AutoMirrored.Filled.List, contentDescription = "List", tint = if (viewMode == "List") MaterialTheme.colorScheme.primary else LocalContentColor.current)
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (sortedProjects.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No projects assigned to you.")
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    items(sortedProjects) { project ->
                        if (viewMode == "Cards") {
                            ProjectCard(
                                project = project,
                                isFirst = project == sortedProjects.first(),
                                isLast = project == sortedProjects.last(),
                                onClick = { onProjectClick(project.id) },
                                onMove = { dir ->
                                    val currentOrder = userProfile?.projectOrder?.toMutableList() ?: sortedProjects.map { it.id }.toMutableList()
                                    if (!currentOrder.contains(project.id)) {
                                        currentOrder.clear()
                                        currentOrder.addAll(sortedProjects.map { it.id })
                                    }
                                    val idx = currentOrder.indexOf(project.id)
                                    val newIdx = idx + dir
                                    if (newIdx in currentOrder.indices) {
                                        currentOrder.removeAt(idx)
                                        currentOrder.add(newIdx, project.id)
                                        scope.launch {
                                            val uid = auth.currentUser?.uid ?: return@launch
                                            firestore.collection("users").document(uid).update("projectOrder" to currentOrder)
                                        }
                                    }
                                }
                            )
                        } else {
                            ProjectListItem(
                                project = project,
                                isFirst = project == sortedProjects.first(),
                                isLast = project == sortedProjects.last(),
                                onClick = { onProjectClick(project.id) },
                                onMove = { dir ->
                                    val currentOrder = userProfile?.projectOrder?.toMutableList() ?: sortedProjects.map { it.id }.toMutableList()
                                    if (!currentOrder.contains(project.id)) {
                                        currentOrder.clear()
                                        currentOrder.addAll(sortedProjects.map { it.id })
                                    }
                                    val idx = currentOrder.indexOf(project.id)
                                    val newIdx = idx + dir
                                    if (newIdx in currentOrder.indices) {
                                        currentOrder.removeAt(idx)
                                        currentOrder.add(newIdx, project.id)
                                        scope.launch {
                                            val uid = auth.currentUser?.uid ?: return@launch
                                            firestore.collection("users").document(uid).update("projectOrder" to currentOrder)
                                        }
                                    }
                                }
                            )
                        }
                    }
                }
            }

            if (isSuperAdmin) {
                Spacer(modifier = Modifier.height(16.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("System Overview", style = MaterialTheme.typography.titleSmall)
                        Text("My Role on this device: ${userProfile?.role ?: "None"}", style = MaterialTheme.typography.bodySmall)
                        Text("Projects arriving at device (Raw): $rawProjectsCount", style = MaterialTheme.typography.bodySmall)
                        Text("Projects shown after filter: ${projects.size}", style = MaterialTheme.typography.bodySmall)
                        Text("Total Registered Users: $allUsersCount", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        if (showAddDialog) {
            AddProjectDialog(
                onDismiss = { showAddDialog = false },
                onConfirm = { name, desc ->
                    scope.launch {
                        try {
                            val newProject = Project(
                                name = name,
                                description = desc,
                                teamLeaderId = auth.currentUser?.uid ?: "",
                                createdAt = Clock.System.now().toEpochMilliseconds()
                            )
                            firestore.collection("projects").add(newProject)
                            showAddDialog = false
                        } catch (e: Exception) {}
                    }
                }
            )
        }
    }
}

@Composable
fun ProjectCard(project: Project, isFirst: Boolean, isLast: Boolean, onClick: () -> Unit, onMove: (Int) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        onClick = onClick
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(text = project.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Row {
                    if (!isFirst) {
                        IconButton(onClick = { onMove(-1) }, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Default.KeyboardArrowUp, contentDescription = null)
                        }
                    }
                    if (!isLast) {
                        IconButton(onClick = { onMove(1) }, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Default.KeyboardArrowDown, contentDescription = null)
                        }
                    }
                }
            }
            if (project.description.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = project.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onClick) {
                    Text("View Project")
                }
            }
        }
    }
}

@Composable
fun ProjectListItem(project: Project, isFirst: Boolean, isLast: Boolean, onClick: () -> Unit, onMove: (Int) -> Unit) {
    ListItem(
        headlineContent = { Text(project.name, fontWeight = FontWeight.Bold) },
        supportingContent = { if (project.description.isNotEmpty()) Text(project.description, maxLines = 1) },
        leadingContent = { Icon(Icons.Default.Home, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!isFirst) {
                    IconButton(onClick = { onMove(-1) }, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Default.KeyboardArrowUp, contentDescription = null)
                    }
                }
                if (!isLast) {
                    IconButton(onClick = { onMove(1) }, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Default.KeyboardArrowDown, contentDescription = null)
                    }
                }
                Icon(Icons.Default.ArrowForward, contentDescription = null)
            }
        },
        modifier = Modifier.clickable { onClick() }.border(0.5.dp, Color.LightGray.copy(alpha = 0.3f), MaterialTheme.shapes.small)
    )
}

@Composable
fun AddProjectDialog(onDismiss: () -> Unit, onConfirm: (String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add New Project") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Project Name") },
                    modifier = Modifier.fillMaxWidth()
                )
                TextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Description") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(name, description) },
                enabled = name.isNotBlank()
            ) {
                Text("Add")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
