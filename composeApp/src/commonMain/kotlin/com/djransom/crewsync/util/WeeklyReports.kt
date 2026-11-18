package com.djransom.crewsync.util

import com.djransom.crewsync.data.model.DailyLog
import com.djransom.crewsync.data.model.WeeklyIssueStatus
import com.djransom.crewsync.data.model.WeeklyPhoto
import com.djransom.crewsync.data.model.WeeklyReport
import com.djransom.crewsync.data.model.WeeklyReportStatus
import com.djransom.crewsync.data.model.WeeklyWorkGroup
import com.djransom.crewsync.i18n.dayShort
import com.djransom.crewsync.i18n.monthDay
import com.djransom.crewsync.i18n.t
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.auth.auth
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.plus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

// ---- The weekly report: talking to the server, and wording it ------------------------------------

/** Our own Cloud Function that builds a week's report and its PDF - see weeklyReport in functions/weeklyReport.js. */
private const val WEEKLY_REPORT_URL =
    "https://us-central1-gen-lang-client-0438127279.cloudfunctions.net/weeklyReport"

private val weeklyReportHttp = HttpClient()

sealed class WeeklyReportResult {
    /** Built from the logs ([built] true), or left as it was because it's Final or edited ([reason]). */
    data class Built(val built: Boolean, val reason: String?) : WeeklyReportResult()
    data class Pdf(val url: String) : WeeklyReportResult()
    data class Failed(val message: String) : WeeklyReportResult()
}

/**
 * Asks the server to build the report for the week starting [weekStart] from that week's daily
 * logs. A report that's been edited or marked Final is left alone unless [force].
 */
suspend fun buildWeeklyReport(projectId: String, weekStart: String, force: Boolean): WeeklyReportResult =
    callWeeklyReport(projectId, weekStart, "build", force)

/** Builds (or reuses, while current) the week's PDF and returns its link. It's also filed under Files, in "Weekly Reports". */
suspend fun weeklyReportPdf(projectId: String, weekStart: String): WeeklyReportResult =
    callWeeklyReport(projectId, weekStart, "pdf", false)

private suspend fun callWeeklyReport(projectId: String, weekStart: String, action: String, force: Boolean): WeeklyReportResult {
    return try {
        val token = Firebase.auth.currentUser?.getIdToken(false) ?: return WeeklyReportResult.Failed(t("You're not signed in."))
        val response = weeklyReportHttp.post(WEEKLY_REPORT_URL) {
            header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("projectId", projectId); put("weekStart", weekStart); put("action", action); put("force", force) }.toString())
        }
        val json = runCatching { Json.parseToJsonElement(response.bodyAsText()).jsonObject }.getOrNull()
        if (response.status.value !in 200..299) {
            return WeeklyReportResult.Failed(json?.get("error")?.jsonPrimitive?.content ?: t("Couldn't reach the weekly report (error {0}).", response.status.value))
        }
        if (action == "pdf") {
            val url = json?.get("url")?.jsonPrimitive?.content
            if (url.isNullOrBlank()) WeeklyReportResult.Failed(t("The PDF was built but no link came back.")) else WeeklyReportResult.Pdf(url)
        } else {
            WeeklyReportResult.Built(
                built = json?.get("built")?.jsonPrimitive?.booleanOrNull ?: false,
                reason = json?.get("reason")?.jsonPrimitive?.content?.takeIf { it != "null" }
            )
        }
    } catch (e: Exception) {
        WeeklyReportResult.Failed(t("Couldn't reach Crewsync. Check your connection and try again. ({0})", e.message ?: ""))
    }
}

/** The Monday of the week [date] falls in. Weekly reports run Monday to Sunday. */
fun LocalDate.weekMonday(): LocalDate = plus(-(dayOfWeek.isoDayNumber - 1), DateTimeUnit.DAY)

/** "Nov 9 - 15, 2026" or "Nov 30 - Dec 6, 2026", in the chosen language's date order. */
fun weekRangeText(monday: LocalDate): String {
    val sunday = monday.plus(6, DateTimeUnit.DAY)
    val end = if (sunday.monthNumber == monday.monthNumber) sunday.dayOfMonth.toString() else monthDay(sunday.monthNumber, sunday.dayOfMonth)
    return "${monthDay(monday.monthNumber, monday.dayOfMonth)} - $end, ${sunday.year}"
}

/** "Mon Nov 9" for a stored "YYYY-MM-DD", or the text itself if it isn't a date. */
fun shortLogDay(date: String): String = parseLogDate(date)?.let { "${dayShort(it.dayOfWeek.isoDayNumber)} ${monthDay(it.monthNumber, it.dayOfMonth)}" } ?: date

/** The photos from the week's daily logs, oldest first - what a report's photos can be chosen from. */
fun weekPhotoChoices(logs: List<DailyLog>, monday: LocalDate): List<WeeklyPhoto> {
    val days = (0..6).map { monday.plus(it, DateTimeUnit.DAY).toLogDate() }.toSet()
    return logs.filter { it.date in days }.sortedBy { it.date }
        .flatMap { log -> log.photos.filter { it.url.isNotBlank() }.map { WeeklyPhoto(log.date, it.name, it.url) } }
}

/**
 * Work-completed groups as editable text, one group per block: a "Who:" line, then its lines.
 * [workGroupsFromText] reads it back.
 */
fun workGroupsToText(groups: List<WeeklyWorkGroup>): String =
    groups.joinToString("\n\n") { g -> (listOf("${g.who}:") + g.lines).joinToString("\n") }

/** Reads [workGroupsToText]'s form back: a line ending with ":" starts a group; lines before any group go under [defaultWho]. */
fun workGroupsFromText(text: String, defaultWho: String): List<WeeklyWorkGroup> {
    val groups = mutableListOf<WeeklyWorkGroup>()
    var who = defaultWho
    var lines = mutableListOf<String>()
    fun flush() { if (lines.isNotEmpty()) groups += WeeklyWorkGroup(who, lines.toList()); lines = mutableListOf() }
    for (raw in text.lines()) {
        val line = raw.trim().trimStart('-', '*', '•').trim()
        if (line.isEmpty()) continue
        if (line.endsWith(":") && line.length > 1 && !raw.trimStart().startsWith("-")) { flush(); who = line.dropLast(1).trim() } else lines += line
    }
    flush()
    // The same who in two blocks becomes one group.
    return groups.groupBy { it.who.lowercase() }.values.map { same -> WeeklyWorkGroup(same.first().who, same.flatMap { it.lines }) }
}

/** The report as plain text, for the body of an email (mailto can't carry an attachment everywhere, so it has to stand on its own). */
fun weeklyReportPlainText(report: WeeklyReport, projectName: String): String {
    val monday = parseLogDate(report.weekStart)
    val sb = StringBuilder()
    sb.appendLine(t("Weekly Report") + " - " + projectName)
    sb.appendLine(t("Week of {0}", monday?.let { weekRangeText(it) } ?: report.weekStart) + if (report.status == WeeklyReportStatus.DRAFT) " (" + t("Draft") + ")" else "")
    sb.appendLine()
    if (report.summary.isNotBlank()) { sb.appendLine(report.summary.trim()); sb.appendLine() }
    fun block(title: String, lines: List<String>) {
        sb.appendLine(t(title).uppercase())
        if (lines.isEmpty()) sb.appendLine("  " + t("None")) else lines.forEach { sb.appendLine("  - $it") }
        sb.appendLine()
    }
    block("Weather", report.weather + if (report.precipDays > 0) listOf(t("Rain or snow on {0} day(s)", report.precipDays)) else emptyList())
    block("Crew & Subs on Site", report.crew + if (report.subsOnSite.isNotEmpty()) listOf(t("Subs this week: {0}", report.subsOnSite.joinToString(", "))) else emptyList())
    sb.appendLine(t("Work Completed").uppercase())
    if (report.workCompleted.isEmpty()) sb.appendLine("  " + t("None"))
    report.workCompleted.forEach { g -> sb.appendLine("  ${g.who}"); g.lines.forEach { sb.appendLine("    - $it") } }
    sb.appendLine()
    block("Deliveries & Inspections", report.deliveriesInspections)
    block("Issues", report.issues.map { (if (it.status == WeeklyIssueStatus.OPEN) t("OPEN") else t("Cleared")) + ": " + it.text + if (it.firstSeen.isNotBlank()) " (" + t("since {0}", shortLogDay(it.firstSeen)) + ")" else "" })
    block("Next Week", report.nextWeek)
    if (report.notes.isNotEmpty()) block("Notes", report.notes)
    if (report.pdfUrl.isNotBlank()) sb.appendLine(t("PDF") + ": " + report.pdfUrl)
    return sb.toString().trimEnd()
}

fun weeklyReportSubject(projectName: String, monday: LocalDate): String =
    t("Weekly Report") + " - " + projectName + " - " + t("week of {0}", weekRangeText(monday))
