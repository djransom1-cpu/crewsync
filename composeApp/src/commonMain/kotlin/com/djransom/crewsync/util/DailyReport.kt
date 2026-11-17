package com.djransom.crewsync.util

import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.auth.auth
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Our own Cloud Function that lays out a day's log as a PDF - see dailyReportPdf in functions/dailyReport.js. */
private const val DAILY_REPORT_PDF_URL =
    "https://us-central1-gen-lang-client-0438127279.cloudfunctions.net/dailyReportPdf"

private val dailyReportHttp = HttpClient()

sealed class DailyReportResult {
    data class Ready(val url: String) : DailyReportResult()
    data class Failed(val message: String) : DailyReportResult()
}

/**
 * Builds (or rebuilds) the PDF for [projectId]'s log on [date] and returns its download URL.
 * The function also files the PDF under the project's Files tab ("Daily Reports" folder) and
 * records the URL on the log itself, so later opens don't need to rebuild it.
 */
suspend fun buildDailyReportPdf(projectId: String, date: String): DailyReportResult {
    return try {
        val token = Firebase.auth.currentUser?.getIdToken(false) ?: return DailyReportResult.Failed("You're not signed in.")
        val response = dailyReportHttp.post(DAILY_REPORT_PDF_URL) {
            header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("projectId", projectId); put("date", date) }.toString())
        }
        val text = response.bodyAsText()
        val json = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()
        if (response.status.value in 200..299) {
            val url = json?.get("url")?.jsonPrimitive?.content
            if (url.isNullOrBlank()) DailyReportResult.Failed("The report was built but no link came back.") else DailyReportResult.Ready(url)
        } else {
            DailyReportResult.Failed(json?.get("error")?.jsonPrimitive?.content ?: "Couldn't build the report (error ${response.status.value}).")
        }
    } catch (e: Exception) {
        DailyReportResult.Failed("Couldn't reach Crewsync to build the report. Check your connection and try again. (${e.message})")
    }
}
