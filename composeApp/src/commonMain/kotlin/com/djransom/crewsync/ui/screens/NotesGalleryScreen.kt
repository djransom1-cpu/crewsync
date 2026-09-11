package com.djransom.crewsync.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.djransom.crewsync.data.model.Note
import com.djransom.crewsync.util.parseColor
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.firestore.firestore
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

// Stateful wrapper that switches between the note gallery and an open note's canvas, mirroring
// how FilesTab keeps its own currentFolderId - selection state that's local to this tab rather
// than one of ProjectDetailsScreen's top-level tab indices.
@Composable
fun NotesTab(projectId: String, notes: List<Note>, currentUserEmail: String) {
    var openNoteId by rememberSaveable { mutableStateOf<String?>(null) }
    val openNote = notes.find { it.id == openNoteId }

    if (openNoteId != null && openNote != null) {
        NotepadScreen(
            projectId = projectId,
            noteId = openNote.id,
            noteTitle = openNote.title,
            onBack = { openNoteId = null }
        )
    } else {
        NotesGalleryScreen(
            projectId = projectId,
            notes = notes,
            currentUserEmail = currentUserEmail,
            onOpenNote = { openNoteId = it }
        )
    }
}

@Composable
fun NotesGalleryScreen(
    projectId: String,
    notes: List<Note>,
    currentUserEmail: String,
    onOpenNote: (String) -> Unit
) {
    val firestore = Firebase.firestore
    val scope = rememberCoroutineScope()
    var showAddDialog by remember { mutableStateOf(false) }
    var noteToDelete by remember { mutableStateOf<Note?>(null) }

    val sortedNotes = remember(notes) { notes.sortedByDescending { it.updatedAt } }

    Box(modifier = Modifier.fillMaxSize()) {
        if (sortedNotes.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(48.dp), tint = Color.Gray)
                Spacer(modifier = Modifier.height(8.dp))
                Text("No notes yet.", style = MaterialTheme.typography.titleMedium)
                Text("Tap + to add a sketch or note.", style = MaterialTheme.typography.bodyMedium, color = Color.Gray)
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 140.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(sortedNotes, key = { it.id }) { note ->
                    NoteCard(
                        note = note,
                        onClick = { onOpenNote(note.id) },
                        onDelete = { noteToDelete = note }
                    )
                }
            }
        }

        FloatingActionButton(
            onClick = { showAddDialog = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)
        ) {
            Icon(Icons.Default.Add, contentDescription = "New Note")
        }
    }

    if (showAddDialog) {
        AddNoteDialog(
            onDismiss = { showAddDialog = false },
            onConfirm = { title, colorHex ->
                showAddDialog = false
                scope.launch {
                    val now = Clock.System.now().toEpochMilliseconds()
                    val note = Note(
                        title = title,
                        color = colorHex,
                        createdBy = currentUserEmail,
                        createdAt = now,
                        updatedAt = now
                    )
                    val ref = firestore.collection("projects").document(projectId).collection("notes").add(note)
                    onOpenNote(ref.id)
                }
            }
        )
    }

    noteToDelete?.let { note ->
        AlertDialog(
            onDismissRequest = { noteToDelete = null },
            title = { Text("Delete \"${note.title.ifBlank { "Untitled Note" }}\"?") },
            text = { Text("This deletes the note and everything drawn on it. This can't be undone.") },
            confirmButton = {
                Button(
                    onClick = {
                        val target = note
                        noteToDelete = null
                        scope.launch {
                            val notesDoc = firestore.collection("projects").document(projectId).collection("notes").document(target.id)
                            notesDoc.collection("actions").get().documents.forEach { it.reference.delete() }
                            notesDoc.delete()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { noteToDelete = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
fun NoteCard(note: Note, onClick: () -> Unit, onDelete: () -> Unit) {
    val bg = try { Color(parseColor(note.color)) } catch (_: Exception) { Color(0xFFFFF9C4) }
    Card(
        modifier = Modifier.aspectRatio(1f).clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = bg)
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                IconButton(onClick = onDelete, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete Note", modifier = Modifier.size(16.dp))
                }
            }
            Spacer(modifier = Modifier.weight(1f))
            Text(
                note.title.ifBlank { "Untitled Note" },
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (note.updatedAt > 0) {
                Text(formatShortDateTime(note.updatedAt), style = MaterialTheme.typography.labelSmall, color = Color.DarkGray)
            }
        }
    }
}

@Composable
fun AddNoteDialog(onDismiss: () -> Unit, onConfirm: (title: String, colorHex: String) -> Unit) {
    var title by remember { mutableStateOf("") }
    val colors = listOf("#FFF9C4", "#FFCDD2", "#C8E6C9", "#BBDEFB", "#E1BEE7", "#FFE0B2")
    var selectedColor by remember { mutableStateOf(colors.first()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New Note") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Title (optional)") },
                    modifier = Modifier.fillMaxWidth()
                )
                Text("Color", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    colors.forEach { hex ->
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(Color(parseColor(hex)))
                                .border(
                                    width = if (hex == selectedColor) 3.dp else 1.dp,
                                    color = if (hex == selectedColor) MaterialTheme.colorScheme.primary else Color.Gray,
                                    shape = CircleShape
                                )
                                .clickable { selectedColor = hex }
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(title, selectedColor) }) { Text("Create") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

private fun formatShortDateTime(timestamp: Long): String {
    val date = Instant.fromEpochMilliseconds(timestamp).toLocalDateTime(TimeZone.currentSystemDefault())
    return "${date.monthNumber}/${date.dayOfMonth}"
}
