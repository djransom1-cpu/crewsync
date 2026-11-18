package com.djransom.crewsync.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.djransom.crewsync.data.model.DailyLog
import com.djransom.crewsync.data.model.Project
import com.djransom.crewsync.data.model.WeeklyIssue
import com.djransom.crewsync.data.model.WeeklyIssueStatus
import com.djransom.crewsync.data.model.WeeklyPhoto
import com.djransom.crewsync.data.model.WeeklyReport
import com.djransom.crewsync.data.model.WeeklyReportStatus
import com.djransom.crewsync.i18n.t
import com.djransom.crewsync.util.*
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.firestore.FieldValue
import dev.gitlive.firebase.firestore.firestore
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import kotlin.time.Clock

/** How a week's report stands while it loads: still loading, not built yet, or here. */
private sealed class WeeklyState {
    object Loading : WeeklyState()
    object Missing : WeeklyState()
    data class Ready(val report: WeeklyReport) : WeeklyState()
    data class Unreadable(val message: String) : WeeklyState()
}

/** Line-list sections: stored key and heading. */
private val LINE_SECTIONS = listOf(
    "weather" to "Weather",
    "crew" to "Crew & Subs on Site",
    "subsOnSite" to "Subs this week",
    "deliveriesInspections" to "Deliveries & Inspections",
    "nextWeek" to "Next Week",
    "notes" to "Notes"
)

/**
 * The Daily Log tab's Weekly view: pick a week, see its report, edit any section, rebuild it from
 * the daily logs, mark it Final, and print or email it. The report is built on the server (see
 * functions/weeklyReport.js) - by the Saturday 7 AM run or the Build button - and edited here.
 */
@Composable
fun WeeklyReportView(projectId: String, project: Project, currentUserEmail: String, modifier: Modifier = Modifier) {
    val firestore = Firebase.firestore
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var mondayIso by rememberSaveable { mutableStateOf(todayLogDate().let { parseLogDate(it)!!.weekMonday().toLogDate() }) }
    val monday = parseLogDate(mondayIso) ?: parseLogDate(todayLogDate())!!.weekMonday()
    val ref = remember(projectId, mondayIso) { firestore.collection("projects").document(projectId).collection("weeklyReports").document(mondayIso) }
    val state by remember(projectId, mondayIso) {
        ref.snapshots.map { snap ->
            if (!snap.exists) WeeklyState.Missing
            else runCatching { WeeklyState.Ready(snap.data<WeeklyReport>().copy(weekStart = mondayIso)) }.getOrElse { WeeklyState.Unreadable(it.message ?: "") }
        }.catch { emit(WeeklyState.Unreadable(it.message ?: "")) }
    }.collectAsState(initial = WeeklyState.Loading)
    // The week's daily logs: whether there's anything to build from, and the photos to choose from.
    val logs by remember(projectId) {
        firestore.collection("projects").document(projectId).collection("dailyLogs").snapshots
            .map { snap -> snap.documents.map { it.toDailyLogSafe() } }.catch { emit(emptyList()) }
    }.collectAsState(initial = emptyList())
    val weekDays = remember(mondayIso) { (0..6).map { monday.plus(it, DateTimeUnit.DAY).toLogDate() }.toSet() }
    val loggedThisWeek = logs.any { it.date in weekDays && !it.isEmpty() && !it.isWeatherOnly() }

    var busy by remember { mutableStateOf<String?>(null) }   // "build" | "pdf" | null
    var editing by remember { mutableStateOf<String?>(null) }
    var confirmRebuild by remember { mutableStateOf(false) }
    // A write waits a frame so it never runs while the dialog that asked for it is closing (see PlannerScreen).
    var pendingWrite by remember { mutableStateOf<Array<Pair<String, Any?>>?>(null) }
    LaunchedEffect(pendingWrite) {
        val fields = pendingWrite ?: return@LaunchedEffect
        withFrameNanos { }
        try {
            ref.update(*fields, "updatedAt" to Clock.System.now().toEpochMilliseconds().toDouble(), "updatedBy" to currentUserEmail)
        } catch (e: Exception) {
            snackbar.showSnackbar(t("Couldn't save: {0}", e.message ?: ""))
        }
        pendingWrite = null
    }
    /** A person's change to one section: written alone, and the report marked edited so the Saturday run leaves it be. */
    fun saveSection(key: String, value: Any?) {
        val stored = if (value is List<*> && value.isEmpty()) FieldValue.delete else value
        pendingWrite = arrayOf(key to stored, "edited" to true)
    }

    suspend fun build(force: Boolean) {
        busy = "build"
        when (val r = buildWeeklyReport(projectId, mondayIso, force)) {
            is WeeklyReportResult.Built -> if (!r.built) snackbar.showSnackbar(
                if (r.reason == "final") t("Left as it is: this report is marked Final.") else t("Left as it is: this report has been edited.")
            )
            is WeeklyReportResult.Failed -> snackbar.showSnackbar(r.message)
            else -> {}
        }
        busy = null
    }
    suspend fun pdf(): String? {
        busy = "pdf"
        val r = weeklyReportPdf(projectId, mondayIso)
        busy = null
        return when (r) {
            is WeeklyReportResult.Pdf -> r.url
            is WeeklyReportResult.Failed -> { snackbar.showSnackbar(r.message); null }
            else -> null
        }
    }

    Scaffold(modifier = modifier, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // ---- the week ----
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
                IconButton(onClick = { mondayIso = monday.plus(-7, DateTimeUnit.DAY).toLogDate() }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = t("Previous")) }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(t("Week of {0}", weekRangeText(monday)), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    (state as? WeeklyState.Ready)?.report?.let { r ->
                        Text(
                            listOfNotNull(t(r.status), if (r.edited) t("edited") else null).joinToString(" · "),
                            style = MaterialTheme.typography.labelMedium,
                            color = if (r.status == WeeklyReportStatus.FINAL) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                IconButton(onClick = { mondayIso = monday.plus(7, DateTimeUnit.DAY).toLogDate() }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = t("Next")) }
            }

            when (val s = state) {
                WeeklyState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                is WeeklyState.Unreadable -> Text(t("Couldn't read this report: {0}", s.message), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
                WeeklyState.Missing -> Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Default.Assignment, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.outline)
                    Text(t("No weekly report for this week yet."), style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (loggedThisWeek) t("It's put together from the week's daily logs. A draft is also made every Saturday at 7 AM.") else t("Nothing has been logged this week."),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(onClick = { scope.launch { build(force = false) } }, enabled = busy == null) {
                        Icon(Icons.Default.Refresh, contentDescription = null); Spacer(Modifier.width(6.dp)); Text(t("Build from the daily logs"))
                    }
                    if (busy != null) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                }
                is WeeklyState.Ready -> {
                    val report = s.report
                    // ---- actions ----
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(onClick = {
                            if (report.edited || report.status == WeeklyReportStatus.FINAL) confirmRebuild = true else scope.launch { build(force = false) }
                        }, enabled = busy == null) { Icon(Icons.Default.Refresh, contentDescription = t("Rebuild from the daily logs")) }
                        if (report.status == WeeklyReportStatus.FINAL) {
                            OutlinedButton(onClick = { pendingWrite = arrayOf("status" to WeeklyReportStatus.DRAFT) }, enabled = busy == null) { Text(t("Back to Draft")) }
                        } else {
                            Button(onClick = { pendingWrite = arrayOf("status" to WeeklyReportStatus.FINAL) }, enabled = busy == null) {
                                Icon(Icons.Default.Check, null); Spacer(Modifier.width(4.dp)); Text(t("Mark Final"))
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        if (busy != null) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                        OutlinedButton(onClick = { scope.launch { pdf()?.let { openUrl(it) } } }, enabled = busy == null) {
                            Icon(Icons.Default.Print, contentDescription = t("Print / PDF"))
                        }
                        OutlinedButton(onClick = {
                            scope.launch {
                                val url = pdf()
                                sendEmail("", weeklyReportSubject(project.name, monday), weeklyReportPlainText(report.copy(pdfUrl = url ?: report.pdfUrl), project.name))
                            }
                        }, enabled = busy == null) { Icon(Icons.Default.Email, contentDescription = t("Email")) }
                    }
                    WeeklyReportBody(report, onEdit = { editing = it }, modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp))

                    editing?.let { key ->
                        WeeklySectionEditor(
                            key = key, report = report, photoChoices = weekPhotoChoices(logs, monday), crewName = report.workCompleted.firstOrNull()?.who ?: "NACE",
                            onSave = { value -> editing = null; saveSection(key, value) },
                            onDismiss = { editing = null }
                        )
                    }
                    if (confirmRebuild) {
                        AlertDialog(
                            onDismissRequest = { confirmRebuild = false },
                            title = { Text(t("Rebuild from the daily logs?")) },
                            text = { Text(t("This replaces the changes made to this report with what's in the daily logs now, and sets it back to Draft.")) },
                            confirmButton = { Button(onClick = { confirmRebuild = false; scope.launch { build(force = true) } }) { Text(t("Rebuild")) } },
                            dismissButton = { TextButton(onClick = { confirmRebuild = false }) { Text(t("Cancel")) } }
                        )
                    }
                }
            }
        }
    }
}

/**
 * The report itself, section by section. With [onEdit], each section has a pencil that asks to
 * edit it (by its stored key); without, it's read-only.
 */
@Composable
fun WeeklyReportBody(report: WeeklyReport, onEdit: ((String) -> Unit)?, modifier: Modifier = Modifier) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        WeeklySection(t("Summary"), "summary", onEdit) {
            Text(report.summary.ifBlank { t("None") }, style = MaterialTheme.typography.bodyLarge)
            if (report.daysLogged.isNotEmpty()) Text(t("Days logged: {0}", report.daysLogged.joinToString(", ") { shortLogDay(it) }), style = MaterialTheme.typography.bodySmall, color = muted)
        }
        WeeklySection(t("Weather"), "weather", onEdit) {
            Lines(report.weather)
            if (report.precipDays > 0) Text(t("Rain or snow on {0} day(s)", report.precipDays), style = MaterialTheme.typography.bodySmall, color = muted)
        }
        WeeklySection(t("Crew & Subs on Site"), "crew", onEdit) { Lines(report.crew) }
        WeeklySection(t("Subs this week"), "subsOnSite", onEdit) { Lines(report.subsOnSite) }
        WeeklySection(t("Work Completed"), "workCompleted", onEdit) {
            if (report.workCompleted.isEmpty()) Lines(emptyList())
            report.workCompleted.forEach { g ->
                Text(g.who, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
                Lines(g.lines)
            }
        }
        WeeklySection(t("Deliveries & Inspections"), "deliveriesInspections", onEdit) { Lines(report.deliveriesInspections) }
        WeeklySection(t("Issues"), "issues", onEdit) {
            if (report.issues.isEmpty()) Lines(emptyList())
            report.issues.forEach { issue ->
                Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(vertical = 2.dp)) {
                    val open = issue.status == WeeklyIssueStatus.OPEN
                    Text(
                        if (open) t("OPEN") else t("Cleared"),
                        style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold,
                        color = if (open) MaterialTheme.colorScheme.error else Color(0xFF2E7D32),
                        modifier = Modifier.width(64.dp).padding(top = 2.dp)
                    )
                    Column(Modifier.weight(1f)) {
                        Text(issue.text, style = MaterialTheme.typography.bodyMedium)
                        if (issue.firstSeen.isNotBlank()) Text(t("since {0}", shortLogDay(issue.firstSeen)), style = MaterialTheme.typography.bodySmall, color = muted)
                    }
                }
            }
        }
        WeeklySection(t("Next Week"), "nextWeek", onEdit) { Lines(report.nextWeek) }
        WeeklySection(t("Photos"), "photos", onEdit) {
            if (report.photos.isEmpty()) Lines(emptyList())
            else LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(report.photos, key = { it.url }) { p -> PhotoThumb(p, selected = null, onClick = { openUrl(p.url) }) }
            }
        }
        WeeklySection(t("Notes"), "notes", onEdit) { Lines(report.notes) }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun WeeklySection(title: String, key: String, onEdit: ((String) -> Unit)?, content: @Composable ColumnScope.() -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 12.dp, end = 4.dp, bottom = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).padding(top = 10.dp))
                if (onEdit != null) IconButton(onClick = { onEdit(key) }) { Icon(Icons.Default.Edit, contentDescription = t("Edit")) }
            }
            Column(Modifier.padding(end = 8.dp), content = content)
        }
    }
}

@Composable
private fun Lines(lines: List<String>) {
    if (lines.isEmpty()) Text(t("None"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    lines.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 1.dp)) }
}

@Composable
private fun PhotoThumb(photo: WeeklyPhoto, selected: Boolean?, onClick: () -> Unit) {
    Box(
        Modifier.size(104.dp).clip(RoundedCornerShape(8.dp))
            .then(if (selected == true) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp)) else Modifier)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
    ) {
        AsyncImage(model = photo.url, contentDescription = photo.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        Text(
            shortLogDay(photo.date), style = MaterialTheme.typography.labelSmall, color = Color.White,
            modifier = Modifier.align(Alignment.BottomStart).background(Color.Black.copy(alpha = 0.5f)).padding(horizontal = 4.dp, vertical = 1.dp)
        )
        if (selected == true) Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.align(Alignment.TopEnd).padding(4.dp))
    }
}

/**
 * Editing one section. Text and line lists are edited as text, one item per line; Work Completed
 * as "Who:" blocks; issues as rows with an Open tick; photos picked from the week's.
 * [onSave] gets the value in the form it's stored in.
 */
@Composable
private fun WeeklySectionEditor(
    key: String,
    report: WeeklyReport,
    photoChoices: List<WeeklyPhoto>,
    crewName: String,
    onSave: (Any?) -> Unit,
    onDismiss: () -> Unit
) {
    val title = when (key) {
        "summary" -> t("Summary"); "workCompleted" -> t("Work Completed"); "issues" -> t("Issues"); "photos" -> t("Photos")
        else -> t(LINE_SECTIONS.firstOrNull { it.first == key }?.second ?: key)
    }
    var text by remember(key) {
        mutableStateOf(
            when (key) {
                "summary" -> report.summary
                "workCompleted" -> workGroupsToText(report.workCompleted)
                "weather" -> report.weather.joinToString("\n")
                "crew" -> report.crew.joinToString("\n")
                "subsOnSite" -> report.subsOnSite.joinToString("\n")
                "deliveriesInspections" -> report.deliveriesInspections.joinToString("\n")
                "nextWeek" -> report.nextWeek.joinToString("\n")
                "notes" -> report.notes.joinToString("\n")
                else -> ""
            }
        )
    }
    var issues by remember(key) { mutableStateOf(report.issues) }
    var photos by remember(key) { mutableStateOf(report.photos) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (key) {
                    "issues" -> {
                        issues.forEachIndexed { i, issue ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = issue.status == WeeklyIssueStatus.OPEN,
                                    onCheckedChange = { open -> issues = issues.toMutableList().also { it[i] = issue.copy(status = if (open) WeeklyIssueStatus.OPEN else WeeklyIssueStatus.CLEARED) } }
                                )
                                OutlinedTextField(
                                    value = issue.text, onValueChange = { v -> issues = issues.toMutableList().also { it[i] = issue.copy(text = v) } },
                                    modifier = Modifier.weight(1f), label = { Text(if (issue.status == WeeklyIssueStatus.OPEN) t("Open") else t("Cleared")) }
                                )
                                IconButton(onClick = { issues = issues.filterIndexed { j, _ -> j != i } }) { Icon(Icons.Default.Close, contentDescription = t("Remove")) }
                            }
                        }
                        TextButton(onClick = { issues = issues + WeeklyIssue(text = "", status = WeeklyIssueStatus.OPEN) }) {
                            Icon(Icons.Default.Add, null); Spacer(Modifier.width(4.dp)); Text(t("Add issue"))
                        }
                    }
                    "photos" -> {
                        Text(t("Pick up to {0} photos from this week's logs.", 6), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (photoChoices.isEmpty()) Text(t("No photos in this week's logs."))
                        photoChoices.chunked(3).forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                row.forEach { p ->
                                    val chosen = photos.any { it.url == p.url }
                                    PhotoThumb(p, selected = chosen, onClick = {
                                        photos = if (chosen) photos.filterNot { it.url == p.url } else if (photos.size < 6) photos + p else photos
                                    })
                                }
                            }
                        }
                    }
                    else -> {
                        if (key == "workCompleted") Text(t("Start each group with a line ending in a colon, such as \"{0}:\", then one item per line.", crewName), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedTextField(
                            value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth(), minLines = if (key == "summary") 3 else 6,
                            placeholder = { if (key != "summary") Text(t("One item per line")) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                onSave(
                    when (key) {
                        "summary" -> text.trim()
                        "workCompleted" -> workGroupsFromText(text, crewName).map { mapOf("who" to it.who, "lines" to it.lines) }
                        "issues" -> issues.filter { it.text.isNotBlank() }.map { mapOf("text" to it.text.trim(), "firstSeen" to it.firstSeen, "status" to it.status) }
                        "photos" -> photos.map { mapOf("date" to it.date, "name" to it.name, "url" to it.url) }
                        else -> linesFromText(text)
                    }
                )
            }) { Text(t("Save")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(t("Cancel")) } }
    )
}
