package com.example.crewsync.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.crewsync.data.model.*
import com.example.crewsync.ui.components.ChatTab
import com.example.crewsync.util.rememberFilePickerLauncher
import com.example.crewsync.util.uploadFile
import com.example.crewsync.util.openUrl
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.auth.auth
import dev.gitlive.firebase.firestore.firestore
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import com.example.crewsync.util.toProjectSafe
import com.example.crewsync.util.toTaskSafe
import com.example.crewsync.util.toFirestoreMap
import coil3.compose.AsyncImage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectDetailsScreen(
    projectId: String, 
    onBack: () -> Unit, 
    onMemberClick: (String) -> Unit,
    onMarkupClick: (String, String, String) -> Unit
) {
    val firestore = Firebase.firestore
    val auth = Firebase.auth
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    
    var userProfile by remember { mutableStateOf<User?>(null) }
    var project by remember { mutableStateOf<Project?>(null) }
    var showAddMemberDialog by remember { mutableStateOf(false) }
    var showProjectAlertDialog by remember { mutableStateOf(false) }
    var isUploading by remember { mutableStateOf(false) }
    var currentFolderId by remember { mutableStateOf<String?>(null) }
    var showAddFolderDialog by remember { mutableStateOf(false) }

    val foldersFlow = remember(projectId) {
        firestore.collection("projects").document(projectId).collection("folders").snapshots.map { snap ->
            snap.documents.mapNotNull { try { it.data<ProjectFolder>().copy(id = it.id) } catch (e: Exception) { null } }
        }
    }
    val folders by foldersFlow.collectAsState(initial = emptyList())

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

    val userMap = remember(allUsers) { allUsers.associate { it.email to (it.name.ifEmpty { it.email }) } }
    val userPicMap = remember(allUsers) { allUsers.associate { it.email to it.profilePictureUrl } }

    // Unified Data Fetching
    val tasksFlow = remember(projectId) {
        firestore.collection("tasks").snapshots.map { snap ->
            snap.documents.mapNotNull { try { it.toTaskSafe() } catch (e: Exception) { null } }
                .filter { it.projectId == projectId }
        }
    }
    val tasks by tasksFlow.collectAsState(initial = emptyList())

    val appointmentsFlow = remember(projectId) {
        firestore.collection("projects").document(projectId).collection("appointments").snapshots.map { snap ->
            snap.documents.mapNotNull { try { it.data<Appointment>().copy(id = it.id) } catch (e: Exception) { null } }
        }
    }
    val appointments by appointmentsFlow.collectAsState(initial = emptyList())

    val filesFlow = remember(projectId) {
        firestore.collection("projects").document(projectId).collection("files").snapshots.map { snap ->
            snap.documents.mapNotNull { try { it.data<ProjectFile>().copy(id = it.id) } catch (e: Exception) { null } }
        }
    }
    val files by filesFlow.collectAsState(initial = emptyList())

    val messagesFlow = remember(projectId) {
        firestore.collection("projects").document(projectId).collection("messages").snapshots.map { snap ->
            val list = snap.documents.mapNotNull { try { it.data<ChatMessage>().copy(id = it.id) } catch (e: Exception) { null } }.sortedBy { it.timestamp }
            if (list.isNotEmpty()) {
                val last = list.last()
                if (last.senderEmail != auth.currentUser?.email && last.timestamp > Clock.System.now().toEpochMilliseconds() - 5000) {
                    com.example.crewsync.util.notifyChatMessage(projectId, last.senderEmail, last.text)
                }
            }
            list
        }
    }
    val messages by messagesFlow.collectAsState(initial = emptyList())

    var selectedTab by remember { mutableStateOf(0) }
    val tabs = listOf(
        "Home" to Icons.Default.Home,
        "Team" to Icons.Default.Person,
        "Files" to Icons.Default.Build,
        "Chat" to Icons.Default.Email,
        "Planner" to Icons.AutoMirrored.Filled.List,
        "Calendar" to Icons.Default.DateRange
    )

    val isSuperAdmin = userProfile?.role == "SuperAdmin" || userProfile?.role == "Admin"
    val isAdmin = isSuperAdmin // Project specific admin or global admin
    val isLeader = project?.teamLeaderId == auth.currentUser?.uid || isAdmin

    val filePickerLauncher = rememberFilePickerLauncher { pickedFile ->
        scope.launch {
            isUploading = true
            try {
                val path = "projects/$projectId/${pickedFile.name}"
                val downloadUrl = uploadFile(path, pickedFile.platformFile)
                val projectFile = ProjectFile(
                    name = pickedFile.name,
                    url = downloadUrl,
                    folderId = currentFolderId,
                    uploadedBy = auth.currentUser?.email ?: "Unknown",
                    uploadedAt = Clock.System.now().toEpochMilliseconds()
                )
                firestore.collection("projects").document(projectId).collection("files").add(projectFile.toFirestoreMap())
            } catch (e: Exception) {
            } finally {
                isUploading = false
            }
        }
    }

    val cameraLauncher = com.example.crewsync.util.rememberCameraLauncher { pickedFile ->
        scope.launch {
            isUploading = true
            try {
                val path = "projects/$projectId/${pickedFile.name}"
                val downloadUrl = uploadFile(path, pickedFile.platformFile)
                val projectFile = ProjectFile(
                    name = pickedFile.name,
                    url = downloadUrl,
                    folderId = currentFolderId,
                    uploadedBy = auth.currentUser?.email ?: "Unknown",
                    uploadedAt = Clock.System.now().toEpochMilliseconds()
                )
                firestore.collection("projects").document(projectId).collection("files").add(projectFile.toFirestoreMap())
            } catch (e: Exception) {
            } finally {
                isUploading = false
            }
        }
    }

    LaunchedEffect(projectId) {
        firestore.collection("projects").document(projectId).snapshots.collect { snapshot ->
            if (snapshot.exists) {
                try {
                    project = snapshot.toProjectSafe()
                } catch (_: Exception) {}
            }
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = project?.name ?: "Project Menu",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.titleLarge
                )
                HorizontalDivider()
                tabs.forEachIndexed { index, (label, icon) ->
                    NavigationDrawerItem(
                        icon = { Icon(icon, contentDescription = null) },
                        label = { Text(label) },
                        selected = selectedTab == index,
                        onClick = {
                            selectedTab = index
                            scope.launch { drawerState.close() }
                        },
                        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                    )
                }
            }
        }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { 
                        Text(
                            text = tabs[selectedTab].first,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        ) 
                    },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.Menu, contentDescription = "Menu")
                        }
                    },
                    actions = {
                        if (isAdmin && selectedTab == 0) {
                            IconButton(onClick = { showProjectAlertDialog = true }) {
                                Icon(Icons.Default.Warning, contentDescription = "Send Alert", tint = MaterialTheme.colorScheme.error)
                            }
                        }
                        IconButton(onClick = {
                            if (selectedTab != 0) selectedTab = 0
                            else onBack()
                        }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        titleContentColor = MaterialTheme.colorScheme.onPrimary,
                        navigationIconContentColor = MaterialTheme.colorScheme.onPrimary,
                        actionIconContentColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    windowInsets = WindowInsets.statusBars
                )
            }
        ) { padding ->
            if (project == null) {
                Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                    when (selectedTab) {
                        0 -> ProjectHomeScreen(
                            project = project!!,
                            tasks = tasks,
                            appointments = appointments,
                            messages = messages,
                            files = files,
                            userMap = userMap,
                            userPicMap = userPicMap,
                            onMoveCard = { cardId, direction ->
                                val currentOrder = project!!.cardOrder.toMutableList()
                                val currentIndex = currentOrder.indexOf(cardId)
                                val newIndex = currentIndex + direction
                                if (newIndex in currentOrder.indices) {
                                    currentOrder.removeAt(currentIndex)
                                    currentOrder.add(newIndex, cardId)
                                    scope.launch {
                                        firestore.collection("projects").document(projectId).update("cardOrder" to currentOrder)
                                    }
                                }
                            },
                            onResizeCard = { cardId, newSize ->
                                val currentSizes = project!!.cardSizes.toMutableMap()
                                currentSizes[cardId] = newSize
                                scope.launch {
                                    firestore.collection("projects").document(projectId).update("cardSizes" to currentSizes)
                                }
                            },
                            onNavigateToTab = { selectedTab = it }
                        )
                        1 -> TeamTab(projectId, project!!.members, userMap, isLeader, onAddMember = { showAddMemberDialog = true }, onMemberClick = onMemberClick)
                        2 -> FilesTab(
                            files = files, 
                            folders = folders,
                            currentFolderId = currentFolderId,
                            isUploading = isUploading, 
                            isLeader = isLeader, 
                            onUpload = { filePickerLauncher() }, 
                            onTakeCamera = { cameraLauncher() },
                            onAddFolder = { showAddFolderDialog = true },
                            onFolderClick = { currentFolderId = it },
                            onMarkupClick = onMarkupClick,
                            onDeleteClick = { file ->
                                scope.launch {
                                    firestore.collection("projects").document(projectId).collection("files").document(file.id).delete()
                                }
                            }
                        )
                        3 -> ChatTab(
                            messages = messages,
                            currentUserEmail = auth.currentUser?.email ?: "",
                            userMap = userMap,
                            userPicMap = userPicMap,
                            onSendMessage = { text, url, name ->
                                scope.launch {
                                    val msg = ChatMessage(
                                        senderId = auth.currentUser?.uid ?: "",
                                        senderEmail = auth.currentUser?.email ?: "Anonymous",
                                        text = text,
                                        attachmentUrl = url,
                                        attachmentName = name,
                                        timestamp = Clock.System.now().toEpochMilliseconds()
                                    )
                                    firestore.collection("projects").document(projectId).collection("messages").add(msg.toFirestoreMap())
                                }
                            },
                            onUploadAttachment = { pickedFile, onUrlReady ->
                                scope.launch {
                                    try {
                                        val path = "chat_attachments/$projectId/${pickedFile.name}"
                                        val url = uploadFile(path, pickedFile.platformFile)
                                        onUrlReady(url)
                                    } catch (e: Exception) {}
                                }
                            }
                        )
                        4 -> PlannerScreen(projectId = projectId, projectBuckets = project!!.buckets, projectMembers = project!!.members)
                        5 -> CalendarScreen(
                            projectId = projectId,
                            tasks = tasks,
                            appointments = appointments,
                            canEdit = isLeader
                        )
                    }
                }
            }

            if (showAddMemberDialog) {
                AddMemberDialog(
                    onDismiss = { showAddMemberDialog = false },
                    onConfirm = { email ->
                        scope.launch {
                            val updatedMembers = project!!.members.toMutableList().apply { add(email) }
                            firestore.collection("projects").document(projectId).update("members" to updatedMembers)
                            showAddMemberDialog = false
                        }
                    }
                )
            }

            if (showAddFolderDialog) {
                AddFolderDialog(
                    onDismiss = { showAddFolderDialog = false },
                    onConfirm = { name ->
                        scope.launch {
                            val newFolder = ProjectFolder(
                                projectId = projectId,
                                name = name,
                                parentFolderId = currentFolderId,
                                createdAt = Clock.System.now().toEpochMilliseconds()
                            )
                            firestore.collection("projects").document(projectId).collection("folders").add(newFolder)
                            showAddFolderDialog = false
                        }
                    }
                )
            }

            if (showProjectAlertDialog) {
                SendProjectAlertDialog(
                    onDismiss = { showProjectAlertDialog = false },
                    onConfirm = { title, message ->
                        scope.launch {
                            val alert = Broadcast(
                                projectId = projectId,
                                senderName = userProfile?.name?.ifEmpty { auth.currentUser?.email } ?: "Admin",
                                title = title,
                                message = message,
                                timestamp = Clock.System.now().toEpochMilliseconds()
                            )
                            firestore.collection("broadcasts").add(alert.toFirestoreMap())
                            showProjectAlertDialog = false
                        }
                    }
                )
            }
        }
    }
}

@Composable
fun SendProjectAlertDialog(onDismiss: () -> Unit, onConfirm: (String, String) -> Unit) {
    var title by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Project Alert") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextField(value = title, onValueChange = { title = it }, label = { Text("Title") }, modifier = Modifier.fillMaxWidth())
                TextField(value = message, onValueChange = { message = it }, label = { Text("Message") }, modifier = Modifier.fillMaxWidth(), minLines = 3)
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(title, message) }, enabled = title.isNotBlank() && message.isNotBlank()) {
                Text("Send Alert")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
fun TeamTab(projectId: String, members: List<String>, userMap: Map<String, String>, isLeader: Boolean, onAddMember: () -> Unit, onMemberClick: (String) -> Unit) {
    val firestore = Firebase.firestore
    val scope = rememberCoroutineScope()

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Team Members", style = MaterialTheme.typography.titleMedium)
            if (isLeader) {
                IconButton(onClick = onAddMember) {
                    Icon(Icons.Default.Add, contentDescription = "Add Member")
                }
            }
        }
        LazyColumn {
            items(members) { memberEmail ->
                val displayName = userMap[memberEmail] ?: memberEmail
                ListItem(
                    headlineContent = { Text(displayName) },
                    leadingContent = { Icon(Icons.Default.Person, contentDescription = null) },
                    modifier = Modifier.clickable { onMemberClick(memberEmail) },
                    supportingContent = { 
                        if (displayName != memberEmail) Text(memberEmail)
                        else Text("Tap to chat")
                    },
                    trailingContent = {
                        if (isLeader) {
                            IconButton(onClick = {
                                scope.launch {
                                    val newList = members.filter { it != memberEmail }
                                    firestore.collection("projects").document(projectId).update("members" to newList)
                                }
                            }) {
                                Icon(Icons.Default.Delete, contentDescription = "Remove", tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                )
            }
        }
    }
}

@Composable
fun FilesTab(
    files: List<ProjectFile>, 
    folders: List<ProjectFolder>,
    currentFolderId: String?,
    isUploading: Boolean, 
    isLeader: Boolean, 
    onUpload: () -> Unit, 
    onTakeCamera: () -> Unit,
    onAddFolder: () -> Unit,
    onFolderClick: (String?) -> Unit,
    onMarkupClick: (String, String, String) -> Unit,
    onDeleteClick: (ProjectFile) -> Unit
) {
    val currentFolders = folders.filter { it.parentFolderId == currentFolderId }
    val currentFiles = files.filter { it.folderId == currentFolderId }
    var isGalleryView by remember { mutableStateOf(false) }
    var fullScreenImage by remember { mutableStateOf<ProjectFile?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                if (currentFolderId != null) {
                    IconButton(onClick = { 
                        val parent = folders.find { it.id == currentFolderId }?.parentFolderId
                        onFolderClick(parent)
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Up")
                    }
                }
                Text(
                    text = if (currentFolderId == null) "Project Files" else folders.find { it.id == currentFolderId }?.name ?: "Folder",
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { isGalleryView = !isGalleryView }) {
                    Icon(
                        if (isGalleryView) Icons.AutoMirrored.Filled.List else Icons.Default.Menu, 
                        contentDescription = "Toggle View"
                    )
                }
                if (isLeader) {
                    if (isUploading) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    } else {
                        IconButton(onClick = onAddFolder) {
                            Icon(Icons.Default.Add, contentDescription = "New Folder")
                        }
                        IconButton(onClick = onTakeCamera) {
                            Icon(Icons.Default.Info, contentDescription = "Camera")
                        }
                        IconButton(onClick = onUpload) {
                            Icon(Icons.Default.Add, contentDescription = "Upload File")
                        }
                    }
                }
            }
        }

        if (isGalleryView) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 120.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(currentFolders) { folder ->
                    Card(
                        modifier = Modifier.aspectRatio(1f),
                        onClick = { onFolderClick(folder.id) }
                    ) {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(Icons.Default.Build, null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
                            Text(folder.name, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center)
                        }
                    }
                }
                items(currentFiles) { file ->
                    val isImage = file.name.lowercase().let { it.endsWith(".jpg") || it.endsWith(".jpeg") || it.endsWith(".png") }
                    Card(
                        modifier = Modifier.aspectRatio(1f),
                        onClick = { 
                            if (isImage) fullScreenImage = file
                            else openUrl(file.url)
                        }
                    ) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            if (isImage) {
                                AsyncImage(
                                    model = file.url,
                                    contentDescription = file.name,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop
                                )
                            } else {
                                Column(
                                    modifier = Modifier.fillMaxSize().padding(8.dp),
                                    verticalArrangement = Arrangement.Center,
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Icon(
                                        if (file.name.lowercase().endsWith(".pdf")) Icons.Default.Info else Icons.Default.DateRange,
                                        null,
                                        modifier = Modifier.size(32.dp)
                                    )
                                    Text(file.name, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                            
                            IconButton(
                                onClick = { onMarkupClick(file.url, file.name, file.id) },
                                modifier = Modifier.align(Alignment.TopEnd).size(32.dp).background(Color.Black.copy(alpha = 0.5f), CircleShape).padding(4.dp)
                            ) {
                                Icon(Icons.Default.Edit, null, tint = Color.White, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }
        } else {
            LazyColumn {
                items(currentFolders) { folder ->
                    ListItem(
                        headlineContent = { Text(folder.name, fontWeight = FontWeight.Bold) },
                        leadingContent = { Icon(Icons.Default.Build, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                        modifier = Modifier.clickable { onFolderClick(folder.id) }
                    )
                }
                
                if (currentFiles.isEmpty() && currentFolders.isEmpty()) {
                    item { Text("No items in this folder.", modifier = Modifier.padding(16.dp)) }
                } else {
                    items(currentFiles) { file ->
                        ListItem(
                            headlineContent = { Text(file.name) },
                            supportingContent = { Text("By ${file.uploadedBy}") },
                            leadingContent = { 
                                val isImage = file.name.lowercase().let { it.endsWith(".jpg") || it.endsWith(".jpeg") || it.endsWith(".png") }
                                if (isImage) {
                                    AsyncImage(
                                        model = file.url,
                                        contentDescription = null,
                                        modifier = Modifier.size(40.dp).clip(RoundedCornerShape(4.dp)),
                                        contentScale = ContentScale.Crop
                                    )
                                } else {
                                    Icon(Icons.Default.Info, contentDescription = null)
                                }
                            },
                            trailingContent = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    TextButton(onClick = { onMarkupClick(file.url, file.name, file.id) }) {
                                        Text("Markup")
                                    }
                                    TextButton(onClick = { 
                                        val isImage = file.name.lowercase().let { it.endsWith(".jpg") || it.endsWith(".jpeg") || it.endsWith(".png") }
                                        if (isImage) fullScreenImage = file
                                        else openUrl(file.url)
                                    }) {
                                        Text("Open")
                                    }
                                    if (isLeader) {
                                        IconButton(onClick = { onDeleteClick(file) }) {
                                            Icon(
                                                Icons.Default.Delete, 
                                                contentDescription = "Delete", 
                                                tint = MaterialTheme.colorScheme.error,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    if (fullScreenImage != null) {
        FullScreenImageDialog(
            file = fullScreenImage!!,
            onDismiss = { fullScreenImage = null },
            onMarkup = { 
                onMarkupClick(fullScreenImage!!.url, fullScreenImage!!.name, fullScreenImage!!.id)
                fullScreenImage = null
            }
        )
    }
}

@Composable
fun FullScreenImageDialog(file: ProjectFile, onDismiss: () -> Unit, onMarkup: () -> Unit) {
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            AsyncImage(
                model = file.url,
                contentDescription = file.name,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )
            
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp).align(Alignment.TopStart),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onDismiss, modifier = Modifier.background(Color.Black.copy(alpha = 0.5f), CircleShape)) {
                    Icon(Icons.Default.Close, null, tint = Color.White)
                }
                Button(onClick = onMarkup) {
                    Icon(Icons.Default.Edit, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Markup")
                }
            }
            
            Text(
                text = file.name,
                color = Color.White,
                modifier = Modifier.align(Alignment.BottomCenter).padding(24.dp),
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
fun AddFolderDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New Folder") },
        text = {
            TextField(value = name, onValueChange = { name = it }, label = { Text("Folder Name") }, modifier = Modifier.fillMaxWidth())
        },
        confirmButton = {
            Button(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) { Text("Create") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
fun AddMemberDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    val firestore = Firebase.firestore
    var email by remember { mutableStateOf("") }
    
    val masterContacts by firestore.collection("contacts")
        .snapshots
        .map { snapshot -> 
            snapshot.documents.mapNotNull { 
                try {
                    it.data<Contact>() 
                } catch (e: Exception) { null }
            } 
        }
        .collectAsState(initial = emptyList())

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Team Member") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text("Email Address") },
                    modifier = Modifier.fillMaxWidth()
                )
                
                if (masterContacts.isNotEmpty()) {
                    Text("Or pick from Master List:", style = MaterialTheme.typography.labelSmall)
                    LazyColumn(modifier = Modifier.heightIn(max = 200.dp)) {
                        items(masterContacts) { contact ->
                            ListItem(
                                headlineContent = { Text(contact.name) },
                                supportingContent = { Text(contact.email) },
                                modifier = Modifier.clickable { email = contact.email }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(email) }, enabled = email.isNotBlank()) { Text("Add") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
