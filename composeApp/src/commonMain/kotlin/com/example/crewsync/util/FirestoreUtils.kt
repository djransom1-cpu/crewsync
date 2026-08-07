package com.example.crewsync.util

import com.example.crewsync.data.model.*
import dev.gitlive.firebase.firestore.DocumentSnapshot

fun DocumentSnapshot.toProjectSafe(): Project {
    return try {
        this.data<Project>().copy(id = this.id)
    } catch (_: Exception) {
        try {
            val name: String = try { this.get("name") } catch (_: Exception) { "Untitled Project" }
            val desc: String = try { this.get("description") } catch (_: Exception) { "" }
            val leader: String = try { this.get("teamLeaderId") } catch (_: Exception) { "" }
            val membersList: List<String> = try { this.get("members") } catch (_: Exception) { emptyList() }
            val locationStr: String = try { this.get("location") } catch (_: Exception) { "Nashville, TN" }
            val created: Long = try { this.get("createdAt") } catch (_: Exception) { 0L }
            Project(
                id = this.id,
                name = name,
                description = desc,
                teamLeaderId = leader,
                members = membersList,
                location = locationStr,
                createdAt = created
            )
        } catch (_: Exception) {
            Project(id = this.id, name = "Project ${this.id.take(4)}")
        }
    }
}

fun DocumentSnapshot.toUserSafe(fallbackEmail: String = ""): User {
    return try {
        val u = this.data<User>()
        if (u.email.isEmpty() && fallbackEmail.isNotEmpty()) u.copy(email = fallbackEmail) else u
    } catch (_: Exception) {
        try {
            val emailStr: String = try { this.get("email") } catch (_: Exception) { fallbackEmail }
            val nameStr: String = try { this.get("name") } catch (_: Exception) { "" }
            val phoneStr: String = try { this.get("phone") } catch (_: Exception) { "" }
            val tradeStr: String = try { this.get("trade") } catch (_: Exception) { "" }
            val roleStr: String = try { this.get("role") } catch (_: Exception) { "Member" }
            val picUrl: String? = try { this.get("profilePictureUrl") } catch (_: Exception) { null }
            val tokenStr: String? = try { this.get("fcmToken") } catch (_: Exception) { null }
            val orderList: List<String> = try { this.get("projectOrder") } catch (_: Exception) { emptyList() }
            val viewModeStr: String = try { this.get("dashboardViewMode") } catch (_: Exception) { "Cards" }
            User(
                uid = this.id,
                email = emailStr,
                name = nameStr,
                phone = phoneStr,
                trade = tradeStr,
                role = roleStr,
                profilePictureUrl = picUrl,
                fcmToken = tokenStr,
                projectOrder = orderList,
                dashboardViewMode = viewModeStr
            )
        } catch (_: Exception) {
            User(uid = this.id, email = fallbackEmail)
        }
    }
}

fun DocumentSnapshot.toTaskSafe(): com.example.crewsync.data.model.Task {
    return try {
        this.data<com.example.crewsync.data.model.Task>().copy(id = this.id)
    } catch (_: Exception) {
        try {
            val projId: String = try { this.get("projectId") } catch (_: Exception) { "" }
            val titleStr: String = try { this.get("title") } catch (_: Exception) { "Untitled Task" }
            val descStr: String = try { this.get("description") } catch (_: Exception) { "" }
            val statusStr: String = try { this.get("status") } catch (_: Exception) { "Not Started" }
            val assigned: String? = try { this.get("assignedTo") } catch (_: Exception) { null }
            val colorStr: String = try { this.get("color") } catch (_: Exception) { "#FFFFFF" }
            val start: Long? = try { this.get("startDate") } catch (_: Exception) { null }
            val due: Long? = try { this.get("dueDate") } catch (_: Exception) { null }
            
            com.example.crewsync.data.model.Task(
                id = this.id,
                projectId = projId,
                title = titleStr,
                description = descStr,
                status = statusStr,
                assignedTo = assigned,
                color = colorStr,
                startDate = start,
                dueDate = due
            )
        } catch (_: Exception) {
            com.example.crewsync.data.model.Task(id = this.id, title = "Task ${this.id.take(4)}")
        }
    }
}

fun com.example.crewsync.data.model.Task.toFirestoreMap(): Map<String, Any?> {
    val map = mutableMapOf<String, Any?>()
    if (id.isNotEmpty()) map["id"] = id
    map["projectId"] = projectId
    map["title"] = title
    map["description"] = description
    map["status"] = status
    if (assignedTo != null) map["assignedTo"] = assignedTo
    map["color"] = color
    if (startDate != null) map["startDate"] = startDate.toDouble()
    if (dueDate != null) map["dueDate"] = dueDate.toDouble()
    map["checklist"] = checklist.map { mapOf("id" to it.id, "text" to it.text, "isDone" to it.isDone) }
    map["attachments"] = attachments.map { mapOf("id" to it.id, "name" to it.name, "url" to it.url, "uploadedBy" to it.uploadedBy, "uploadedAt" to it.uploadedAt) }
    return map
}

fun DocumentSnapshot.toTaskTemplateSafe(): TaskTemplate {
    return try {
        this.data<TaskTemplate>().copy(id = this.id)
    } catch (_: Exception) {
        val titleStr: String = try { this.get("title") } catch (_: Exception) { "Task Template" }
        val tradeStr: String = try { this.get("trade") } catch (_: Exception) { "" }
        val descStr: String = try { this.get("description") } catch (_: Exception) { "" }
        val colorStr: String = try { this.get("colorHex") } catch (_: Exception) { "#38BDF8" }
        val checkList: List<String> = try { this.get("defaultChecklist") } catch (_: Exception) { emptyList() }
        TaskTemplate(this.id, titleStr, tradeStr, descStr, checkList, colorStr)
    }
}

fun TaskTemplate.toFirestoreMap(): Map<String, Any> {
    return mapOf(
        "id" to id,
        "title" to title,
        "trade" to trade,
        "description" to description,
        "defaultChecklist" to defaultChecklist,
        "colorHex" to colorHex
    )
}

fun com.example.crewsync.data.model.Project.toFirestoreMap(): Map<String, Any?> {
    val map = mutableMapOf<String, Any?>()
    if (id.isNotEmpty()) map["id"] = id
    map["name"] = name
    map["description"] = description
    map["teamLeaderId"] = teamLeaderId
    map["members"] = members
    map["location"] = location
    map["createdAt"] = createdAt.toDouble()
    map["cardOrder"] = cardOrder
    map["cardSizes"] = cardSizes
    return map
}

fun ChatMessage.toFirestoreMap(): Map<String, Any?> {
    val map = mutableMapOf<String, Any?>()
    if (id.isNotEmpty()) map["id"] = id
    map["senderId"] = senderId
    map["senderEmail"] = senderEmail
    map["text"] = text
    map["timestamp"] = timestamp.toDouble()
    if (attachmentUrl != null) map["attachmentUrl"] = attachmentUrl
    if (attachmentName != null) map["attachmentName"] = attachmentName
    return map
}

fun Broadcast.toFirestoreMap(): Map<String, Any?> {
    val map = mutableMapOf<String, Any?>()
    if (id.isNotEmpty()) map["id"] = id
    map["projectId"] = projectId
    map["senderName"] = senderName
    map["title"] = title
    map["message"] = message
    map["timestamp"] = timestamp.toDouble()
    return map
}

fun Appointment.toFirestoreMap(): Map<String, Any?> {
    val map = mutableMapOf<String, Any?>()
    if (id.isNotEmpty()) map["id"] = id
    map["projectId"] = projectId
    map["title"] = title
    map["description"] = description
    map["notes"] = notes
    map["location"] = location
    map["color"] = color
    map["isAllDay"] = isAllDay
    map["startDate"] = startDate.toDouble()
    map["endDate"] = endDate.toDouble()
    map["recurrence"] = recurrence
    map["createdBy"] = createdBy
    return map
}

fun ProjectFile.toFirestoreMap(): Map<String, Any?> {
    val map = mutableMapOf<String, Any?>()
    if (id.isNotEmpty()) map["id"] = id
    map["name"] = name
    map["url"] = url
    if (folderId != null) map["folderId"] = folderId
    map["uploadedBy"] = uploadedBy
    map["uploadedAt"] = uploadedAt.toDouble()
    return map
}

fun User.toFirestoreMap(): Map<String, Any?> {
    val map = mutableMapOf<String, Any?>()
    if (uid.isNotEmpty()) map["uid"] = uid
    map["email"] = email
    map["name"] = name
    map["phone"] = phone
    map["trade"] = trade
    map["role"] = role
    if (profilePictureUrl != null) map["profilePictureUrl"] = profilePictureUrl
    if (fcmToken != null) map["fcmToken"] = fcmToken
    map["projectOrder"] = projectOrder
    map["dashboardViewMode"] = dashboardViewMode
    return map
}

fun DocumentSnapshot.toProjectFileSafe(): ProjectFile {
    return try {
        this.data<ProjectFile>().copy(id = this.id)
    } catch (_: Exception) {
        try {
            val nameStr: String = try { this.get("name") } catch (_: Exception) { "Untitled File" }
            val urlStr: String = try { this.get("url") } catch (_: Exception) { "" }
            val folder: String? = try { this.get("folderId") } catch (_: Exception) { null }
            val uploaded: String = try { this.get("uploadedBy") } catch (_: Exception) { "" }
            val time: Long = try { 
                val v = this.get<Any?>("uploadedAt")
                when (v) {
                    is Number -> v.toLong()
                    is String -> v.toLongOrNull() ?: 0L
                    else -> 0L
                }
            } catch (_: Exception) { 0L }
            ProjectFile(
                id = this.id,
                name = nameStr,
                url = urlStr,
                folderId = folder,
                uploadedBy = uploaded,
                uploadedAt = time
            )
        } catch (_: Exception) {
            ProjectFile(id = this.id, name = "File ${this.id.take(4)}")
        }
    }
}
