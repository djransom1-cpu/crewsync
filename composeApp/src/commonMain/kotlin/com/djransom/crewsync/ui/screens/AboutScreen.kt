package com.djransom.crewsync.ui.screens

import com.djransom.crewsync.i18n.t
import com.djransom.crewsync.i18n.I18n
import com.djransom.crewsync.i18n.systemLanguageCode
import com.djransom.crewsync.util.rememberSettings
import androidx.compose.foundation.clickable
import androidx.compose.material3.RadioButton
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
import kotlin.time.Clock

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onNavigateToReports: () -> Unit) {
    val firestore = Firebase.firestore
    val auth = Firebase.auth
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    
    var userProfile by remember { mutableStateOf<User?>(null) }
    var showFeedbackDialog by remember { mutableStateOf(false) }
    val appSettings = rememberSettings()

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
            TopAppBar(title = { Text(t("About Crewsync")) })
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
                text = t("Construction Crew Management"),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = MaterialTheme.shapes.medium
            ) {
                Text(
                    text = t("Version {0}", getAppVersion()),
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
                    Text(t("Calendar Preferences"), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                    Text(t("First day of the week:"), fontSize = 12.sp, color = Color.Gray)
                    
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
                                            snackbarHostState.showSnackbar(t("Calendar set to start on {0}", t(dayOption)))
                                        }
                                    }
                                },
                                label = { Text(t(dayOption), fontSize = 12.sp) }
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Language: the device's language unless one is picked here (kept on this device)
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(t("Language"), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                    val systemName = I18n.languages.firstOrNull { it.first == systemLanguageCode().lowercase().take(2) }?.second ?: "English"
                    val choices = listOf("" to t("Same as this device ({0})", systemName)) + I18n.languages
                    choices.forEach { (code, name) ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { I18n.choose(code) { appSettings.putString(I18n.SETTINGS_KEY, it) } }
                        ) {
                            RadioButton(
                                selected = I18n.chosen == code,
                                onClick = { I18n.choose(code) { appSettings.putString(I18n.SETTINGS_KEY, it) } }
                            )
                            Text(name, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    Text(
                        t("Task names, notes and messages stay the way people typed them."),
                        fontSize = 12.sp, color = Color.Gray
                    )
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
                Text(t("Report a Bug"))
            }

            if (isSuperAdmin) {
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedButton(
                    onClick = onNavigateToReports,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.AutoMirrored.Filled.List, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(t("View Bug Reports"))
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
                            snackbarHostState.showSnackbar(t("Thank you for your feedback!"))
                        } catch (e: Exception) {
                            snackbarHostState.showSnackbar(t("Failed to send feedback: {0}", e.message))
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
        title = { Text(t("Report a Bug")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(t("What happened?")) },
                    placeholder = { Text(t("e.g. App crashed on login")) },
                    modifier = Modifier.fillMaxWidth()
                )
                TextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text(t("Details")) },
                    placeholder = { Text(t("Describe the steps to reproduce...")) },
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
                Text(t("Send Report"))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(t("Cancel"))
            }
        }
    )
}
