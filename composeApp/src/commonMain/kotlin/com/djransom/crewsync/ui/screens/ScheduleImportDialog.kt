package com.djransom.crewsync.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.djransom.crewsync.data.model.SchedulePhase
import com.djransom.crewsync.i18n.I18n
import com.djransom.crewsync.i18n.t
import com.djransom.crewsync.util.CsvFileProblem
import com.djransom.crewsync.util.CsvPhaseRow
import com.djransom.crewsync.util.CsvRowProblem
import com.djransom.crewsync.util.CsvSchedule
import com.djransom.crewsync.util.WorkCalendar
import com.djransom.crewsync.util.formatPhaseDates
import com.djransom.crewsync.util.parseScheduleCsv
import com.djransom.crewsync.util.readPickedFileText
import com.djransom.crewsync.util.rememberFilePickerLauncher
import kotlinx.coroutines.launch

/**
 * The Schedule tab's Import: pick a CSV saved from a spreadsheet, see every row as it will be
 * read - including the ones that can't be imported and why - choose whether these dates are also
 * the baseline, then import. Nothing is saved until the Import button. See ScheduleCsv.kt for how
 * the file is read.
 */
@Composable
fun ScheduleImportDialog(
    existingPhases: List<SchedulePhase>,
    calendar: WorkCalendar,
    actions: ScheduleActions,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var fileName by remember { mutableStateOf("") }
    var csv by remember { mutableStateOf<CsvSchedule?>(null) }
    var readError by remember { mutableStateOf<String?>(null) }
    // The first schedule loaded is usually the plan itself, so the baseline is on when there isn't one yet.
    var asBaseline by remember { mutableStateOf(existingPhases.none { it.baselineStart != null || it.baselineEnd != null }) }
    val pick = rememberFilePickerLauncher { picked ->
        scope.launch {
            try {
                val text = readPickedFileText(picked.platformFile)
                fileName = picked.name
                readError = null
                // Numeric dates with nothing over 12 in them are read the way the app's language writes dates.
                csv = parseScheduleCsv(text, existingPhases.map { it.name }, calendar, dayFirstByDefault = I18n.current != "en")
            } catch (e: Exception) {
                readError = e.message ?: e.toString()
            }
        }
    }
    val loaded = csv
    val count = loaded?.importable?.size ?: 0

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t("Import a schedule"), fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (loaded == null) {
                    Text(t("Pick a CSV file with a Name, a Start and a Finish column, and optionally Group. In Excel or Google Sheets, save or download the sheet as CSV first."))
                    Text(t("Rows go at the bottom of the schedule in the order they're in. Phases already on the schedule are skipped, so the same file can be imported again."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = { pick() }) {
                        Icon(Icons.Default.FileOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(t("Choose CSV file"))
                    }
                } else {
                    ScheduleImportPreview(loaded, fileName, asBaseline, onAsBaseline = { asBaseline = it })
                }
                readError?.let { Text(t("Couldn't read the file: {0}", it), color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(
                onClick = { if (loaded != null) actions.importPhases(loaded.importable, asBaseline); onDismiss() },
                enabled = count > 0
            ) { Text(t("Import {0} phase(s)", count)) }
        },
        dismissButton = {
            Row {
                if (loaded != null) TextButton(onClick = { pick() }) { Text(t("Other file")) }
                TextButton(onClick = onDismiss) { Text(t("Cancel")) }
            }
        }
    )
}

/**
 * The preview of a read file: a summary, the baseline switch, then each row - what it will
 * become, or why it's left out, with its line number so it can be found in the spreadsheet.
 */
@Composable
fun ScheduleImportPreview(csv: CsvSchedule, fileName: String, asBaseline: Boolean, onAsBaseline: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (fileName.isNotEmpty()) Text(fileName, style = MaterialTheme.typography.labelLarge, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        csv.fileProblem?.let { problem ->
            Text(
                when (problem) {
                    CsvFileProblem.EMPTY -> t("The file is empty.")
                    CsvFileProblem.NO_NAME_COLUMN -> t("There's no Name column. The first line of the file needs column headings, one of them Name.")
                    CsvFileProblem.NO_DATE_COLUMNS -> t("The file needs both a Start and a Finish column.")
                },
                color = MaterialTheme.colorScheme.error
            )
            return@Column
        }
        val skipped = csv.rows.count { it.alreadyOnSchedule }
        val problems = csv.rows.count { it.problem != null }
        Text(t("{0} phase(s) to import", csv.importable.size), style = MaterialTheme.typography.titleSmall)
        if (skipped > 0) Text(t("{0} already on the schedule, skipped", skipped), style = MaterialTheme.typography.bodySmall, color = muted)
        if (problems > 0) Text(t("{0} row(s) with problems are left out", problems), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        csv.dayFirst?.let { dayFirst ->
            Text(
                if (dayFirst) t("Dates like 3/4/2027 are read day first (3 April).") else t("Dates like 3/4/2027 are read month first (March 4)."),
                style = MaterialTheme.typography.bodySmall, color = muted
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { onAsBaseline(!asBaseline) }) {
            Column(Modifier.weight(1f)) {
                Text(t("Also set these dates as the baseline"))
                Text(t("The baseline is the plan that slip is measured against."), style = MaterialTheme.typography.bodySmall, color = muted)
            }
            Switch(checked = asBaseline, onCheckedChange = onAsBaseline)
        }
        HorizontalDivider()
        csv.rows.forEach { row -> ImportRow(row) }
    }
}

@Composable
private fun ImportRow(row: CsvPhaseRow) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val leftOut = !row.importable
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                row.name.ifBlank { t("(no name)") },
                style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
                color = if (leftOut) muted else Color.Unspecified, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
            )
            Text(t("Line {0}", row.line), style = MaterialTheme.typography.labelSmall, color = muted)
        }
        val detail = when {
            row.problem != null -> importProblemText(row)
            row.alreadyOnSchedule -> t("Already on the schedule")
            else -> listOfNotNull(
                row.group.takeIf { it.isNotBlank() },
                formatPhaseDates(row.start, row.end),
                if (row.isMilestone) t("Milestone") else if (row.durationDays == 1) t("1 working day") else t("{0} working days", row.durationDays)
            ).joinToString("  ·  ")
        }
        Text(detail, style = MaterialTheme.typography.bodySmall, color = if (row.problem != null) MaterialTheme.colorScheme.error else muted)
    }
}

private fun importProblemText(row: CsvPhaseRow): String = when (row.problem) {
    CsvRowProblem.NO_NAME -> t("No name")
    CsvRowProblem.NO_START -> t("No start date")
    CsvRowProblem.NO_FINISH -> t("No finish date")
    CsvRowProblem.BAD_START -> t("Can't read the start date \"{0}\"", row.problemDetail)
    CsvRowProblem.BAD_FINISH -> t("Can't read the finish date \"{0}\"", row.problemDetail)
    CsvRowProblem.FINISH_BEFORE_START -> t("The finish is before the start")
    CsvRowProblem.REPEATED_NAME -> t("The same name is on an earlier line")
    null -> ""
}
