package com.example.crewsync.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.example.crewsync.data.model.*
import com.example.crewsync.util.*
import kotlinx.datetime.*

@Composable
fun ProjectHomeScreen(
    project: Project,
    tasks: List<Task>,
    appointments: List<Appointment>,
    messages: List<ChatMessage>,
    files: List<ProjectFile>,
    userMap: Map<String, String>,
    userPicMap: Map<String, String?>,
    onMoveCard: (String, Int) -> Unit,
    onResizeCard: (String, String) -> Unit,
    onNavigateToTab: (Int) -> Unit
) {
    val cardOrder = project.cardOrder
    val cardSizes = project.cardSizes

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item(span = { GridItemSpan(2) }) {
            Column {
                Text(
                    text = project.name,
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = if (project.location.isNotBlank()) "Project Dashboard • ${project.location}" else "Project Dashboard",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                val ownerLine = listOfNotNull(
                    project.ownerName.takeIf { it.isNotBlank() },
                    project.ownerPhone.takeIf { it.isNotBlank() },
                    project.ownerEmail.takeIf { it.isNotBlank() }
                ).joinToString(" • ")
                if (ownerLine.isNotBlank()) {
                    Text(
                        text = "Owner: $ownerLine",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        items(cardOrder, span = { cardId ->
            val size = cardSizes[cardId] ?: "Large"
            GridItemSpan(if (size == "Large") 2 else 1)
        }) { cardId ->
            HomeCardWrapper(
                title = cardId,
                size = cardSizes[cardId] ?: "Large",
                onMoveUp = { onMoveCard(cardId, -1) },
                onMoveDown = { onMoveCard(cardId, 1) },
                onResize = { newSize -> onResizeCard(cardId, newSize) },
                isFirst = cardId == cardOrder.first(),
                isLast = cardId == cardOrder.last(),
                onHeaderClick = {
                    val index = when(cardId) {
                        "Calendar" -> 5
                        "Tasks" -> 4
                        "Chat" -> 3
                        "Team" -> 1
                        "Files" -> 2
                        else -> 0
                    }
                    onNavigateToTab(index)
                }
            ) {
                when (cardId) {
                    "Weather" -> WeatherCard(project.location)
                    "Calendar" -> CalendarSummaryCard(appointments, tasks)
                    "Tasks" -> TasksSummaryCard(tasks)
                    "Chat" -> ChatSummaryCard(messages, userMap, userPicMap)
                    "Team" -> TeamSummaryCard(project.members, userPicMap)
                    "Files" -> FilesSummaryCard(files)
                }
            }
        }
        
        item(span = { GridItemSpan(2) }) { Spacer(modifier = Modifier.height(32.dp)) }
    }
}

@Composable
fun HomeCardWrapper(
    title: String,
    size: String,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onResize: (String) -> Unit,
    isFirst: Boolean,
    isLast: Boolean,
    onHeaderClick: () -> Unit,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    .clickable { onHeaderClick() }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title, 
                    style = MaterialTheme.typography.titleSmall, 
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 8.dp)
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { onResize(if (size == "Large") "Small" else "Large") }, modifier = Modifier.size(28.dp)) {
                        Icon(
                            Icons.Default.Build, 
                            contentDescription = "Resize", 
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    if (!isFirst) {
                        IconButton(onClick = onMoveUp, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.KeyboardArrowUp, contentDescription = null, modifier = Modifier.size(16.dp))
                        }
                    }
                    if (!isLast) {
                        IconButton(onClick = onMoveDown, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.KeyboardArrowDown, contentDescription = null, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
            Box(modifier = Modifier.padding(12.dp)) {
                content()
            }
        }
    }
}

@Composable
fun WeatherCard(location: String) {
    var weather by remember(location) { mutableStateOf<LocalWeather?>(null) }
    var isLoading by remember(location) { mutableStateOf(true) }
    var failed by remember(location) { mutableStateOf(false) }

    LaunchedEffect(location) {
        isLoading = true
        failed = false
        weather = fetchLocalWeather(location)
        failed = weather == null
        isLoading = false
    }

    when {
        location.isBlank() -> {
            Text("No project address set.", style = MaterialTheme.typography.bodyMedium, color = Color.Gray)
        }
        isLoading -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Text("Loading weather…", style = MaterialTheme.typography.bodyMedium, color = Color.Gray)
            }
        }
        failed || weather == null -> {
            Text("Weather unavailable for this address.", style = MaterialTheme.typography.bodyMedium, color = Color.Gray)
        }
        else -> {
            val w = weather!!
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("${w.currentTempF}°F", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text(w.shortForecast, style = MaterialTheme.typography.bodyMedium)
                    if (w.highF != null && w.lowF != null) {
                        Text("High: ${w.highF}° Low: ${w.lowF}°", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                    }
                }
                Icon(
                    Icons.Default.Star,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = Color(0xFFFFD600)
                )
            }
        }
    }
}

@Composable
fun CalendarSummaryCard(appointments: List<Appointment>, tasks: List<Task>) {
    val now = Clock.System.now().toEpochMilliseconds()
    val upcomingAppts = appointments.filter { it.startDate >= now }.sortedBy { it.startDate }.take(2)
    val upcomingTasks = tasks.filter { it.dueDate != null && it.dueDate!! >= now }.sortedBy { it.dueDate }.take(2)

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (upcomingAppts.isEmpty() && upcomingTasks.isEmpty()) {
            Text("No upcoming events.", style = MaterialTheme.typography.bodyMedium, color = Color.Gray)
        } else {
            upcomingAppts.forEach { appt ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.DateRange, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text(appt.title, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                }
            }
            upcomingTasks.forEach { task ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.secondary)
                    Spacer(Modifier.width(8.dp))
                    Text(task.title, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                }
            }
        }
    }
}

@Composable
fun TasksSummaryCard(tasks: List<Task>) {
    val total = tasks.size
    val done = tasks.count { it.status == "Done" }
    
    Column {
        val progress = if (total > 0) done.toFloat() / total.toFloat() else 0f
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape)
        )
        Spacer(Modifier.height(8.dp))
        Text("$done / $total Tasks Done", style = MaterialTheme.typography.bodySmall)
        
        Spacer(Modifier.height(8.dp))
        tasks.filter { it.status != "Done" }.take(2).forEach { task ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(Color(parseColor(task.color))))
                Spacer(Modifier.width(8.dp))
                Text(task.title, style = MaterialTheme.typography.labelSmall, maxLines = 1)
            }
        }
    }
}

@Composable
fun ChatSummaryCard(messages: List<ChatMessage>, userMap: Map<String, String>, userPicMap: Map<String, String?>) {
    val lastMsg = messages.lastOrNull()
    if (lastMsg == null) {
        Text("No messages yet.", style = MaterialTheme.typography.bodyMedium, color = Color.Gray)
    } else {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val profilePic = userPicMap[lastMsg.senderEmail]
            if (profilePic != null) {
                AsyncImage(
                    model = profilePic,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp).clip(CircleShape),
                    contentScale = ContentScale.Crop
                )
            } else {
                Box(modifier = Modifier.size(24.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Person, contentDescription = null, modifier = Modifier.size(14.dp))
                }
            }
            Spacer(Modifier.width(8.dp))
            Text(lastMsg.text, style = MaterialTheme.typography.bodySmall, maxLines = 1)
        }
    }
}

@Composable
fun TeamSummaryCard(members: List<String>, userPicMap: Map<String, String?>) {
    Row(horizontalArrangement = Arrangement.spacedBy((-12).dp)) {
        members.take(5).forEach { email ->
            val pic = userPicMap[email]
            if (pic != null) {
                AsyncImage(
                    model = pic,
                    contentDescription = null,
                    modifier = Modifier.size(32.dp).clip(CircleShape).border(1.dp, Color.White, CircleShape),
                    contentScale = ContentScale.Crop
                )
            } else {
                Box(modifier = Modifier.size(32.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer).border(1.dp, Color.White, CircleShape), contentAlignment = Alignment.Center) {
                    Text(email.take(1).uppercase(), style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
fun FilesSummaryCard(files: List<ProjectFile>) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (files.isEmpty()) {
            Text("No files.", style = MaterialTheme.typography.bodyMedium, color = Color.Gray)
        } else {
            files.take(2).forEach { file ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(12.dp), tint = Color.Gray)
                    Spacer(Modifier.width(4.dp))
                    Text(file.name, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                }
            }
        }
    }
}

private fun formatShortDate(timestamp: Long): String {
    val instant = Instant.fromEpochMilliseconds(timestamp)
    val date = instant.toLocalDateTime(TimeZone.currentSystemDefault())
    return "${date.monthNumber}/${date.dayOfMonth}"
}
