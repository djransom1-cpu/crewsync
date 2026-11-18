package com.djransom.crewsync.data.model

import kotlinx.serialization.Serializable

/**
 * A week's report for a project, built from that week's daily logs. Stored at
 * projects/{projectId}/weeklyReports/{weekStart}, weekStart being the Monday ("YYYY-MM-DD");
 * the week runs Monday to Sunday.
 *
 * It is built server-side (functions/weeklyReport.js - by the Saturday 7 AM run, the Rebuild
 * button, or the connector), and every section can then be edited here. An edited report, or one
 * marked Final, is never rebuilt over unless someone asks for it.
 */
@Serializable
data class WeeklyReport(
    val weekStart: String = "",
    val weekEnd: String = "",
    val daysLogged: List<String> = emptyList(),          // the days whose log had something written in it
    val summary: String = "",
    val weather: List<String> = emptyList(),             // one line per day: high, low, conditions
    val precipDays: Int = 0,
    val crew: List<String> = emptyList(),                // per day: the crew's headcount and the subs on site
    val subsOnSite: List<String> = emptyList(),
    val workCompleted: List<WeeklyWorkGroup> = emptyList(),
    val deliveriesInspections: List<String> = emptyList(),
    val issues: List<WeeklyIssue> = emptyList(),
    val nextWeek: List<String> = emptyList(),
    val photos: List<WeeklyPhoto> = emptyList(),         // up to 6
    val notes: List<String> = emptyList(),
    val status: String = WeeklyReportStatus.DRAFT,
    val edited: Boolean = false,                          // someone changed it by hand: the Saturday run leaves it alone
    val pdfUrl: String = "",
    val pdfBuiltAt: Long = 0L,
    val builtAt: Long = 0L,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val updatedBy: String = ""
) {
    /** The stored PDF shows the report as it is now. */
    fun pdfIsCurrent(): Boolean = pdfUrl.isNotBlank() && pdfBuiltAt >= updatedAt
}

/** The week's work done by one crew or sub: [who] and the lines, each ending with the days it was logged, e.g. "framed the lanai walls (Mon, Wed)". */
@Serializable
data class WeeklyWorkGroup(val who: String = "", val lines: List<String> = emptyList())

/** An issue raised during the week: the day it first appeared, and whether it was still listed on the last logged day. */
@Serializable
data class WeeklyIssue(val text: String = "", val firstSeen: String = "", val status: String = WeeklyIssueStatus.OPEN)

@Serializable
data class WeeklyPhoto(val date: String = "", val name: String = "", val url: String = "")

object WeeklyReportStatus {
    const val DRAFT = "Draft"
    const val FINAL = "Final"
}

object WeeklyIssueStatus {
    const val OPEN = "open"
    const val CLEARED = "cleared"
}
