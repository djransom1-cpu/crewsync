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

/** Our own Cloud Function that wipes a project and everything in it - see deleteProject in functions/index.js. */
private const val DELETE_PROJECT_URL =
    "https://us-central1-gen-lang-client-0438127279.cloudfunctions.net/deleteProject"

private val projectAdminHttp = HttpClient()

/**
 * Permanently deletes [projectId]: its tasks, calendar, files and photos, notes, chat and the project itself.
 * The function only allows the project's creator (team leader) or an admin. Returns null when it worked,
 * otherwise a message to show.
 */
suspend fun deleteProjectForever(projectId: String): String? {
    return try {
        val token = Firebase.auth.currentUser?.getIdToken(true) ?: return "You're not signed in."
        val response = projectAdminHttp.post(DELETE_PROJECT_URL) {
            header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("projectId", projectId) }.toString())
        }
        val text = response.bodyAsText()
        if (response.status.value in 200..299) null
        else runCatching { Json.parseToJsonElement(text).jsonObject["error"]?.jsonPrimitive?.content }.getOrNull()
            ?: "Couldn't delete the project (error ${response.status.value})."
    } catch (e: Exception) {
        "Couldn't reach Crewsync to delete the project. Check your connection and try again. (${e.message})"
    }
}
