package com.djransom.crewsync.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.djransom.crewsync.data.model.Environment
import com.djransom.crewsync.data.model.User
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.firestore.firestore
import kotlinx.coroutines.launch
import kotlin.time.Clock

// Company doc ids double as invite codes (see Environment.kt) - unambiguous, no lookalike
// characters (no 0/O or 1/I/L), short enough to read aloud or type from memory.
private const val INVITE_CODE_CHARS = "23456789ABCDEFGHJKMNPQRSTUVWXYZ"
fun generateInviteCode(length: Int = 8): String =
    (1..length).map { INVITE_CODE_CHARS.random() }.joinToString("")

@Composable
fun EnvironmentSwitcherRow(currentEnvironment: Environment?, onClick: () -> Unit) {
    Row(
        modifier = Modifier.clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.Home, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(6.dp))
        Text(
            currentEnvironment?.name?.ifBlank { "Unnamed Environment" } ?: "Loading…",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Icon(Icons.Default.ArrowDropDown, contentDescription = "Switch Environment", tint = MaterialTheme.colorScheme.primary)
    }
}

// Fetches (once, not live - this list changes rarely) every Environment the user belongs to,
// so the switcher dialog can show names instead of raw ids.
@Composable
fun rememberMyEnvironments(userProfile: User?): List<Environment> {
    var environments by remember { mutableStateOf<List<Environment>>(emptyList()) }
    LaunchedEffect(userProfile?.environmentIds) {
        val ids = userProfile?.environmentIds ?: emptyList()
        if (ids.isEmpty()) {
            environments = emptyList()
            return@LaunchedEffect
        }
        val firestore = Firebase.firestore
        val fetched = ids.mapNotNull { id ->
            try {
                val snap = firestore.collection("environments").document(id).get()
                if (snap.exists) snap.data<Environment>().copy(id = snap.id) else null
            } catch (_: Exception) { null }
        }
        environments = fetched
    }
    return environments
}

@Composable
fun EnvironmentSwitcherDialog(
    userProfile: User,
    environments: List<Environment>,
    onDismiss: () -> Unit,
    onSwitch: (String) -> Unit,
    onCreated: (String) -> Unit,
    onJoined: (String) -> Unit
) {
    val firestore = Firebase.firestore
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var showCreateDialog by remember { mutableStateOf(false) }
    var showJoinDialog by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var copiedCodeId by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Your Environments") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (environments.isEmpty()) {
                    Text("Loading…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    environments.forEach { env ->
                        val isActive = env.id == userProfile.activeEnvironmentId
                        var showChangeCodeDialog by remember(env.id) { mutableStateOf(false) }
                        ListItem(
                            headlineContent = { Text(env.name.ifBlank { "Unnamed Environment" }, fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal) },
                            supportingContent = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        if (copiedCodeId == env.id) "Copied!" else "Invite code: ${env.id}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (copiedCodeId == env.id) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    TextButton(
                                        onClick = {
                                            clipboard.setText(AnnotatedString(env.id))
                                            copiedCodeId = env.id
                                            scope.launch {
                                                kotlinx.coroutines.delay(1500)
                                                if (copiedCodeId == env.id) copiedCodeId = null
                                            }
                                        },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                        modifier = Modifier.height(24.dp)
                                    ) {
                                        Text("Copy", style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            },
                            trailingContent = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (env.ownerId == userProfile.uid) {
                                        IconButton(onClick = { showChangeCodeDialog = true }, modifier = Modifier.size(32.dp)) {
                                            Icon(Icons.Default.Edit, contentDescription = "Change Invite Code", modifier = Modifier.size(18.dp))
                                        }
                                    }
                                    if (isActive) Icon(Icons.Default.CheckCircle, contentDescription = "Current", tint = MaterialTheme.colorScheme.primary)
                                }
                            },
                            modifier = Modifier.clickable(enabled = !isActive) { onSwitch(env.id) }
                        )
                        if (showChangeCodeDialog) {
                            ChangeInviteCodeDialog(
                                environment = env,
                                onDismiss = { showChangeCodeDialog = false },
                                onConfirm = { newCode ->
                                    scope.launch {
                                        errorMessage = null
                                        try {
                                            val cleanNewCode = newCode.trim().uppercase().filter { it.isLetterOrDigit() }
                                            changeEnvironmentInviteCode(env, cleanNewCode)
                                            showChangeCodeDialog = false
                                            onSwitch(cleanNewCode)
                                        } catch (e: Exception) {
                                            errorMessage = "Couldn't change code: ${e.message ?: "unknown error"}"
                                            showChangeCodeDialog = false
                                        }
                                    }
                                }
                            )
                        }
                    }
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                if (errorMessage != null) {
                    Text(errorMessage!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                }
                OutlinedButton(onClick = { showCreateDialog = true }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Create New Environment")
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { showJoinDialog = true }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Person, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Join with Invite Code")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )

    if (showCreateDialog) {
        CreateEnvironmentDialog(
            onDismiss = { showCreateDialog = false },
            onConfirm = { name, customCode ->
                scope.launch {
                    errorMessage = null
                    try {
                        val requestedId = customCode.trim().uppercase().filter { it.isLetterOrDigit() }
                        val newId = if (requestedId.isNotEmpty()) {
                            val existing = firestore.collection("environments").document(requestedId).get()
                            if (existing.exists) {
                                errorMessage = "That invite code is already taken - try another."
                                return@launch
                            }
                            requestedId
                        } else {
                            generateInviteCode()
                        }
                        val env = Environment(
                            id = newId,
                            name = name,
                            ownerId = userProfile.uid,
                            createdAt = Clock.System.now().toEpochMilliseconds()
                        )
                        firestore.collection("environments").document(newId).set(env)
                        val updatedIds = userProfile.environmentIds + newId
                        firestore.collection("users").document(userProfile.uid).update("environmentIds" to updatedIds)
                        firestore.collection("users").document(userProfile.uid).update("activeEnvironmentId" to newId)
                        showCreateDialog = false
                        onCreated(newId)
                    } catch (e: Exception) {
                        errorMessage = "Couldn't create environment: ${e.message ?: "unknown error"}"
                    }
                }
            }
        )
    }

    if (showJoinDialog) {
        JoinEnvironmentDialog(
            onDismiss = { showJoinDialog = false },
            onConfirm = { code ->
                scope.launch {
                    errorMessage = null
                    try {
                        val cleanCode = code.trim().uppercase().filter { it.isLetterOrDigit() }
                        val snap = firestore.collection("environments").document(cleanCode).get()
                        if (!snap.exists) {
                            errorMessage = "No environment found for that invite code."
                            return@launch
                        }
                        if (!userProfile.environmentIds.contains(cleanCode)) {
                            val updatedIds = userProfile.environmentIds + cleanCode
                            firestore.collection("users").document(userProfile.uid).update("environmentIds" to updatedIds)
                        }
                        firestore.collection("users").document(userProfile.uid).update("activeEnvironmentId" to cleanCode)
                        showJoinDialog = false
                        onJoined(cleanCode)
                    } catch (e: Exception) {
                        errorMessage = "Couldn't join: ${e.message ?: "unknown error"}"
                    }
                }
            }
        )
    }
}

@Composable
fun CreateEnvironmentDialog(onDismiss: () -> Unit, onConfirm: (name: String, customCode: String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var customCode by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Name Your Environment") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("This is a private workspace for your projects, team, and contacts - e.g. your company name, or a family/personal list.", style = MaterialTheme.typography.bodySmall)
                TextField(value = name, onValueChange = { name = it }, label = { Text("Environment Name") }, modifier = Modifier.fillMaxWidth())
                TextField(
                    value = customCode,
                    onValueChange = { customCode = it },
                    label = { Text("Invite Code (optional)") },
                    placeholder = { Text("Leave blank for a random one") },
                    modifier = Modifier.fillMaxWidth()
                )
                Text("This is what you'll share with people to invite them - pick something memorable, or leave it blank.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(name.trim(), customCode) }, enabled = name.isNotBlank()) { Text("Create") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
fun ChangeInviteCodeDialog(environment: Environment, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var code by remember { mutableStateOf(environment.id) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Change Invite Code") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Anyone using the old code (\"${environment.id}\") won't be able to join with it anymore. Current members are unaffected.", style = MaterialTheme.typography.bodySmall)
                TextField(value = code, onValueChange = { code = it }, label = { Text("New Invite Code") }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(code) }, enabled = code.isNotBlank() && code.trim().uppercase() != environment.id) { Text("Change") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

// Moves an Environment to a new document id (the id IS the invite code - see Environment.kt),
// since Firestore doc ids can't be renamed in place: creates the new doc, repoints every
// environment-scoped document and every member's environmentIds/activeEnvironmentId at it,
// then deletes the old one. The caller must currently belong to `environment` - membership is
// what the security rules check against the OLD id throughout this process.
suspend fun changeEnvironmentInviteCode(environment: Environment, newCode: String) {
    val firestore = Firebase.firestore
    val oldId = environment.id

    val existing = firestore.collection("environments").document(newCode).get()
    if (existing.exists) throw Exception("That invite code is already taken - try another.")

    firestore.collection("environments").document(newCode).set(environment.copy(id = newCode))

    val collections = listOf("projects", "tasks", "contacts", "broadcasts")
    for (col in collections) {
        val snap = firestore.collection(col).where { "environmentId" equalTo oldId }.get()
        snap.documents.forEach { doc -> doc.reference.update("environmentId" to newCode) }
    }

    val memberSnap = firestore.collection("users").where { "environmentIds" contains oldId }.get()
    memberSnap.documents.forEach { doc ->
        val user = try { doc.data<User>() } catch (_: Exception) { null } ?: return@forEach
        val updatedIds = user.environmentIds.map { if (it == oldId) newCode else it }
        doc.reference.update("environmentIds" to updatedIds)
        if (user.activeEnvironmentId == oldId) {
            doc.reference.update("activeEnvironmentId" to newCode)
        }
    }

    firestore.collection("environments").document(oldId).delete()
}

@Composable
fun JoinEnvironmentDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var code by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Join an Environment") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Enter the invite code someone shared with you.", style = MaterialTheme.typography.bodySmall)
                TextField(value = code, onValueChange = { code = it }, label = { Text("Invite Code") }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(code) }, enabled = code.isNotBlank()) { Text("Join") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
