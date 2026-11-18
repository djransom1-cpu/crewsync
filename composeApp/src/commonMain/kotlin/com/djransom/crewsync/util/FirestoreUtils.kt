package com.djransom.crewsync.util

import com.djransom.crewsync.data.model.*
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
            val envId: String = try { this.get("environmentId") } catch (_: Exception) { "" }
            val shareEnabled: Boolean = try { this.get("calendarShareEnabled") } catch (_: Exception) { false }
            val shareToken: String = try { this.get("calendarShareToken") } catch (_: Exception) { "" }
            val isArchived: Boolean = try { this.get("archived") } catch (_: Exception) { false }
            val holidays: List<String> = try { this.get("scheduleHolidays") } catch (_: Exception) { emptyList() }
            val week: List<Int> = try { this.get("workWeek") } catch (_: Exception) { listOf(1, 2, 3, 4, 5) }
            Project(
                id = this.id,
                environmentId = envId,
                name = name,
                description = desc,
                teamLeaderId = leader,
                members = membersList,
                location = locationStr,
                calendarShareEnabled = shareEnabled,
                calendarShareToken = shareToken,
                createdAt = created,
                archived = isArchived,
                scheduleHolidays = holidays,
                workWeek = week
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
            val firstDayStr: String = try { this.get("firstDayOfWeek") } catch (_: Exception) { "Sunday" }
            val envIds: List<String> = try { this.get("environmentIds") } catch (_: Exception) { emptyList() }
            val activeEnvId: String = try { this.get("activeEnvironmentId") } catch (_: Exception) { "" }
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
                dashboardViewMode = viewModeStr,
                firstDayOfWeek = firstDayStr,
                environmentIds = envIds,
                activeEnvironmentId = activeEnvId
            )
        } catch (_: Exception) {
            User(uid = this.id, email = fallbackEmail)
        }
    }
}

fun DocumentSnapshot.toTaskSafe(): com.djransom.crewsync.data.model.Task {
    val task = try {
        this.data<com.djransom.crewsync.data.model.Task>().copy(id = this.id)
    } catch (_: Exception) {
        try {
            val projId: String = try { this.get("projectId") } catch (_: Exception) { "" }
            val titleStr: String = try { this.get("title") } catch (_: Exception) { "Untitled Task" }
            val descStr: String = try { this.get("description") } catch (_: Exception) { "" }
            val statusStr: String = try { this.get("status") } catch (_: Exception) { "Not Started" }
            val assigned: String? = try { this.get("assignedTo") } catch (_: Exception) { null }
            val assignedList: List<String> = try { this.get("assignedMembers") } catch (_: Exception) { emptyList() }
            val colorStr: String = try { this.get("color") } catch (_: Exception) { "#FFFFFF" }
            val start: Long? = try { this.get("startDate") } catch (_: Exception) { null }
            val due: Long? = try { this.get("dueDate") } catch (_: Exception) { null }
            val envId: String = try { this.get("environmentId") } catch (_: Exception) { "" }
            val orderVal: Long = try { this.get("order") } catch (_: Exception) { 0L }

            com.djransom.crewsync.data.model.Task(
                id = this.id,
                environmentId = envId,
                projectId = projId,
                title = titleStr,
                description = descStr,
                status = statusStr,
                assignedTo = assigned,
                assignedMembers = if (assignedList.isNotEmpty()) assignedList else (if (assigned != null) listOf(assigned) else emptyList()),
                color = colorStr,
                startDate = start,
                dueDate = due,
                order = orderVal
            )
        } catch (_: Exception) {
            com.djransom.crewsync.data.model.Task(id = this.id, title = "Task ${this.id.take(4)}")
        }
    }
    // Tasks written before checklists were grouped only have a flat "checklist" array on the
    // document, not "checklistGroups" - whichever path above produced this Task, if it came
    // back with no groups, try to recover that legacy data as a single default group instead
    // of silently losing it. Once the task is saved again under the new schema both fields
    // stay in sync (see toFirestoreMap below), so this only ever fires for pre-migration data.
    return if (task.checklistGroups.isEmpty()) {
        legacyChecklistAsGroup()?.let { task.copy(checklistGroups = listOf(it)) } ?: task
    } else task
}

private fun DocumentSnapshot.legacyChecklistAsGroup(): com.djransom.crewsync.data.model.ChecklistGroup? {
    return try {
        val raw: List<Map<String, Any?>> = this.get("checklist")
        if (raw.isEmpty()) return null
        val items = raw.mapIndexedNotNull { idx, m ->
            val text = m["text"] as? String ?: return@mapIndexedNotNull null
            com.djransom.crewsync.data.model.ChecklistItem(
                id = (m["id"] as? String) ?: "chk_legacy_$idx",
                text = text,
                isDone = (m["isDone"] as? Boolean) ?: false
            )
        }
        if (items.isEmpty()) null else com.djransom.crewsync.data.model.ChecklistGroup(id = "chk_grp_legacy", title = "Checklist", items = items)
    } catch (_: Exception) {
        null
    }
}

fun com.djransom.crewsync.data.model.Task.toFirestoreMap(): Map<String, Any?> {
    val map = mutableMapOf<String, Any?>()
    if (id.isNotEmpty()) map["id"] = id
    map["environmentId"] = environmentId
    map["projectId"] = projectId
    map["title"] = title
    map["description"] = description
    map["status"] = status
    if (assignedTo != null) map["assignedTo"] = assignedTo
    // GitLive's JS Firestore encoder computes an array-backed list's size as
    // descriptor.elementsCount - 1 when it goes through the generic Any-typed encoding path
    // (which every value in this Map<String, Any?> does, since there's no static type to
    // dispatch on). For an empty list that's 0 - 1 = -1, and `Array<Any?>(-1)` throws
    // "RangeError: Invalid array length" - crashing every task save on the web target, since
    // most tasks have at least one empty list field. Omitting empty lists here avoids the
    // encoder path entirely; a missing key decodes back to the class's emptyList() default,
    // same as writing an empty list would have.
    if (assignedMembers.isNotEmpty()) map["assignedMembers"] = assignedMembers
    map["color"] = color
    if (startDate != null) map["startDate"] = startDate.toDouble()
    if (dueDate != null) map["dueDate"] = dueDate.toDouble()
    map["order"] = order.toDouble()
    // Deliberately NOT writing the legacy flat "checklist" field here. It used to be mirrored
    // on every save as a compatibility shim, but that meant any save - even one that never
    // touched checklists - overwrote it with whatever checklistGroups held at that moment,
    // which is how real checklist data got permanently wiped for tasks whose groups were empty
    // at save time. Leaving the old field alone means any task that still has intact legacy
    // data sitting untouched in Firestore keeps being recovered by legacyChecklistAsGroup()
    // on every load, indefinitely, instead of being one save away from destroying it.
    if (checklistGroups.isNotEmpty()) map["checklistGroups"] = checklistGroups
    if (attachments.isNotEmpty()) map["attachments"] = attachments
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

fun com.djransom.crewsync.data.model.Project.toFirestoreMap(): Map<String, Any?> {
    val map = mutableMapOf<String, Any?>()
    if (id.isNotEmpty()) map["id"] = id
    map["environmentId"] = environmentId
    map["name"] = name
    map["description"] = description
    map["teamLeaderId"] = teamLeaderId
    // See the note in Task.toFirestoreMap() below: an empty list hitting GitLive's JS Any-typed
    // encoder throws "Invalid array length". A brand-new project with no members yet, or one
    // whose card order was cleared, would crash on save without this guard.
    if (members.isNotEmpty()) map["members"] = members
    map["location"] = location
    map["createdAt"] = createdAt.toDouble()
    if (cardOrder.isNotEmpty()) map["cardOrder"] = cardOrder
    map["cardSizes"] = cardSizes
    map["calendarShareEnabled"] = calendarShareEnabled
    map["calendarShareToken"] = calendarShareToken
    map["archived"] = archived
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
    map["environmentId"] = environmentId
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
    if (projectOrder.isNotEmpty()) map["projectOrder"] = projectOrder
    map["dashboardViewMode"] = dashboardViewMode
    map["firstDayOfWeek"] = firstDayOfWeek
    if (environmentIds.isNotEmpty()) map["environmentIds"] = environmentIds
    map["activeEnvironmentId"] = activeEnvironmentId
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

// ---- Daily logs (projects/{projectId}/dailyLogs/{date}) ----------------------------------------

fun DailyLog.toFirestoreMap(): Map<String, Any?> {
    val map = mutableMapOf<String, Any?>()
    map["id"] = id
    map["projectId"] = projectId
    map["date"] = date
    map["weather"] = weather
    if (weatherReadings.isNotEmpty()) map["weatherReadings"] = weatherReadings
    // Same empty-list rule as Task.toFirestoreMap(): GitLive's JS encoder can't take an empty
    // array through the Any-typed path, and a missing key decodes back to emptyList() anyway.
    if (crewOnSite.isNotEmpty()) map["crewOnSite"] = crewOnSite
    if (workCompleted.isNotEmpty()) map["workCompleted"] = workCompleted
    if (deliveriesInspections.isNotEmpty()) map["deliveriesInspections"] = deliveriesInspections
    if (issuesDelays.isNotEmpty()) map["issuesDelays"] = issuesDelays
    if (tomorrowPlan.isNotEmpty()) map["tomorrowPlan"] = tomorrowPlan
    map["notes"] = notes
    if (photos.isNotEmpty()) map["photos"] = photos
    if (entries.isNotEmpty()) map["entries"] = entries
    if (contributors.isNotEmpty()) map["contributors"] = contributors
    map["createdBy"] = createdBy
    map["createdAt"] = createdAt.toDouble()
    map["updatedBy"] = updatedBy
    map["updatedAt"] = updatedAt.toDouble()
    map["pdfUrl"] = pdfUrl
    map["pdfBuiltAt"] = pdfBuiltAt.toDouble()
    return map
}

fun DocumentSnapshot.toDailyLogSafe(): DailyLog {
    return try {
        this.data<DailyLog>().copy(id = this.id, date = this.id)
    } catch (_: Exception) {
        fun str(k: String): String = try { this.get<String>(k) } catch (_: Exception) { "" }
        fun strList(k: String): List<String> = try { this.get<List<String>>(k) } catch (_: Exception) { emptyList() }
        fun long(k: String): Long = try { this.get<Double>(k).toLong() } catch (_: Exception) { try { this.get<Long>(k) } catch (_: Exception) { 0L } }
        DailyLog(
            id = this.id,
            projectId = str("projectId"),
            date = this.id,
            weather = str("weather"),
            crewOnSite = strList("crewOnSite"),
            workCompleted = strList("workCompleted"),
            deliveriesInspections = strList("deliveriesInspections"),
            issuesDelays = strList("issuesDelays"),
            tomorrowPlan = strList("tomorrowPlan"),
            notes = str("notes"),
            contributors = strList("contributors"),
            createdBy = str("createdBy"),
            createdAt = long("createdAt"),
            updatedBy = str("updatedBy"),
            updatedAt = long("updatedAt"),
            pdfUrl = str("pdfUrl"),
            pdfBuiltAt = long("pdfBuiltAt")
        )
    }
}

// ---- Schedule phases (projects/{projectId}/schedulePhases/{id}) --------------------------------

fun SchedulePhase.toFirestoreMap(): Map<String, Any?> {
    val map = mutableMapOf<String, Any?>()
    if (id.isNotEmpty()) map["id"] = id
    map["projectId"] = projectId
    map["environmentId"] = environmentId
    map["name"] = name
    map["group"] = group
    map["order"] = order
    // Dates go out as Doubles like every other epoch-millis field in this file; a phase with no
    // date yet simply has no key (it reads back as null).
    if (start != null) map["start"] = start.toDouble()
    if (end != null) map["end"] = end.toDouble()
    map["durationDays"] = durationDays
    map["isMilestone"] = isMilestone
    // Same empty-list rule as Task.toFirestoreMap(): GitLive's JS encoder can't take an empty
    // array through the Any-typed path, and a missing key decodes back to emptyList() anyway.
    if (predecessors.isNotEmpty()) map["predecessors"] = predecessors
    map["trade"] = trade
    map["contactId"] = contactId
    map["contactName"] = contactName
    map["status"] = status
    map["percentComplete"] = percentComplete
    map["percentMode"] = percentMode
    if (baselineStart != null) map["baselineStart"] = baselineStart.toDouble()
    if (baselineEnd != null) map["baselineEnd"] = baselineEnd.toDouble()
    map["notes"] = notes
    if (color.isNotBlank()) map["color"] = color
    if (taskIds.isNotEmpty()) map["taskIds"] = taskIds
    if (appointmentIds.isNotEmpty()) map["appointmentIds"] = appointmentIds
    map["createdAt"] = createdAt.toDouble()
    map["updatedAt"] = updatedAt.toDouble()
    map["updatedBy"] = updatedBy
    return map
}

fun DocumentSnapshot.toSchedulePhaseSafe(): SchedulePhase {
    return try {
        this.data<SchedulePhase>().copy(id = this.id).normalized()
    } catch (_: Exception) {
        // The typed decode is all-or-nothing: one field of the wrong type (a date typed in as
        // text, a null where a string belongs) fails the whole document. Rather than drop the
        // phase off the schedule, rebuild it field by field and default whatever can't be read.
        schedulePhaseFromFields(this.id) { key -> try { this.get<Any?>(key) } catch (_: Exception) { null } }
    }
}

/**
 * Builds a phase from loose fields, tolerating anything missing or of the wrong type: numbers
 * may arrive as Int, Long, Double or numeric text, lists may hold junk entries, and every field
 * falls back to the model's default. [read] returns the raw value stored under a field name, or
 * null. Kept free of Firestore types so it can be unit-tested (see SchedulePhaseParsingTest).
 */
fun schedulePhaseFromFields(id: String, read: (String) -> Any?): SchedulePhase {
    fun str(k: String): String = (read(k) as? String) ?: ""
    fun longOrNull(k: String): Long? = numberOrNull(read(k))?.toLong()
    fun int(k: String): Int = numberOrNull(read(k))?.toInt() ?: 0
    fun strList(k: String): List<String> =
        (read(k) as? List<*>)?.mapNotNull { (it as? String)?.takeIf { s -> s.isNotBlank() } } ?: emptyList()
    val preds = (read("predecessors") as? List<*>)?.mapNotNull { raw ->
        when (raw) {
            is PhasePredecessor -> raw
            is Map<*, *> -> PhasePredecessor(
                phaseId = (raw["phaseId"] as? String) ?: "",
                type = (raw["type"] as? String) ?: PredecessorType.FS,
                lagDays = numberOrNull(raw["lagDays"])?.toInt() ?: 0
            )
            else -> null
        }
    } ?: emptyList()
    return SchedulePhase(
        id = id,
        projectId = str("projectId"),
        environmentId = str("environmentId"),
        name = str("name"),
        group = str("group"),
        order = numberOrNull(read("order"))?.toDouble() ?: 0.0,
        start = longOrNull("start"),
        end = longOrNull("end"),
        durationDays = int("durationDays"),
        isMilestone = (read("isMilestone") as? Boolean) ?: false,
        predecessors = preds,
        trade = str("trade"),
        contactId = str("contactId"),
        contactName = str("contactName"),
        status = str("status"),
        percentComplete = int("percentComplete"),
        percentMode = str("percentMode"),
        baselineStart = longOrNull("baselineStart"),
        baselineEnd = longOrNull("baselineEnd"),
        notes = str("notes"),
        color = str("color"),
        taskIds = strList("taskIds"),
        appointmentIds = strList("appointmentIds"),
        createdAt = longOrNull("createdAt") ?: 0L,
        updatedAt = longOrNull("updatedAt") ?: 0L,
        updatedBy = str("updatedBy")
    ).normalized()
}

/**
 * Tidies values that are readable but out of range, whichever way the phase was decoded: an
 * unknown status becomes "Not started", percent is held to 0-100, a predecessor with no phase
 * (or pointing at this phase itself) is dropped, and links are de-duplicated.
 */
fun SchedulePhase.normalized(): SchedulePhase = copy(
    status = PhaseStatus.normalize(status),
    percentMode = PercentMode.normalize(percentMode),
    percentComplete = percentComplete.coerceIn(0, 100),
    durationDays = durationDays.coerceAtLeast(0),
    predecessors = predecessors
        .filter { it.phaseId.isNotBlank() && it.phaseId != id }
        .map { it.copy(type = PredecessorType.normalize(it.type)) }
        .distinctBy { it.phaseId },
    taskIds = taskIds.distinct(),
    appointmentIds = appointmentIds.distinct()
)

private fun numberOrNull(v: Any?): Number? = when (v) {
    is Number -> v
    is String -> v.trim().toDoubleOrNull()
    else -> null
}
