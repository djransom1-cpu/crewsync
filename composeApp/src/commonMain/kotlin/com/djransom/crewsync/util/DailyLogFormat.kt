package com.djransom.crewsync.util

import com.djransom.crewsync.data.model.DailyLog
import com.djransom.crewsync.data.model.DailyLogSections
import com.djransom.crewsync.i18n.numericDate
import com.djransom.crewsync.i18n.t
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

/** Today's date at the device's location, as the "YYYY-MM-DD" key a daily log is stored under. */
fun todayLogDate(): String =
    Instant.fromEpochMilliseconds(Clock.System.now().toEpochMilliseconds())
        .toLocalDateTime(TimeZone.currentSystemDefault()).date.toLogDate()

fun LocalDate.toLogDate(): String =
    "$year-${monthNumber.toString().padStart(2, '0')}-${dayOfMonth.toString().padStart(2, '0')}"

fun parseLogDate(s: String): LocalDate? = try { LocalDate.parse(s) } catch (_: Exception) { null }

/** "Tue 11/17/2026" in the chosen language's date order. */
fun formatLogDate(date: String, withWeekday: Boolean = true): String {
    val d = parseLogDate(date) ?: return date
    val numeric = numericDate(d.monthNumber, d.dayOfMonth, d.year)
    if (!withWeekday) return numeric
    val wd = when (d.dayOfWeek) {
        DayOfWeek.MONDAY -> t("Mon"); DayOfWeek.TUESDAY -> t("Tue"); DayOfWeek.WEDNESDAY -> t("Wed")
        DayOfWeek.THURSDAY -> t("Thu"); DayOfWeek.FRIDAY -> t("Fri"); DayOfWeek.SATURDAY -> t("Sat")
        else -> t("Sun")
    }
    return "$wd $numeric"
}

/** Splits a section's text box back into one line per bullet, dropping blanks and any leading bullet marks. */
fun linesFromText(text: String): List<String> =
    text.lines().map { it.trim().trimStart('-', '*', '•').trim() }.filter { it.isNotEmpty() }

/**
 * The report as plain text - what goes into an email body (mailto can't carry attachments on
 * every platform, so the body has to stand on its own) and what the connector hands back to
 * Claude. [userMap] resolves emails to names for the "prepared by" line.
 */
fun dailyLogPlainText(log: DailyLog, projectName: String, userMap: Map<String, String> = emptyMap()): String {
    val sb = StringBuilder()
    sb.appendLine(t("Daily Report") + " - " + projectName)
    sb.appendLine(formatLogDate(log.date))
    log.weatherLine().takeIf { it.isNotBlank() }?.let { sb.appendLine(t("Weather") + ": " + it) }
    sb.appendLine()
    DailyLogSections.ordered.forEach { (key, heading) ->
        val lines = DailyLogSections.linesOf(log, key)
        sb.appendLine(t(heading).uppercase())
        if (lines.isEmpty()) sb.appendLine("  " + t("None")) else lines.forEach { sb.appendLine("  - $it") }
        sb.appendLine()
    }
    if (log.notes.isNotBlank()) {
        sb.appendLine(t("Notes").uppercase())
        sb.appendLine("  " + log.notes.trim().replace("\n", "\n  "))
        sb.appendLine()
    }
    if (log.photos.isNotEmpty()) sb.appendLine(t("{0} photo(s) attached", log.photos.size)).also { sb.appendLine() }
    val by = log.contributors.ifEmpty { listOfNotNull(log.createdBy.ifBlank { null }) }
    if (by.isNotEmpty()) sb.appendLine(t("Prepared by") + ": " + by.joinToString(", ") { userMap[it] ?: it })
    if (log.pdfUrl.isNotBlank()) { sb.appendLine(); sb.appendLine(t("PDF") + ": " + log.pdfUrl) }
    return sb.toString().trimEnd()
}

fun dailyReportSubject(log: DailyLog, projectName: String): String =
    t("Daily Report") + " - " + projectName + " - " + formatLogDate(log.date, withWeekday = false)
