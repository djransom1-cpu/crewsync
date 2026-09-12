package com.djransom.crewsync.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.djransom.crewsync.data.model.Environment
import com.djransom.crewsync.data.model.User
import kotlin.time.Clock
import com.djransom.crewsync.util.rememberFilePickerLauncher
import com.djransom.crewsync.util.uploadFile
import com.djransom.crewsync.util.toFirestoreMap
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.auth.auth
import dev.gitlive.firebase.firestore.firestore
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen() {
    val auth = Firebase.auth
    val firestore = Firebase.firestore
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    
    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var trade by remember { mutableStateOf("") }
    var email by remember { mutableStateOf(auth.currentUser?.email ?: "") }
    var role by remember { mutableStateOf("Member") }
    var profilePictureUrl by remember { mutableStateOf<String?>(null) }
    var environmentIds by remember { mutableStateOf<List<String>>(emptyList()) }
    var activeEnvironmentId by remember { mutableStateOf("") }
    // Preserved as-is (not editable on this screen) so saving a profile edit doesn't silently
    // reset these back to their defaults, wiping push notifications/project ordering/preferences.
    var fcmToken by remember { mutableStateOf<String?>(null) }
    var projectOrder by remember { mutableStateOf<List<String>>(emptyList()) }
    var dashboardViewMode by remember { mutableStateOf("Cards") }
    var firstDayOfWeek by remember { mutableStateOf("Sunday") }
    var isLoading by remember { mutableStateOf(true) }
    var isUploading by remember { mutableStateOf(false) }
    var showEnvironmentSwitcher by remember { mutableStateOf(false) }

    var newPassword by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var showPasswordChange by remember { mutableStateOf(false) }

    val imagePicker = rememberFilePickerLauncher { pickedFile ->
        scope.launch {
            isUploading = true
            try {
                val uid = auth.currentUser?.uid ?: return@launch
                val path = "profile_pictures/$uid"
                val url = uploadFile(path, pickedFile.platformFile)
                profilePictureUrl = url
                firestore.collection("users").document(uid).update("profilePictureUrl" to url)
                snackbarHostState.showSnackbar("Profile picture uploaded!")
            } catch (e: Exception) {
                snackbarHostState.showSnackbar("Failed to upload image: ${e.message}")
            } finally {
                isUploading = false
            }
        }
    }

    LaunchedEffect(auth.currentUser?.uid) {
        val uid = auth.currentUser?.uid ?: return@LaunchedEffect
        val userEmail = auth.currentUser?.email ?: ""
        
        // 1. Try to load by UID
        var snapshot = firestore.collection("users").document(uid).get()
        
        // 2. If not found, try to load by Email (the Invitation case)
        if (!snapshot.exists && userEmail.isNotEmpty()) {
            val inviteSnapshot = firestore.collection("users").document(userEmail.lowercase()).get()
            if (inviteSnapshot.exists) {
                try {
                    val invitedData = inviteSnapshot.data<User>()
                    // Transfer the data to the proper UID document
                    firestore.collection("users").document(uid).set(invitedData.copy(uid = uid, email = userEmail.lowercase()))
                    // Delete the old email-based document
                    firestore.collection("users").document(userEmail.lowercase()).delete()
                    // Refresh snapshot
                    snapshot = firestore.collection("users").document(uid).get()
                } catch (e: Exception) {}
            }
        }

        // 3. Last Resort: Create a basic profile if still nothing exists - including its own new
        // environment, same as a fresh LoginScreen signup would, so this fallback never leaves
        // someone with no workspace to land in.
        if (!snapshot.exists) {
            val newEnvId = generateInviteCode()
            firestore.collection("environments").document(newEnvId).set(
                Environment(id = newEnvId, name = "${userEmail.substringBefore("@")}'s Environment", ownerId = uid, createdAt = Clock.System.now().toEpochMilliseconds())
            )
            val newUser = User(uid = uid, email = userEmail.lowercase(), name = "", role = "Admin", environmentIds = listOf(newEnvId), activeEnvironmentId = newEnvId)
            firestore.collection("users").document(uid).set(newUser)
            snapshot = firestore.collection("users").document(uid).get()
        }

        if (snapshot.exists) {
            try {
                val user = snapshot.data<User>()
                name = user.name
                phone = user.phone
                trade = user.trade
                email = user.email.ifEmpty { userEmail }
                role = user.role
                profilePictureUrl = user.profilePictureUrl
                environmentIds = user.environmentIds
                activeEnvironmentId = user.activeEnvironmentId
                fcmToken = user.fcmToken
                projectOrder = user.projectOrder
                dashboardViewMode = user.dashboardViewMode
                firstDayOfWeek = user.firstDayOfWeek
            } catch (e: Exception) {}
        }
        isLoading = false
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(title = { Text("My Profile") })
        },
        floatingActionButton = {
            FloatingActionButton(onClick = {
                scope.launch {
                    val uid = auth.currentUser?.uid ?: return@launch
                    val updatedUser = User(
                        uid = uid,
                        email = email,
                        name = name,
                        phone = phone,
                        trade = trade,
                        role = role,
                        profilePictureUrl = profilePictureUrl,
                        environmentIds = environmentIds,
                        activeEnvironmentId = activeEnvironmentId,
                        fcmToken = fcmToken,
                        projectOrder = projectOrder,
                        dashboardViewMode = dashboardViewMode,
                        firstDayOfWeek = firstDayOfWeek
                    )
                    firestore.collection("users").document(uid).set(updatedUser.toFirestoreMap())
                    snackbarHostState.showSnackbar("Profile updated successfully!")
                }
            }) {
                Icon(Icons.Default.Check, contentDescription = "Save")
            }
        }
    ) { padding ->
        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(120.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { imagePicker() },
                    contentAlignment = Alignment.Center
                ) {
                    if (profilePictureUrl != null) {
                        AsyncImage(
                            model = profilePictureUrl,
                            contentDescription = "Profile Picture",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Icon(
                            Icons.Default.Person,
                            contentDescription = null,
                            modifier = Modifier.size(80.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    
                    if (isUploading) {
                        CircularProgressIndicator(modifier = Modifier.size(40.dp))
                    } else {
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .background(MaterialTheme.colorScheme.primary, CircleShape)
                                .padding(4.dp)
                        ) {
                            Icon(
                                Icons.Default.Add,
                                contentDescription = "Change Picture",
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                    }
                }
                
                Text(
                    text = email,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                Text(
                    text = "Role: $role",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.secondary
                )

                val userForSwitcher = User(
                    uid = auth.currentUser?.uid ?: "", email = email, name = name, phone = phone, trade = trade,
                    role = role, profilePictureUrl = profilePictureUrl, environmentIds = environmentIds,
                    activeEnvironmentId = activeEnvironmentId, fcmToken = fcmToken, projectOrder = projectOrder,
                    dashboardViewMode = dashboardViewMode, firstDayOfWeek = firstDayOfWeek
                )
                val myEnvironments = rememberMyEnvironments(userForSwitcher)
                val currentEnvironment = myEnvironments.find { it.id == activeEnvironmentId }
                Card(
                    modifier = Modifier.fillMaxWidth().clickable { showEnvironmentSwitcher = true },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f))
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("Environment", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(currentEnvironment?.name?.ifBlank { "Unnamed Environment" } ?: "Loading…", style = MaterialTheme.typography.titleSmall)
                        if (currentEnvironment != null) {
                            Text("Invite code: ${currentEnvironment.id}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        }
                        Text("Tap to switch, create, or join another", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                if (showEnvironmentSwitcher) {
                    EnvironmentSwitcherDialog(
                        userProfile = userForSwitcher,
                        environments = myEnvironments,
                        onDismiss = { showEnvironmentSwitcher = false },
                        onSwitch = { envId ->
                            scope.launch {
                                val uid = auth.currentUser?.uid ?: return@launch
                                firestore.collection("users").document(uid).update("activeEnvironmentId" to envId)
                                activeEnvironmentId = envId
                                showEnvironmentSwitcher = false
                            }
                        },
                        onCreated = { newId -> activeEnvironmentId = newId; environmentIds = environmentIds + newId; showEnvironmentSwitcher = false },
                        onJoined = { joinedId -> activeEnvironmentId = joinedId; if (joinedId !in environmentIds) environmentIds = environmentIds + joinedId; showEnvironmentSwitcher = false }
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                TextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Full Name") },
                    modifier = Modifier.fillMaxWidth()
                )

                TextField(
                    value = phone,
                    onValueChange = { phone = it },
                    label = { Text("Phone Number") },
                    modifier = Modifier.fillMaxWidth()
                )

                TextField(
                    value = trade,
                    onValueChange = { trade = it },
                    label = { Text("Trade/Skill") },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("e.g. Lead Carpenter") }
                )

                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider()
                
                if (!showPasswordChange) {
                    TextButton(onClick = { showPasswordChange = true }) {
                        Text("Change Password")
                    }
                } else {
                    Text("Change Password", style = MaterialTheme.typography.titleMedium)
                    TextField(
                        value = newPassword,
                        onValueChange = { newPassword = it },
                        label = { Text("New Password") },
                        modifier = Modifier.fillMaxWidth(),
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation()
                    )
                    TextField(
                        value = confirmPassword,
                        onValueChange = { confirmPassword = it },
                        label = { Text("Confirm New Password") },
                        modifier = Modifier.fillMaxWidth(),
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                if (newPassword.length < 6) {
                                    scope.launch { snackbarHostState.showSnackbar("Password must be at least 6 characters") }
                                    return@Button
                                }
                                if (newPassword != confirmPassword) {
                                    scope.launch { snackbarHostState.showSnackbar("Passwords do not match") }
                                    return@Button
                                }
                                scope.launch {
                                    try {
                                        auth.currentUser?.updatePassword(newPassword)
                                        snackbarHostState.showSnackbar("Password updated successfully!")
                                        newPassword = ""
                                        confirmPassword = ""
                                        showPasswordChange = false
                                    } catch (e: Exception) {
                                        val msg = if (e.message?.contains("recent login") == true) {
                                            "For security, please log out and log back in to change your password."
                                        } else {
                                            e.message ?: "Failed to update password"
                                        }
                                        snackbarHostState.showSnackbar(msg)
                                    }
                                }
                            },
                            enabled = newPassword.isNotEmpty() && confirmPassword.isNotEmpty()
                        ) {
                            Text("Update Password")
                        }
                        TextButton(onClick = { 
                            showPasswordChange = false 
                            newPassword = ""
                            confirmPassword = ""
                        }) {
                            Text("Cancel")
                        }
                    }
                }
            }
        }
    }
}
