package com.djransom.crewsync.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.djransom.crewsync.data.model.Feedback
import com.djransom.crewsync.data.model.User
import com.djransom.crewsync.util.getAppVersion
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.auth.auth
import dev.gitlive.firebase.firestore.firestore
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onNavigateToReports: () -> Unit) {
    val firestore = Firebase.firestore
    val auth = Firebase.auth
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    
    var userProfile by remember { mutableStateOf<User?>(null) }
    var showFeedbackDialog by remember { mutableStateOf(false) }

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

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(title = { Text("About Crewsync") })
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(32.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                Icons.Default.Info,
                contentDescription = null,
                modifier = Modifier.size(80.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            
            Spacer(modifier = Modifier.height(24.dp))
            
            Text(
                text = "Crewsync",
                style = MaterialTheme.typography.headlineMedium
            )
            
            Text(
                text = "Construction Crew Management",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = MaterialTheme.shapes.medium
            ) {
                Text(
                    text = "Version ${getAppVersion()}",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
            
            Spacer(modifier = Modifier.height(24.dp))

            // Calendar First Day Preference
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Calendar Preferences", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                    Text("First day of the week:", fontSize = 12.sp, color = Color.Gray)
                    
                    val currentFirstDay = userProfile?.firstDayOfWeek ?: "Sunday"
                    val options = listOf("Sunday", "Monday", "Saturday")
                    
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        options.forEach { dayOption ->
                            FilterChip(
                                selected = currentFirstDay == dayOption,
                                onClick = {
                                    scope.launch {
                                        auth.currentUser?.uid?.let { uid ->
                                            firestore.collection("users").document(uid).update("firstDayOfWeek" to dayOption)
                                            userProfile = userProfile?.copy(firstDayOfWeek = dayOption)
                                            snackbarHostState.showSnackbar("Calendar set to start on $dayOption")
                                        }
                                    }
                                },
                                label = { Text(dayOption, fontSize = 12.sp) }
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = { showFeedbackDialog = true },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer)
            ) {
                Icon(Icons.Default.Warning, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Report a Bug")
            }

            if (isSuperAdmin) {
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedButton(
                    onClick = onNavigateToReports,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.AutoMirrored.Filled.List, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("View Bug Reports")
                }
            }
            
            Spacer(modifier = Modifier.height(48.dp))
            
            Text(
                text = "© 2026 Crewsync Team",
                style = MaterialTheme.typography.labelSmall,
                color = Color.Gray
            )
        }

        if (showFeedbackDialog) {
            FeedbackDialog(
                onDismiss = { showFeedbackDialog = false },
                onConfirm = { title, desc ->
                    scope.launch {
                        try {
                            val feedback = Feedback(
                                userId = auth.currentUser?.uid ?: "Anonymous",
                                userEmail = auth.currentUser?.email ?: "Anonymous",
                                title = title,
                                description = desc,
                                appVersion = getAppVersion(),
                                timestamp = Clock.System.now().toEpochMilliseconds()
                            )
                            firestore.collection("feedback").add(feedback)
                            showFeedbackDialog = false
                            snackbarHostState.showSnackbar("Thank you for your feedback!")
                        } catch (e: Exception) {
                            snackbarHostState.showSnackbar("Failed to send feedback: ${e.message}")
                        }
                    }
                }
            )
        }
    }
}

@Composable
fun FeedbackDialog(onDismiss: () -> Unit, onConfirm: (String, String) -> Unit) {
    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Report a Bug") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("What happened?") },
                    placeholder = { Text("e.g. App crashed on login") },
                    modifier = Modifier.fillMaxWidth()
                )
                TextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Details") },
                    placeholder = { Text("Describe the steps to reproduce...") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(title, description) },
                enabled = title.isNotBlank() && description.isNotBlank()
            ) {
                Text("Send Report")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
