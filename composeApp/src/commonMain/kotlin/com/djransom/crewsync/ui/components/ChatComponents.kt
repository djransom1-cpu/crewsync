package com.djransom.crewsync.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.djransom.crewsync.data.model.ChatMessage
import com.djransom.crewsync.util.PickedFile
import com.djransom.crewsync.util.rememberFilePickerLauncher
import com.djransom.crewsync.util.openUrl

@Composable
fun ChatTab(
    messages: List<ChatMessage>, 
    currentUserEmail: String, 
    userMap: Map<String, String> = emptyMap(),
    userPicMap: Map<String, String?> = emptyMap(),
    onSendMessage: (String, String?, String?) -> Unit,
    onUploadAttachment: (PickedFile, (String) -> Unit) -> Unit
) {
    var text by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    var isUploadingAttachment by remember { mutableStateOf(false) }

    val attachmentLauncher = rememberFilePickerLauncher { pickedFile ->
        isUploadingAttachment = true
        onUploadAttachment(pickedFile) { url ->
            onSendMessage("", url, pickedFile.name)
            isUploadingAttachment = false
        }
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item { Spacer(modifier = Modifier.height(8.dp)) }
            items(messages) { msg ->
                val isMe = msg.senderEmail == currentUserEmail
                val displayName = userMap[msg.senderEmail] ?: msg.senderEmail
                val profilePic = userPicMap[msg.senderEmail]
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = if (isMe) Arrangement.End else Arrangement.Start,
                    verticalAlignment = Alignment.Bottom
                ) {
                    if (!isMe) {
                        if (profilePic != null) {
                            AsyncImage(
                                model = profilePic,
                                contentDescription = null,
                                modifier = Modifier.size(32.dp).clip(CircleShape),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            Box(modifier = Modifier.size(32.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Person, contentDescription = null, modifier = Modifier.size(20.dp))
                            }
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    
                    Surface(
                        color = if (isMe) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.medium
                    ) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            if (!isMe) Text(displayName, style = MaterialTheme.typography.labelSmall)
                            if (msg.text.isNotEmpty()) {
                                Text(msg.text)
                            }
                            if (msg.attachmentUrl != null) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .padding(top = 4.dp)
                                        .clickable { openUrl(msg.attachmentUrl) }
                                ) {
                                    Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = msg.attachmentName ?: "Attachment",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }
                    
                    if (isMe) {
                        Spacer(modifier = Modifier.width(8.dp))
                        if (profilePic != null) {
                            AsyncImage(
                                model = profilePic,
                                contentDescription = null,
                                modifier = Modifier.size(32.dp).clip(CircleShape),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            Box(modifier = Modifier.size(32.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Person, contentDescription = null, modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                }
            }
        }
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isUploadingAttachment) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
                Spacer(modifier = Modifier.width(16.dp))
            } else {
                IconButton(onClick = { attachmentLauncher() }) {
                    Icon(Icons.Default.Add, contentDescription = "Attach File")
                }
            }
            TextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Type a message...") }
            )
            IconButton(onClick = { 
                if (text.isNotBlank()) {
                    onSendMessage(text, null, null)
                    text = ""
                }
            }) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
            }
        }
    }
}
