package com.djransom.crewsync.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.djransom.crewsync.data.model.DailyLog
import com.djransom.crewsync.data.model.DailyLogSections
import com.djransom.crewsync.data.model.Project
import com.djransom.crewsync.data.model.ProjectFile
import com.djransom.crewsync.i18n.t
import com.djransom.crewsync.util.*
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.firestore.firestore
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

/**
 * The Daily Log tab: one report per day for the project, written by anyone on the team.
 *
 * Layout: a strip of recent days across the top (today first), then the selected day's report -
 * weather, the five sections as editable bullet lists, free notes, photos - with Save / Photo /
 * PDF / Email / Print along the top. Saving writes the whole day back; the sections you didn't
 * touch keep whatever the latest snapshot had, so two people editing different sections don't
 * wipe each other out as long as they save a moment apart.
 *
 * The printable/emailable report is a PDF laid out server-side (functions/dailyReport.js) so it
 * looks identical from every platform; Print and Email both make sure the PDF is current first.
 */
@Composable
fun DailyLogTab(
    projectId: String,
    project: Project,
    userMap: Map<String, String>,
    currentUserEmail: String
) {
    val firestore = Firebase.firestore
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    val logsFlow = remember(projectId) {
        firestore.collection("projects").document(projectId).collection("dailyLogs")
            .snapshots
            .map { snap -> snap.documents.map { it.toDailyLogSafe() }.sortedByDescending { it.date } }
    }
    val logs by logsFlow.collectAsState(initial = emptyList())

    val today = remember { todayLogDate() }
    var selectedDate by rememberSaveable { mutableStateOf(today) }
    var showDatePicker by remember { mutableStateOf(false) }

    val existing = logs.firstOrNull { it.date == selectedDate }
    // The day as it stands in Firestore, or a blank one if nobody has written to it yet.
    val baseLog = existing ?: DailyLog(id = selectedDate, projectId = projectId, date = selectedDate)

    // Editable copies of the text sections. Re-seeded whenever the selected day changes or the
    // stored log changes underneath us while we have no unsaved edits.
    var sectionText by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var notesText by remember { mutableStateOf("") }
    var weatherText by remember { mutableStateOf("") }
    var dirty by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf<String?>(null) } // "save" | "pdf" | "photo" | null

    fun seedFrom(log: DailyLog) {
        sectionText = DailyLogSections.ordered.associate { (key, _) -> key to DailyLogSections.linesOf(log, key).joinToString("\n") }
        notesText = log.notes
        weatherText = log.weather
        dirty = false
    }
    LaunchedEffect(selectedDate, existing?.updatedAt) { if (!dirty) seedFrom(baseLog) }

    // Weather fills itself in the first time today's log is opened, from the job-site address.
    LaunchedEffect(selectedDate, project.location) {
        if (selectedDate == today && weatherText.isBlank() && existing?.weather.isNullOrBlank() && project.location.isNotBlank()) {
            fetchLocalWeather(project.location)?.let { w ->
                val hl = if (w.highF != null && w.lowF != null) " (H ${w.highF}° / L ${w.lowF}°)" else ""
                if (weatherText.isBlank()) weatherText = "${w.currentTempF}°F, ${w.shortForecast}$hl"
            }
        }
    }

    fun assembled(): DailyLog {
        val now = Clock.System.now().toEpochMilliseconds()
        var log = baseLog
        DailyLogSections.ordered.forEach { (key, _) -> log = DailyLogSections.withLines(log, key, linesFromText(sectionText[key] ?: "")) }
        return log.copy(
            notes = notesText.trim(),
            weather = weatherText.trim(),
            contributors = (baseLog.contributors + currentUserEmail).filter { it.isNotBlank() }.distinct(),
            createdBy = baseLog.createdBy.ifBlank { currentUserEmail },
            createdAt = if (baseLog.createdAt == 0L) now else baseLog.createdAt,
            updatedBy = currentUserEmail,
            updatedAt = now
        )
    }

    suspend fun save(log: DailyLog = assembled()): Boolean {
        return try {
            firestore.collection("projects").document(projectId).collection("dailyLogs").document(selectedDate).set(log.toFirestoreMap())
            dirty = false
            true
        } catch (e: Exception) {
            snackbar.showSnackbar(t("Couldn't save: {0}", e.message ?: ""))
            false
        }
    }

    /** Saves if needed, then builds the PDF (or reuses a current one). Returns the URL or null. */
    suspend fun ensurePdf(): String? {
        val current = if (dirty) { if (!save()) return null; assembled() } else baseLog
        if (current.pdfIsCurrent() && !dirty) return current.pdfUrl
        return when (val r = buildDailyReportPdf(projectId, selectedDate)) {
            is DailyReportResult.Ready -> r.url
            is DailyReportResult.Failed -> { snackbar.showSnackbar(r.message); null }
        }
    }

    fun addPhoto(picked: PickedFile) {
        scope.launch {
            busy = "photo"
            try {
                val path = "projects/$projectId/daily_logs/$selectedDate/${picked.name}"
                val url = uploadFile(path, picked.platformFile)
                val file = ProjectFile(
                    id = "dl_${Clock.System.now().toEpochMilliseconds()}",
                    name = picked.name, url = url,
                    uploadedBy = currentUserEmail,
                    uploadedAt = Clock.System.now().toEpochMilliseconds()
                )
                save(assembled().copy(photos = baseLog.photos + file))
            } catch (e: Exception) {
                snackbar.showSnackbar(t("Upload failed: {0}", e.message ?: ""))
            } finally { busy = null }
        }
    }
    val cameraLauncher = rememberCameraLauncher { addPhoto(it) }
    val fileLauncher = rememberFilePickerLauncher { addPhoto(it) }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // ---- day strip ----------------------------------------------------------------------
            val dayChips = remember(logs, today) { (listOf(today) + logs.map { it.date }).distinct().sortedDescending() }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
                LazyRow(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(dayChips) { d ->
                        val dayLog = logs.firstOrNull { it.date == d }
                        val hasContent = dayLog != null && !dayLog.isEmpty()
                        val weatherOnly = dayLog?.isWeatherOnly() == true
                        FilterChip(
                            selected = d == selectedDate,
                            onClick = { if (!dirty || d == selectedDate) { selectedDate = d } else scope.launch { if (save()) selectedDate = d } },
                            label = { Text(if (d == today) t("Today") else formatLogDate(d)) },
                            leadingIcon = when {
                                hasContent -> ({ Icon(Icons.Default.Check, null, modifier = Modifier.size(16.dp)) })
                                weatherOnly -> ({ Icon(Icons.Default.WbSunny, t("No work logged"), modifier = Modifier.size(16.dp)) })
                                else -> null
                            }
                        )
                    }
                }
                IconButton(onClick = { showDatePicker = true }) { Icon(Icons.Default.DateRange, contentDescription = t("Pick a date")) }
            }

            // ---- header + actions -----------------------------------------------------------------
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(formatLogDate(selectedDate), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    val who = baseLog.contributors.map { userMap[it] ?: it }
                    if (who.isNotEmpty()) Text(t("By {0}", who.joinToString(", ")), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (busy != null) CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
            }
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(onClick = { scope.launch { busy = "save"; save(); busy = null } }, enabled = dirty && busy == null) {
                    Icon(Icons.Default.Check, null); Spacer(Modifier.width(4.dp)); Text(t("Save"))
                }
                OutlinedButton(onClick = { cameraLauncher() }, enabled = busy == null) { Icon(Icons.Default.CameraAlt, contentDescription = t("Photo")) }
                OutlinedButton(onClick = { fileLauncher() }, enabled = busy == null) { Icon(Icons.Default.AttachFile, contentDescription = t("Attach")) }
                Spacer(Modifier.weight(1f))
                OutlinedButton(onClick = { scope.launch { busy = "pdf"; ensurePdf()?.let { openUrl(it) }; busy = null } }, enabled = busy == null) {
                    Icon(Icons.Default.Print, contentDescription = t("Print / PDF"))
                }
                OutlinedButton(onClick = {
                    scope.launch {
                        busy = "pdf"
                        val url = ensurePdf()
                        busy = null
                        val log = assembled().copy(pdfUrl = url ?: baseLog.pdfUrl)
                        sendEmail("", dailyReportSubject(log, project.name), dailyLogPlainText(log, project.name, userMap))
                    }
                }, enabled = busy == null) { Icon(Icons.Default.Email, contentDescription = t("Email")) }
            }

            // ---- the report --------------------------------------------------------------------------
            Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (baseLog.weatherReadings.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.WbSunny, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                        baseLog.weatherReadings.sortedBy { it.slotOrder() }.forEach { r ->
                            AssistChip(onClick = {}, label = { Text("${r.label()}  ${r.summary()}") })
                        }
                    }
                    if (baseLog.isWeatherOnly()) Text(t("No work logged"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                OutlinedTextField(
                    value = weatherText, onValueChange = { weatherText = it; dirty = true },
                    label = { Text(if (baseLog.weatherReadings.isEmpty()) t("Weather") else t("Weather notes")) }, singleLine = true,
                    leadingIcon = { Icon(Icons.Default.WbSunny, null) },
                    modifier = Modifier.fillMaxWidth()
                )
                DailyLogSections.ordered.forEach { (key, heading) ->
                    OutlinedTextField(
                        value = sectionText[key] ?: "",
                        onValueChange = { sectionText = sectionText + (key to it); dirty = true },
                        label = { Text(t(heading)) },
                        placeholder = { Text(t("One item per line")) },
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                OutlinedTextField(
                    value = notesText, onValueChange = { notesText = it; dirty = true },
                    label = { Text(t("Notes")) }, minLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )

                if (baseLog.photos.isNotEmpty()) {
                    Text(t("Photos"), style = MaterialTheme.typography.titleMedium)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(baseLog.photos) { photo ->
                            Card(modifier = Modifier.size(110.dp).clickable { openUrl(photo.url) }) {
                                if (photo.name.lowercase().let { it.endsWith(".jpg") || it.endsWith(".jpeg") || it.endsWith(".png") || it.endsWith(".webp") || it.endsWith(".heic") }) {
                                    AsyncImage(model = photo.url, contentDescription = photo.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)))
                                } else {
                                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Icon(Icons.Default.Description, null)
                                            Text(photo.name, style = MaterialTheme.typography.labelSmall, maxLines = 2)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                if (baseLog.entries.isNotEmpty()) {
                    Text(t("Added by voice / assistant"), style = MaterialTheme.typography.titleMedium)
                    baseLog.entries.sortedByDescending { it.at }.forEach { e ->
                        val who = userMap[e.by] ?: e.by
                        val whenStr = formatDate(e.at)
                        Text("• ${e.text}", style = MaterialTheme.typography.bodyMedium)
                        Text("   $who · $whenStr · ${e.source}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    if (showDatePicker) {
        val initial = parseLogDate(selectedDate)?.let { d -> toPickerMillis(d.atStartOfDayInSystemMillis()) }
        val state = rememberDatePickerState(initialSelectedDateMillis = initial)
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { ms ->
                        val d = Instant.fromEpochMilliseconds(ms).toLocalDateTime(TimeZone.UTC).date.toLogDate()
                        if (!dirty) selectedDate = d else scope.launch { if (save()) selectedDate = d }
                    }
                    showDatePicker = false
                }) { Text(t("OK")) }
            },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text(t("Cancel")) } }
        ) { DatePicker(state = state) }
    }
}

private fun kotlinx.datetime.LocalDate.atStartOfDayInSystemMillis(): Long =
    this.atStartOfDayIn(TimeZone.currentSystemDefault()).toEpochMilliseconds()
