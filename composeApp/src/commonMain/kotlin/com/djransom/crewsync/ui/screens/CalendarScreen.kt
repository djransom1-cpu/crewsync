package com.djransom.crewsync.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.djransom.crewsync.data.model.Appointment
import com.djransom.crewsync.data.model.Task
import com.djransom.crewsync.util.*
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.firestore.firestore
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.datetime.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarScreen(
    projectId: String,
    tasks: List<Task>,
    appointments: List<Appointment>,
    canEdit: Boolean,
    firstDayOfWeek: String = "Sunday"
) {
    val startDow = parseFirstDayOfWeek(firstDayOfWeek)
    val firestore = Firebase.firestore
    val scope = rememberCoroutineScope()
    
    var viewMode by rememberSaveable { mutableStateOf("Month") }
    var currentMonth by remember { mutableStateOf(Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date) }
    var selectedDate by remember { mutableStateOf(currentMonth) }
    
    var appointmentToEdit by remember { mutableStateOf<Appointment?>(null) }
    var showAddAppointmentDialog by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf<Appointment?>(null) }

    val selectedDateItems = (tasks.filter { task ->
        task.dueDate?.let { isSameDay(it, selectedDate) } == true ||
        task.startDate?.let { isSameDay(it, selectedDate) } == true ||
        (task.startDate != null && task.dueDate != null && selectedDate >= timestampToDate(task.startDate) && selectedDate <= timestampToDate(task.dueDate))
    }.map { CalendarItem.TaskItem(it) } + appointments.filter {
        isAppointmentOnDay(it, selectedDate)
    }.map { CalendarItem.AppointmentItem(it) }).sortedBy { it.sortKey }

    val calendarGrid: @Composable ColumnScope.() -> Unit = {
        CalendarHeader(
            currentMonth = currentMonth,
            viewMode = viewMode,
            onViewModeChange = { viewMode = it },
            onMonthChange = { currentMonth = it }
        )
        Box(modifier = Modifier.weight(1f)) {
            when (viewMode) {
                "Month" -> MonthView(currentMonth, selectedDate, tasks, appointments, startDow) { selectedDate = it }
                "Week" -> WeekView(selectedDate, tasks, appointments, startDow) { selectedDate = it }
                "Day" -> DayView(selectedDate, tasks, appointments)
            }
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        // In landscape the schedule list sits in a narrow side rail so the calendar itself
        // keeps its full height instead of getting squeezed short by a bottom panel. This has
        // to be an aspect-ratio check (width > height), not a fixed dp width threshold - large
        // tablets are comfortably past any reasonable width breakpoint even in portrait.
        val isWide = maxWidth > maxHeight

        if (isWide) {
            Row(modifier = Modifier.fillMaxSize()) {
                Column(modifier = Modifier.weight(1f).fillMaxHeight(), content = calendarGrid)
                VerticalDivider()
                ScheduleForDayPanel(
                    selectedDate = selectedDate,
                    calendarItems = selectedDateItems,
                    canEdit = canEdit,
                    onEdit = { appointmentToEdit = it },
                    onDelete = { showDeleteConfirm = it },
                    modifier = Modifier.width(300.dp).fillMaxHeight()
                )
            }
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                calendarGrid()
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                ScheduleForDayPanel(
                    selectedDate = selectedDate,
                    calendarItems = selectedDateItems,
                    canEdit = canEdit,
                    onEdit = { appointmentToEdit = it },
                    onDelete = { showDeleteConfirm = it },
                    modifier = Modifier.fillMaxWidth().weight(0.5f)
                )
            }
        }

        if (canEdit) {
            FloatingActionButton(
                onClick = {
                    appointmentToEdit = null
                    showAddAppointmentDialog = true
                },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add Appointment")
            }
        }
    }

    if (showAddAppointmentDialog || appointmentToEdit != null) {
        val key = appointmentToEdit?.id ?: "new_${selectedDate}"
        key(key) {
            AppointmentDialog(
                projectId = projectId,
                appointment = appointmentToEdit,
                initialDate = selectedDate,
                onDismiss = { 
                    showAddAppointmentDialog = false
                    appointmentToEdit = null
                },
                onConfirm = { appt ->
                    scope.launch {
                        if (appt.id.isEmpty()) {
                            firestore.collection("projects").document(projectId).collection("appointments").add(appt.toFirestoreMap())
                        } else {
                            firestore.collection("projects").document(projectId).collection("appointments").document(appt.id).set(appt.toFirestoreMap())
                        }
                        showAddAppointmentDialog = false
                        appointmentToEdit = null
                    }
                }
            )
        }
    }

    if (showDeleteConfirm != null) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = null },
            title = { Text("Delete Appointment?") },
            text = { Text("Are you sure you want to remove '${showDeleteConfirm!!.title}'?") },
            confirmButton = {
                Button(
                    onClick = {
                        val appt = showDeleteConfirm!!
                        scope.launch {
                            firestore.collection("projects").document(projectId).collection("appointments").document(appt.id).delete()
                            showDeleteConfirm = null
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = null }) { Text("Cancel") }
            }
        )
    }
}

sealed class CalendarItem {
    abstract val sortKey: Long
    data class TaskItem(val task: Task) : CalendarItem() {
        override val sortKey = task.startDate ?: 0L
    }
    data class AppointmentItem(val appointment: Appointment) : CalendarItem() {
        override val sortKey = appointment.startDate
    }
}

/** The "what's scheduled today" list - stacked under the calendar in portrait, or a narrow
 * side rail next to it in landscape so the calendar grid keeps its full height. */
@Composable
private fun ScheduleForDayPanel(
    selectedDate: LocalDate,
    calendarItems: List<CalendarItem>,
    canEdit: Boolean,
    onEdit: (Appointment) -> Unit,
    onDelete: (Appointment) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Text(
            text = "Schedule for ${selectedDate.month.name} ${selectedDate.dayOfMonth}, ${selectedDate.year}",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )

        if (calendarItems.isEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                Text("No events scheduled.", style = MaterialTheme.typography.bodyMedium, color = Color.Gray)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(calendarItems) { item ->
                    when (item) {
                        is CalendarItem.TaskItem -> TaskItemSimple(item.task)
                        is CalendarItem.AppointmentItem -> AppointmentItemCard(
                            appointment = item.appointment,
                            showActions = canEdit,
                            onEdit = onEdit,
                            onDelete = onDelete
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun CalendarHeader(
    currentMonth: LocalDate,
    viewMode: String,
    onViewModeChange: (String) -> Unit,
    onMonthChange: (LocalDate) -> Unit
) {
    var showViewMenu by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "${currentMonth.month.name} ${currentMonth.year}",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = {
                val prev = if (currentMonth.monthNumber == 1) {
                    LocalDate(currentMonth.year - 1, 12, 1)
                } else {
                    LocalDate(currentMonth.year, currentMonth.monthNumber - 1, 1)
                }
                onMonthChange(prev)
            }) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous")
            }
            IconButton(onClick = {
                val next = if (currentMonth.monthNumber == 12) {
                    LocalDate(currentMonth.year + 1, 1, 1)
                } else {
                    LocalDate(currentMonth.year, currentMonth.monthNumber + 1, 1)
                }
                onMonthChange(next)
            }) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next")
            }

            Box {
                TextButton(onClick = { showViewMenu = true }) {
                    Text(viewMode)
                    Icon(Icons.Default.ArrowDropDown, contentDescription = "Change view")
                }
                DropdownMenu(expanded = showViewMenu, onDismissRequest = { showViewMenu = false }) {
                    listOf("Day", "Week", "Month").forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option) },
                            onClick = {
                                onViewModeChange(option)
                                showViewMenu = false
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun MonthView(
    currentMonth: LocalDate,
    selectedDate: LocalDate,
    tasks: List<Task>,
    appointments: List<Appointment>,
    firstDayOfWeek: DayOfWeek = DayOfWeek.SUNDAY,
    onDateSelected: (LocalDate) -> Unit
) {
    val daysInMonth = getDaysInMonth(currentMonth.year, currentMonth.monthNumber)
    val firstDayOfMonth = LocalDate(currentMonth.year, currentMonth.monthNumber, 1)
    val dayOfWeekOffset = weekdayOffsetFrom(firstDayOfMonth, firstDayOfWeek)
    val weekDayLabels = orderedWeekDays(firstDayOfWeek)

    BoxWithConstraints(modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp)) {
        // Optimized Aspect Ratio for Fold 4 (Landscape vs Portrait)
        val cellAspectRatio = if (maxWidth > 600.dp) 1.8f else 0.8f

        Column {
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                weekDayLabels.forEach { dow ->
                    Text(
                        text = dow.name.lowercase().replaceFirstChar { it.uppercase() }.take(3),
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.Gray,
                        fontSize = 12.sp
                    )
                }
            }

            var cellIndex = 0
            for (row in 0 until 6) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    for (col in 0 until 7) {
                        val dayNumber = cellIndex - dayOfWeekOffset + 1
                        val date = if (dayNumber in 1..daysInMonth) {
                            LocalDate(currentMonth.year, currentMonth.monthNumber, dayNumber)
                        } else null

                        MonthCell(
                            modifier = Modifier.weight(1f).aspectRatio(cellAspectRatio),
                            date = date,
                            isSelected = date == selectedDate,
                            tasks = tasks,
                            appointments = appointments,
                            onClick = { date?.let { onDateSelected(it) } }
                        )
                        cellIndex++
                    }
                }
            }
        }
    }
}

@Composable
fun MonthCell(
    modifier: Modifier,
    date: LocalDate?,
    isSelected: Boolean,
    tasks: List<Task>,
    appointments: List<Appointment>,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .border(0.5.dp, Color.LightGray.copy(alpha = 0.2f))
            .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f) else Color.Transparent)
            .clickable(enabled = date != null) { onClick() }
            .padding(1.dp)
    ) {
        if (date != null) {
            Column {
                Text(
                    text = date.dayOfMonth.toString(),
                    modifier = Modifier.fillMaxWidth().padding(end = 4.dp),
                    textAlign = TextAlign.End,
                    style = MaterialTheme.typography.labelMedium,
                    fontSize = 13.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Gray
                )

                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    val dayTasks = tasks.filter { task ->
                        task.startDate != null && task.dueDate != null &&
                        date >= timestampToDate(task.startDate) && date <= timestampToDate(task.dueDate)
                    }
                    val dayAppts = appointments.filter { isAppointmentOnDay(it, date) }

                    val allEvents = (dayTasks.map { it.title to it.color } + dayAppts.map { it.title to it.color }).take(3)

                    allEvents.forEach { (title, colorHex) ->
                        val bgColor = Color(parseColor(colorHex))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(20.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(bgColor),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            Text(
                                text = title,
                                fontSize = 10.sp,
                                color = if (isDarkColor(bgColor)) Color.White else Color.Black,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(horizontal = 3.dp),
                                lineHeight = 12.sp
                            )
                        }
                    }
                    if (dayTasks.size + dayAppts.size > 3) {
                        Text(
                            "+${dayTasks.size + dayAppts.size - 3} more",
                            fontSize = 9.sp,
                            modifier = Modifier.padding(start = 2.dp),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun WeekView(
    selectedDate: LocalDate,
    tasks: List<Task>,
    appointments: List<Appointment>,
    firstDayOfWeek: DayOfWeek = DayOfWeek.SUNDAY,
    onDateSelected: (LocalDate) -> Unit
) {
    val offset = weekdayOffsetFrom(selectedDate, firstDayOfWeek)
    val startOfWeek = selectedDate.minus(offset.toLong(), DateTimeUnit.DAY)
    
    Column(modifier = Modifier.fillMaxSize().padding(8.dp)) {
        Row(modifier = Modifier.fillMaxWidth()) {
            for (i in 0 until 7) {
                val date = startOfWeek.plus(i.toLong(), DateTimeUnit.DAY)
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onDateSelected(date) }
                        .padding(4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(date.dayOfWeek.name.take(3), style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                    Text(
                        text = date.dayOfMonth.toString(),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = if (date == selectedDate) FontWeight.Bold else FontWeight.Normal,
                        color = if (date == selectedDate) MaterialTheme.colorScheme.primary else Color.Unspecified
                    )
                }
            }
        }
        
        HorizontalDivider()
        
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(24) { hour ->
                Row(modifier = Modifier.fillMaxWidth().height(68.dp)) {
                    Text(
                        text = if (hour == 0) "12 AM" else if (hour < 12) "$hour AM" else if (hour == 12) "12 PM" else "${hour-12} PM",
                        style = MaterialTheme.typography.labelMedium,
                        fontSize = 12.sp,
                        modifier = Modifier.width(48.dp).padding(top = 4.dp),
                        textAlign = TextAlign.End
                    )

                    Box(modifier = Modifier.weight(1f).fillMaxHeight().border(0.5.dp, Color.LightGray.copy(alpha = 0.2f))) {
                        Row(modifier = Modifier.fillMaxSize()) {
                            for (i in 0 until 7) {
                                val date = startOfWeek.plus(i.toLong(), DateTimeUnit.DAY)
                                Box(modifier = Modifier.weight(1f).fillMaxHeight().border(0.2.dp, Color.LightGray.copy(alpha = 0.1f))) {
                                    val hourAppts = appointments.filter { appt ->
                                        isAppointmentOnDay(appt, date) && !appt.isAllDay && timestampToHour(appt.startDate) == hour
                                    }
                                    Column {
                                        hourAppts.forEach { appt ->
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .height(26.dp)
                                                    .background(Color(parseColor(appt.color)).copy(alpha = 0.7f), RoundedCornerShape(3.dp))
                                                    .padding(3.dp)
                                            ) {
                                                Text(appt.title, fontSize = 11.sp, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DayView(
    selectedDate: LocalDate,
    tasks: List<Task>,
    appointments: List<Appointment>
) {
    LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        item {
            val allDayEvents = appointments.filter { it.isAllDay && isAppointmentOnDay(it, selectedDate) }
            if (allDayEvents.isNotEmpty()) {
                Text("All Day", style = MaterialTheme.typography.labelMedium)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    allDayEvents.forEach { appt ->
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color(parseColor(appt.color))),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(appt.title, modifier = Modifier.padding(8.dp), color = Color.White)
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
        
        items(24) { hour ->
            Row(modifier = Modifier.fillMaxWidth().height(84.dp)) {
                Text(
                    text = if (hour == 0) "12 AM" else if (hour < 12) "$hour AM" else if (hour == 12) "12 PM" else "${hour-12} PM",
                    style = MaterialTheme.typography.labelMedium,
                    fontSize = 13.sp,
                    modifier = Modifier.width(56.dp).padding(top = 8.dp)
                )
                Box(modifier = Modifier.weight(1f).fillMaxHeight().border(0.5.dp, Color.LightGray.copy(alpha = 0.3f))) {
                    val hourAppts = appointments.filter { appt ->
                        isAppointmentOnDay(appt, selectedDate) && !appt.isAllDay && timestampToHour(appt.startDate) == hour
                    }
                    val hourTasks = tasks.filter { task ->
                        task.startDate != null && isSameDay(task.startDate, selectedDate) && timestampToHour(task.startDate) == hour
                    }

                    Column(modifier = Modifier.fillMaxSize().padding(3.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        hourAppts.forEach { appt ->
                            Surface(
                                color = Color(parseColor(appt.color)),
                                shape = RoundedCornerShape(4.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(appt.title, modifier = Modifier.padding(6.dp), color = Color.White, fontSize = 14.sp)
                            }
                        }
                        hourTasks.forEach { task ->
                            Surface(
                                color = Color(parseColor(task.color)),
                                shape = RoundedCornerShape(4.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(task.title, modifier = Modifier.padding(6.dp), color = Color.White, fontSize = 14.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun TaskItemSimple(task: Task) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(12.dp).clip(CircleShape).background(Color(parseColor(task.color))))
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(text = task.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                Text(text = "Task: ${task.status}", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
            }
        }
    }
}

@Composable
fun AppointmentItemCard(appointment: Appointment, showActions: Boolean, onEdit: (Appointment) -> Unit, onDelete: (Appointment) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        colors = CardDefaults.cardColors(containerColor = Color(parseColor(appointment.color)).copy(alpha = 0.1f)),
        onClick = { if (showActions) onEdit(appointment) }
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.DateRange, contentDescription = null, tint = Color(parseColor(appointment.color)))
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = appointment.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                    val timeText = if (appointment.isAllDay) "All Day" else "${formatTime(appointment.startDate)} - ${formatTime(appointment.endDate)}"
                    val recurrenceText = if (appointment.recurrence != "None") " (${appointment.recurrence})" else ""
                    Text(text = "$timeText$recurrenceText", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                }
                Row {
                    IconButton(onClick = { addToExternalCalendar(appointment) }) {
                        Icon(Icons.Default.DateRange, contentDescription = "Add to Google Calendar", tint = MaterialTheme.colorScheme.primary)
                    }
                    if (showActions) {
                        IconButton(onClick = { onEdit(appointment) }) {
                            Icon(Icons.Default.Edit, contentDescription = "Edit")
                        }
                        IconButton(onClick = { onDelete(appointment) }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
            if (appointment.location.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                    Icon(Icons.Default.Place, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(text = appointment.location, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppointmentDialog(projectId: String, appointment: Appointment?, initialDate: LocalDate, onDismiss: () -> Unit, onConfirm: (Appointment) -> Unit) {
    var title by remember(appointment) { mutableStateOf(appointment?.title ?: "") }
    var description by remember(appointment) { mutableStateOf(appointment?.description ?: "") }
    var notes by remember(appointment) { mutableStateOf(appointment?.notes ?: "") }
    var location by remember(appointment) { mutableStateOf(appointment?.location ?: "") }
    var selectedColor by remember(appointment) { mutableStateOf(appointment?.color ?: "#2196F3") }
    
    var startDate by remember(appointment, initialDate) { 
        mutableStateOf(appointment?.let { timestampToDate(it.startDate) } ?: initialDate) 
    }
    var endDate by remember(appointment, initialDate) { 
        mutableStateOf(appointment?.let { timestampToDate(it.endDate) } ?: initialDate) 
    }
    var isAllDay by remember(appointment) { mutableStateOf(appointment?.isAllDay ?: false) }
    var recurrence by remember(appointment) { mutableStateOf(appointment?.recurrence ?: "None") }
    
    val startInstant = appointment?.let { Instant.fromEpochMilliseconds(it.startDate).toLocalDateTime(TimeZone.currentSystemDefault()) }
    val endInstant = appointment?.let { Instant.fromEpochMilliseconds(it.endDate).toLocalDateTime(TimeZone.currentSystemDefault()) }

    val startTimeState = rememberTimePickerState(
        initialHour = startInstant?.hour ?: 9, 
        initialMinute = startInstant?.minute ?: 0
    )
    val endTimeState = rememberTimePickerState(
        initialHour = endInstant?.hour ?: 10, 
        initialMinute = endInstant?.minute ?: 0
    )
    
    val colors = listOf("#2196F3", "#F44336", "#4CAF50", "#FFC107", "#9C27B0", "#FF9800", "#795548", "#607D8B")

    var showStartDatePicker by remember { mutableStateOf(false) }
    var showEndDatePicker by remember { mutableStateOf(false) }
    var showStartTimePicker by remember { mutableStateOf(false) }
    var showEndTimePicker by remember { mutableStateOf(false) }
    var showRecurrenceMenu by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (appointment == null) "New Appointment" else "Edit Appointment") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                TextField(
                    value = title, 
                    onValueChange = { title = it }, 
                    label = { Text("Title") }, 
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, autoCorrectEnabled = true)
                )
                
                Text("Label Color", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    colors.forEach { hex ->
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .clip(CircleShape)
                                .background(Color(parseColor(hex)))
                                .border(if (selectedColor == hex) 2.dp else 0.dp, Color.Black, CircleShape)
                                .clickable { selectedColor = hex }
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("All Day", modifier = Modifier.weight(1f))
                    Switch(checked = isAllDay, onCheckedChange = { isAllDay = it })
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { showStartDatePicker = true }, modifier = Modifier.weight(1f)) {
                        val dateText = "${startDate.month.name.take(3)} ${startDate.dayOfMonth}"
                        Text("Start: $dateText")
                    }
                    if (!isAllDay) {
                        Button(onClick = { showStartTimePicker = true }, modifier = Modifier.weight(1f)) {
                            Text(formatTime(startTimeState.hour, startTimeState.minute))
                        }
                    }
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { showEndDatePicker = true }, modifier = Modifier.weight(1f)) {
                        val dateText = "${endDate.month.name.take(3)} ${endDate.dayOfMonth}"
                        Text("End: $dateText")
                    }
                    if (!isAllDay) {
                        Button(onClick = { showEndTimePicker = true }, modifier = Modifier.weight(1f)) {
                            Text(formatTime(endTimeState.hour, endTimeState.minute))
                        }
                    }
                }

                Box {
                    OutlinedButton(onClick = { showRecurrenceMenu = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("Repeat: $recurrence")
                    }
                    DropdownMenu(expanded = showRecurrenceMenu, onDismissRequest = { showRecurrenceMenu = false }) {
                        listOf("None", "Daily", "Weekly", "Monthly").forEach { option ->
                            DropdownMenuItem(text = { Text(option) }, onClick = { 
                                recurrence = option
                                showRecurrenceMenu = false 
                            })
                        }
                    }
                }

                TextField(
                    value = location, 
                    onValueChange = { location = it }, 
                    label = { Text("Location") }, 
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, autoCorrectEnabled = true)
                )
                TextField(
                    value = description, 
                    onValueChange = { description = it }, 
                    label = { Text("Description") }, 
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, autoCorrectEnabled = true)
                )
            }
            
            if (showStartDatePicker) {
                val state = rememberDatePickerState(initialSelectedDateMillis = startDate.atStartOfDayIn(TimeZone.currentSystemDefault()).toEpochMilliseconds())
                DatePickerDialog(
                    onDismissRequest = { showStartDatePicker = false },
                    confirmButton = {
                        TextButton(onClick = {
                            state.selectedDateMillis?.let { 
                                startDate = Instant.fromEpochMilliseconds(it).toLocalDateTime(TimeZone.UTC).date 
                            }
                            showStartDatePicker = false
                        }) { Text("OK") }
                    }
                ) { DatePicker(state = state) }
            }
            if (showEndDatePicker) {
                val state = rememberDatePickerState(initialSelectedDateMillis = endDate.atStartOfDayIn(TimeZone.currentSystemDefault()).toEpochMilliseconds())
                DatePickerDialog(
                    onDismissRequest = { showEndDatePicker = false },
                    confirmButton = {
                        TextButton(onClick = {
                            state.selectedDateMillis?.let { 
                                endDate = Instant.fromEpochMilliseconds(it).toLocalDateTime(TimeZone.UTC).date 
                            }
                            showEndDatePicker = false
                        }) { Text("OK") }
                    }
                ) { DatePicker(state = state) }
            }
            if (showStartTimePicker) {
                AlertDialog(
                    onDismissRequest = { showStartTimePicker = false },
                    confirmButton = { TextButton(onClick = { showStartTimePicker = false }) { Text("OK") } },
                    text = { TimePicker(state = startTimeState) }
                )
            }
            if (showEndTimePicker) {
                AlertDialog(
                    onDismissRequest = { showEndTimePicker = false },
                    confirmButton = { TextButton(onClick = { showEndTimePicker = false }) { Text("OK") } },
                    text = { TimePicker(state = endTimeState) }
                )
            }
        },
        confirmButton = {
            Button(onClick = { 
                val start = LocalDateTime(startDate.year, startDate.monthNumber, startDate.dayOfMonth, startTimeState.hour, startTimeState.minute)
                    .toInstant(TimeZone.currentSystemDefault()).toEpochMilliseconds()
                val end = LocalDateTime(endDate.year, endDate.monthNumber, endDate.dayOfMonth, endTimeState.hour, endTimeState.minute)
                    .toInstant(TimeZone.currentSystemDefault()).toEpochMilliseconds()
                
                onConfirm(Appointment(
                    id = appointment?.id ?: "",
                    projectId = projectId,
                    title = title,
                    description = description,
                    notes = notes,
                    location = location,
                    color = selectedColor,
                    isAllDay = isAllDay,
                    startDate = start,
                    endDate = end,
                    recurrence = recurrence
                )) 
            }, enabled = title.isNotBlank()) { Text(if (appointment == null) "Add" else "Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

private fun parseFirstDayOfWeek(pref: String): DayOfWeek = when (pref) {
    "Monday" -> DayOfWeek.MONDAY
    "Saturday" -> DayOfWeek.SATURDAY
    else -> DayOfWeek.SUNDAY
}

/** kotlinx-datetime's DayOfWeek follows ISO-8601 (MONDAY=0 ... SUNDAY=6) regardless of any
 * user preference - these two helpers translate that fixed ordinal into "how many days after
 * the user's chosen first day of the week is this", which is what both the month grid's
 * leading blank cells and the week view's start date actually need. */
private fun weekdayOffsetFrom(date: LocalDate, firstDay: DayOfWeek): Int {
    val diff = date.dayOfWeek.ordinal - firstDay.ordinal
    return ((diff % 7) + 7) % 7
}

private fun orderedWeekDays(firstDay: DayOfWeek): List<DayOfWeek> {
    val all = DayOfWeek.values()
    val startIdx = all.indexOf(firstDay)
    return (0 until 7).map { all[(startIdx + it) % 7] }
}

private fun getDaysInMonth(year: Int, month: Int): Int {
    return when (month) {
        1, 3, 5, 7, 8, 10, 12 -> 31
        4, 6, 9, 11 -> 30
        2 -> if (year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)) 29 else 28
        else -> 30
    }
}

private fun isSameDay(timestamp: Long, date: LocalDate): Boolean {
    val instant = Instant.fromEpochMilliseconds(timestamp)
    val localDate = instant.toLocalDateTime(TimeZone.currentSystemDefault()).date
    return localDate == date
}

private fun timestampToDate(timestamp: Long): LocalDate {
    return Instant.fromEpochMilliseconds(timestamp).toLocalDateTime(TimeZone.currentSystemDefault()).date
}

private fun timestampToHour(timestamp: Long): Int {
    return Instant.fromEpochMilliseconds(timestamp).toLocalDateTime(TimeZone.currentSystemDefault()).hour
}

private fun isAppointmentOnDay(appointment: Appointment, date: LocalDate): Boolean {
    val start = timestampToDate(appointment.startDate)
    val end = timestampToDate(appointment.endDate)
    
    if (date in start..end) return true
    
    if (date < start) return false
    return when (appointment.recurrence) {
        "Daily" -> true
        "Weekly" -> date.dayOfWeek == start.dayOfWeek
        "Monthly" -> date.dayOfMonth == start.dayOfMonth
        else -> false
    }
}

private fun formatTime(timestamp: Long): String {
    val instant = Instant.fromEpochMilliseconds(timestamp)
    val localDateTime = instant.toLocalDateTime(TimeZone.currentSystemDefault())
    return formatTime(localDateTime.hour, localDateTime.minute)
}

private fun formatTime(hour: Int, minute: Int): String {
    val h = if (hour % 12 == 0) 12 else hour % 12
    val amPm = if (hour < 12) "AM" else "PM"
    return "$h:${minute.toString().padStart(2, '0')} $amPm"
}
