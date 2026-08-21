package com.djransom.crewsync.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.djransom.crewsync.data.model.Contact
import com.djransom.crewsync.data.model.User
import com.djransom.crewsync.util.makePhoneCall
import com.djransom.crewsync.util.sendEmail
import com.djransom.crewsync.util.rememberContactPickerLauncher
import com.djransom.crewsync.util.rememberCameraLauncher
import com.djransom.crewsync.util.recognizeTextInImage
import com.djransom.crewsync.util.parseBusinessCardText
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.auth.auth
import dev.gitlive.firebase.firestore.firestore
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactsScreen() {
    val firestore = Firebase.firestore
    val auth = Firebase.auth
    val scope = rememberCoroutineScope()
    
    var userProfile by remember { mutableStateOf<User?>(null) }
    var selectedTab by remember { mutableStateOf(0) }
    val tabs = listOf("Directory", "Company", "Subcontractors")
    
    var contactToEdit by remember { mutableStateOf<Contact?>(null) }
    var showAddContactDialog by remember { mutableStateOf(false) }
    var showAddUserDialog by remember { mutableStateOf(false) }
    var isScanningCard by remember { mutableStateOf(false) }

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

    val contactPickerLauncher = rememberContactPickerLauncher { picked ->
        scope.launch {
            val newContact = Contact(
                name = picked.name,
                email = picked.email,
                phone = picked.phone,
                type = if (selectedTab == 1) "Company" else "Subcontractor"
            )
            firestore.collection("contacts").add(newContact)
        }
    }

    val cardScanCameraLauncher = rememberCameraLauncher { pickedFile ->
        scope.launch {
            isScanningCard = true
            try {
                val text = recognizeTextInImage(pickedFile.platformFile)
                val parsed = parseBusinessCardText(text)
                contactToEdit = Contact(
                    name = parsed.name,
                    jobTitle = parsed.jobTitle,
                    company = parsed.company,
                    email = parsed.email,
                    phone = parsed.phone,
                    type = if (selectedTab == 1) "Company" else "Subcontractor"
                )
                showAddContactDialog = true
            } catch (_: Exception) {
            } finally {
                isScanningCard = false
            }
        }
    }

    val registeredUsersFlow = remember {
        firestore.collection("users")
            .snapshots
            .map { snapshot -> 
                snapshot.documents.mapNotNull { doc -> 
                    try {
                        doc.data<User>().let { if (it.uid.isEmpty()) it.copy(uid = doc.id) else it }
                    } catch (e: Exception) { null }
                }
            }
    }
    val registeredUsers by registeredUsersFlow.collectAsState(initial = emptyList())

    val manualContactsFlow = remember {
        firestore.collection("contacts")
            .snapshots
            .map { snapshot -> 
                snapshot.documents.mapNotNull { doc -> 
                    try {
                        doc.data<Contact>().copy(id = doc.id)
                    } catch (e: Exception) { null }
                }
            }
    }
    val manualContacts by manualContactsFlow.collectAsState(initial = emptyList())

    Scaffold(
        topBar = {
            Column {
                TopAppBar(title = { Text("Master Contacts") })
                PrimaryTabRow(selectedTabIndex = selectedTab) {
                    tabs.forEachIndexed { index, title ->
                        Tab(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            text = { Text(title) }
                        )
                    }
                }
            }
        },
        floatingActionButton = {
            if (isAdmin) {
                if (selectedTab == 0 && isSuperAdmin) {
                    FloatingActionButton(onClick = { showAddUserDialog = true }) {
                        Icon(Icons.Default.Add, contentDescription = "Add User")
                    }
                } else if (selectedTab != 0) {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        SmallFloatingActionButton(
                            onClick = { cardScanCameraLauncher() },
                            containerColor = MaterialTheme.colorScheme.secondaryContainer
                        ) {
                            Icon(Icons.Default.Info, contentDescription = "Scan Business Card")
                        }
                        SmallFloatingActionButton(
                            onClick = { contactPickerLauncher() },
                            containerColor = MaterialTheme.colorScheme.secondaryContainer
                        ) {
                            Icon(Icons.Default.Person, contentDescription = "Import from Phone")
                        }
                        FloatingActionButton(onClick = {
                            contactToEdit = null
                            showAddContactDialog = true
                        }) {
                            Icon(Icons.Default.Add, contentDescription = "Add Contact")
                        }
                    }
                }
            }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (selectedTab) {
                0 -> {
                    if (registeredUsers.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("No users found.")
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(registeredUsers) { user ->
                                UserCard(
                                    user = user, 
                                    showAdminActions = isAdmin,
                                    canManageAdmins = isSuperAdmin,
                                    currentUserEmail = auth.currentUser?.email ?: "",
                                    onRoleChange = { newRole ->
                                        scope.launch {
                                            firestore.collection("users").document(user.uid).update("role" to newRole)
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
                else -> {
                    val type = if (selectedTab == 1) "Company" else "Subcontractor"
                    val filtered = manualContacts.filter { it.type == type }
                    
                    if (filtered.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("No contacts in this list.")
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(filtered) { contact ->
                                ContactCard(
                                    contact = contact, 
                                    showAdminActions = isAdmin, 
                                    onDelete = {
                                        scope.launch {
                                            firestore.collection("contacts").document(contact.id).delete()
                                        }
                                    },
                                    onEdit = {
                                        contactToEdit = contact
                                        showAddContactDialog = true
                                    },
                                    onCall = { makePhoneCall(it) },
                                    onEmail = { sendEmail(it) }
                                )
                            }
                        }
                    }
                }
            }
        }

        if (showAddUserDialog) {
            AddUserDialog(
                onDismiss = { showAddUserDialog = false },
                onConfirm = { newUser ->
                    scope.launch {
                        firestore.collection("users").document(newUser.email).set(newUser)
                        showAddUserDialog = false
                    }
                }
            )
        }

        if (isScanningCard) {
            AlertDialog(
                onDismissRequest = {},
                title = { Text("Scanning Business Card…") },
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(16.dp))
                        Text("Reading text from the photo. This can take a few seconds.")
                    }
                },
                confirmButton = {}
            )
        }

        if (showAddContactDialog) {
            ContactDialog(
                contact = contactToEdit,
                defaultType = if (selectedTab == 1) "Company" else "Subcontractor",
                onDismiss = { showAddContactDialog = false },
                onConfirm = { updatedContact ->
                    scope.launch {
                        if (updatedContact.id.isEmpty()) {
                            firestore.collection("contacts").add(updatedContact)
                        } else {
                            firestore.collection("contacts").document(updatedContact.id).set(updatedContact)
                        }
                        showAddContactDialog = false
                    }
                }
            )
        }
    }
}

@Composable
fun AddUserDialog(onDismiss: () -> Unit, onConfirm: (User) -> Unit) {
    var email by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var trade by remember { mutableStateOf("") }
    var role by remember { mutableStateOf("Member") }
    var expanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Invite User to Crewsync") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                TextField(
                    value = email, 
                    onValueChange = { email = it }, 
                    label = { Text("Email Address") }, 
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = true)
                )
                TextField(
                    value = name, 
                    onValueChange = { name = it }, 
                    label = { Text("Full Name") }, 
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, autoCorrectEnabled = true)
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
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, autoCorrectEnabled = true)
                )
                
                Box {
                    OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("Default Role: $role")
                    }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        DropdownMenuItem(text = { Text("Member") }, onClick = { role = "Member"; expanded = false })
                        DropdownMenuItem(text = { Text("Admin") }, onClick = { role = "Admin"; expanded = false })
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { 
                onConfirm(User(email = email, name = name, phone = phone, trade = trade, role = role)) 
            }, enabled = email.isNotBlank()) {
                Text("Add to Directory")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
fun UserCard(user: User, showAdminActions: Boolean, canManageAdmins: Boolean, currentUserEmail: String, onRoleChange: (String) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = when (user.role) {
            "SuperAdmin" -> CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
            "Admin" -> CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            else -> CardDefaults.cardColors()
        }
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (user.profilePictureUrl != null) {
                AsyncImage(
                    model = user.profilePictureUrl,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp).clip(CircleShape),
                    contentScale = ContentScale.Crop
                )
            } else {
                Icon(
                    imageVector = when (user.role) {
                        "SuperAdmin" -> Icons.Default.Lock
                        "Admin" -> Icons.Default.Star
                        else -> Icons.Default.Person
                    },
                    contentDescription = null,
                    tint = when (user.role) {
                        "SuperAdmin" -> MaterialTheme.colorScheme.tertiary
                        "Admin" -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurface
                    }
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = user.name.ifEmpty { user.email }, 
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                if (user.name.isNotEmpty()) {
                    Text(text = user.email, style = MaterialTheme.typography.bodySmall)
                }
                if (user.trade.isNotEmpty()) {
                    Text(text = "Trade: ${user.trade}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                }
                Text(text = "Role: ${user.role}", style = MaterialTheme.typography.labelSmall)
            }
            if (showAdminActions && user.email != currentUserEmail) {
                if (user.role == "Member") {
                    TextButton(onClick = { onRoleChange("Admin") }) {
                        Text("Make Admin")
                    }
                } else if (user.role == "Admin" && canManageAdmins) {
                    TextButton(onClick = { onRoleChange("Member") }) {
                        Text("Make Member")
                    }
                }
            }
            if (user.phone.isNotEmpty()) {
                IconButton(onClick = { makePhoneCall(user.phone) }) {
                    Icon(Icons.Default.Phone, contentDescription = "Call", tint = MaterialTheme.colorScheme.primary)
                }
            }
            IconButton(onClick = { sendEmail(user.email) }) {
                Icon(Icons.Default.Email, contentDescription = "Email", tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
fun ContactCard(contact: Contact, showAdminActions: Boolean, onDelete: () -> Unit, onEdit: () -> Unit, onCall: (String) -> Unit, onEmail: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = contact.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    if (contact.jobTitle.isNotEmpty()) {
                        Text(text = contact.jobTitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                    }
                    if (contact.company.isNotEmpty()) {
                        Text(text = contact.company, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Row {
                    if (showAdminActions) {
                        IconButton(onClick = onEdit) {
                            Icon(Icons.Default.Edit, contentDescription = "Edit")
                        }
                        IconButton(onClick = onDelete) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                    Icon(
                        if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = null
                    )
                }
            }

            if (expanded) {
                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Spacer(Modifier.height(12.dp))

                // Emails
                ContactInfoRow(Icons.Default.Email, contact.email) { onEmail(contact.email) }
                if (contact.secondaryEmail.isNotEmpty()) {
                    ContactInfoRow(Icons.Default.Email, contact.secondaryEmail, label = "Secondary") { onEmail(contact.secondaryEmail) }
                }

                // Phones
                ContactInfoRow(Icons.Default.Phone, contact.phone) { onCall(contact.phone) }
                if (contact.secondaryPhone.isNotEmpty()) {
                    ContactInfoRow(Icons.Default.Phone, contact.secondaryPhone, label = "Secondary") { onCall(contact.secondaryPhone) }
                }

                // Address
                if (contact.address.isNotEmpty()) {
                    ContactInfoRow(Icons.Default.Place, contact.address)
                }

                // Notes
                if (contact.notes.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text("Notes:", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                    Text(contact.notes, style = MaterialTheme.typography.bodySmall)
                }
            } else {
                // Compact view summary
                Row(modifier = Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (contact.phone.isNotEmpty()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Phone, null, modifier = Modifier.size(14.dp), tint = Color.Gray)
                            Spacer(Modifier.width(4.dp))
                            Text(contact.phone, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    if (contact.email.isNotEmpty()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Email, null, modifier = Modifier.size(14.dp), tint = Color.Gray)
                            Spacer(Modifier.width(4.dp))
                            Text(contact.email, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ContactInfoRow(icon: androidx.compose.ui.graphics.vector.ImageVector, value: String, label: String? = null, onClick: (() -> Unit)? = null) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).let { 
            if (onClick != null) it.clickable { onClick() } else it 
        },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Column {
            if (label != null) Text(label, style = MaterialTheme.typography.labelSmall, color = Color.Gray)
            Text(value, style = MaterialTheme.typography.bodyMedium)
        }
        if (onClick != null) {
            Spacer(Modifier.weight(1f))
            Icon(Icons.Default.ArrowForward, null, modifier = Modifier.size(14.dp), tint = Color.Gray)
        }
    }
}

@Composable
fun ContactDialog(contact: Contact?, defaultType: String, onDismiss: () -> Unit, onConfirm: (Contact) -> Unit) {
    var name by remember { mutableStateOf(contact?.name ?: "") }
    var jobTitle by remember { mutableStateOf(contact?.jobTitle ?: "") }
    var company by remember { mutableStateOf(contact?.company ?: "") }
    var email by remember { mutableStateOf(contact?.email ?: "") }
    var secondaryEmail by remember { mutableStateOf(contact?.secondaryEmail ?: "") }
    var phone by remember { mutableStateOf(contact?.phone ?: "") }
    var secondaryPhone by remember { mutableStateOf(contact?.secondaryPhone ?: "") }
    var address by remember { mutableStateOf(contact?.address ?: "") }
    var notes by remember { mutableStateOf(contact?.notes ?: "") }
    var type by remember { mutableStateOf(contact?.type ?: defaultType) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (contact == null) "Add New Contact" else "Edit Contact") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                TextField(
                    value = name, 
                    onValueChange = { name = it }, 
                    label = { Text("Full Name") }, 
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, autoCorrectEnabled = true)
                )
                TextField(
                    value = jobTitle, 
                    onValueChange = { jobTitle = it }, 
                    label = { Text("Job Title") }, 
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, autoCorrectEnabled = true)
                )
                TextField(
                    value = company, 
                    onValueChange = { company = it }, 
                    label = { Text("Company") }, 
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, autoCorrectEnabled = true)
                )
                
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Text("Communication", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                
                TextField(
                    value = email, 
                    onValueChange = { email = it }, 
                    label = { Text("Primary Email") }, 
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = true)
                )
                TextField(
                    value = secondaryEmail, 
                    onValueChange = { secondaryEmail = it }, 
                    label = { Text("Secondary Email") }, 
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = true)
                )
                TextField(
                    value = phone, 
                    onValueChange = { phone = it }, 
                    label = { Text("Primary Phone") }, 
                    modifier = Modifier.fillMaxWidth()
                )
                TextField(
                    value = secondaryPhone, 
                    onValueChange = { secondaryPhone = it }, 
                    label = { Text("Secondary Phone") }, 
                    modifier = Modifier.fillMaxWidth()
                )
                
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Text("Additional Details", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                
                TextField(
                    value = address, 
                    onValueChange = { address = it }, 
                    label = { Text("Physical Address") }, 
                    modifier = Modifier.fillMaxWidth(), 
                    minLines = 2,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, autoCorrectEnabled = true)
                )
                TextField(
                    value = notes, 
                    onValueChange = { notes = it }, 
                    label = { Text("Private Notes") }, 
                    modifier = Modifier.fillMaxWidth(), 
                    minLines = 3,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, autoCorrectEnabled = true)
                )
                
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = type == "Company", onClick = { type = "Company" })
                    Text("Company Internal")
                    Spacer(modifier = Modifier.width(16.dp))
                    RadioButton(selected = type == "Subcontractor", onClick = { type = "Subcontractor" })
                    Text("Sub")
                }
            }
        },
        confirmButton = {
            Button(onClick = { 
                onConfirm(Contact(
                    id = contact?.id ?: "",
                    name = name, 
                    jobTitle = jobTitle,
                    company = company,
                    email = email, 
                    secondaryEmail = secondaryEmail,
                    phone = phone, 
                    secondaryPhone = secondaryPhone,
                    address = address,
                    notes = notes,
                    type = type
                )) 
            }, enabled = name.isNotBlank() && email.isNotBlank()) {
                Text(if (contact == null) "Add" else "Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
