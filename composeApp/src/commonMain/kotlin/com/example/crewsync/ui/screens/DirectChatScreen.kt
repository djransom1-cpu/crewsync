package com.example.crewsync.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.example.crewsync.data.model.ChatMessage
import com.example.crewsync.ui.components.ChatTab
import com.example.crewsync.util.uploadFile
import com.example.crewsync.util.toFirestoreMap
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.auth.auth
import dev.gitlive.firebase.firestore.firestore
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DirectChatScreen(otherUserEmail: String, onBack: () -> Unit) {
    val firestore = Firebase.firestore
    val auth = Firebase.auth
    val scope = rememberCoroutineScope()
    
    val currentUserEmail = auth.currentUser?.email ?: ""
    val chatId = listOf(currentUserEmail, otherUserEmail).sorted().joinToString("_").replace(".", ",")

    val messages by firestore.collection("direct_messages").document(chatId).collection("messages")
        .snapshots
        .map { snapshot -> 
            snapshot.documents.map { doc -> doc.data<ChatMessage>().copy(id = doc.id) }
                .sortedBy { it.timestamp }
        }
        .collectAsState(initial = emptyList())

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(otherUserEmail) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            ChatTab(
                messages = messages,
                currentUserEmail = currentUserEmail,
                onSendMessage = { text, url, name ->
                    scope.launch {
                        val msg = ChatMessage(
                            senderId = auth.currentUser?.uid ?: "",
                            senderEmail = currentUserEmail,
                            text = text,
                            attachmentUrl = url,
                            attachmentName = name,
                            timestamp = Clock.System.now().toEpochMilliseconds()
                        )
                        firestore.collection("direct_messages").document(chatId).collection("messages").add(msg.toFirestoreMap())
                    }
                },
                onUploadAttachment = { pickedFile, onUrlReady ->
                    scope.launch {
                        try {
                            val path = "direct_chat_attachments/$chatId/${pickedFile.name}"
                            val url = uploadFile(path, pickedFile.platformFile)
                            onUrlReady(url)
                        } catch (e: Exception) {}
                    }
                }
            )
        }
    }
}
