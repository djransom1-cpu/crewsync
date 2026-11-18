package com.djransom.crewsync.data.model

import kotlinx.serialization.Serializable

/**
 * One day's report for a project - the superintendent's daily log. Exactly one per project per
 * day: the document id IS the date ("2026-11-17"), so two people (or a person and Sierra) adding
 * to the same day land on the same record instead of making duplicates.
 *
 * Every section is a list of lines (one bullet each) rather than a paragraph, so a dictated
 * addition can be appended without rewriting what's already there, and the printed report reads
 * as a clean bulleted page. [entries] keeps the raw additions as they came in, with who and when,
 * so nothing anyone said is ever lost when the sections get tidied.
 *
 * Stored at projects/{projectId}/dailyLogs/{date}. The PDF built from it (see dailyReportPdf in
 * functions/dailyReport.js) is cached in [pdfUrl] and also listed in the project's Files tab.
 */
@Serializable
data class DailyLog(
    val id: String = "",            // same as date
    val projectId: String = "",
    val date: String = "",          // YYYY-MM-DD, in the job site's local time
    val weather: String = "",       // free text: what the numbers don't say ("rained out at 1:30")
    val weatherReadings: List<WeatherReading> = emptyList(), // recorded automatically at 7 AM, noon and 4 PM site time
    val crewOnSite: List<String> = emptyList(),
    val workCompleted: List<String> = emptyList(),
    val deliveriesInspections: List<String> = emptyList(),
    val issuesDelays: List<String> = emptyList(),
    val tomorrowPlan: List<String> = emptyList(),
    val notes: String = "",
    val photos: List<ProjectFile> = emptyList(),
    val entries: List<DailyLogEntry> = emptyList(),
    val contributors: List<String> = emptyList(), // emails of everyone who has written to this day
    val createdBy: String = "",
    val createdAt: Long = 0L,
    val updatedBy: String = "",
    val updatedAt: Long = 0L,
    val pdfUrl: String = "",
    val pdfBuiltAt: Long = 0L
) {
    /** Nothing but automatic weather - a rain day or a weekend. Shown as "no work logged". */
    fun isWeatherOnly(): Boolean = isEmpty() && weatherReadings.isNotEmpty()

    fun weatherLine(): String {
        val auto = weatherReadings.sortedBy { it.slotOrder() }.joinToString("  ·  ") { "${it.label()} ${it.summary()}" }
        return listOf(auto, weather.trim()).filter { it.isNotBlank() }.joinToString("  —  ")
    }

    fun isEmpty(): Boolean =
        crewOnSite.isEmpty() && workCompleted.isEmpty() && deliveriesInspections.isEmpty() &&
            issuesDelays.isEmpty() && tomorrowPlan.isEmpty() && notes.isBlank() && photos.isEmpty()

    /** The PDF is stale when the log changed after it was built. */
    fun pdfIsCurrent(): Boolean = pdfUrl.isNotBlank() && pdfBuiltAt >= updatedAt
}

/**
 * One automatic weather observation for the day. The recordSiteWeather Cloud Function writes
 * three a day (morning / midday / afternoon) from the nearest NWS station, so a rain delay is on
 * the record at the time it happened, whether or not anyone wrote a log that day.
 */
@Serializable
data class WeatherReading(
    val slot: String = "",      // "morning", "midday", "afternoon"
    val at: Long = 0L,          // when it was recorded
    val tempF: Int? = null,
    val conditions: String = "",// "Light Rain", "Overcast"
    val windMph: Int? = null,
    val precip: Boolean = false
) {
    fun slotOrder(): Int = when (slot) { "morning" -> 0; "midday" -> 1; "afternoon" -> 2; else -> 9 }
    fun label(): String = when (slot) { "morning" -> "7 AM"; "midday" -> "Noon"; "afternoon" -> "4 PM"; else -> slot }
    fun summary(): String = buildString {
        if (tempF != null) append("${tempF}°")
        if (conditions.isNotBlank()) { if (isNotEmpty()) append(" "); append(conditions.lowercase()) }
        if (windMph != null && windMph >= 10) append(", $windMph mph wind")
    }
}

/** A raw addition to the day, kept verbatim: who said it, when, and the text as it arrived. */
@Serializable
data class DailyLogEntry(
    val by: String = "",       // email
    val at: Long = 0L,
    val text: String = "",
    val source: String = "app" // "app", "sierra", "claude"
)

/** The sections of a daily log, in print order, with their on-screen/printed headings. */
object DailyLogSections {
    const val CREW = "crewOnSite"
    const val WORK = "workCompleted"
    const val DELIVERIES = "deliveriesInspections"
    const val ISSUES = "issuesDelays"
    const val TOMORROW = "tomorrowPlan"

    /** (field key, English heading) - headings go through t() where shown. */
    val ordered: List<Pair<String, String>> = listOf(
        CREW to "Crew & Subs on Site",
        WORK to "Work Completed",
        DELIVERIES to "Deliveries & Inspections",
        ISSUES to "Issues & Delays",
        TOMORROW to "Plan for Tomorrow",
    )

    fun linesOf(log: DailyLog, key: String): List<String> = when (key) {
        CREW -> log.crewOnSite
        WORK -> log.workCompleted
        DELIVERIES -> log.deliveriesInspections
        ISSUES -> log.issuesDelays
        TOMORROW -> log.tomorrowPlan
        else -> emptyList()
    }

    fun withLines(log: DailyLog, key: String, lines: List<String>): DailyLog = when (key) {
        CREW -> log.copy(crewOnSite = lines)
        WORK -> log.copy(workCompleted = lines)
        DELIVERIES -> log.copy(deliveriesInspections = lines)
        ISSUES -> log.copy(issuesDelays = lines)
        TOMORROW -> log.copy(tomorrowPlan = lines)
        else -> log
    }
}
