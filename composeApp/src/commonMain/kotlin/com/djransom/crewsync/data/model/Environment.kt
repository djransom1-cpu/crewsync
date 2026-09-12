package com.djransom.crewsync.data.model

import kotlinx.serialization.Serializable

// A user can belong to several Environments at once (e.g. their own business, a family
// project list, a crew they were invited into) and switches between them like Slack
// workspaces - see User.environmentIds/activeEnvironmentId. The document id IS the invite
// code (a short random slug, e.g. "K7QX2P9F"): anyone signed in may fetch a single
// environment by its exact id (see firestore.rules), but never list/browse the collection,
// so an invite code only works if someone who's already a member shares it with you.
@Serializable
data class Environment(
    val id: String = "",
    val name: String = "",
    val ownerId: String = "",
    val createdAt: Long = 0L
)
