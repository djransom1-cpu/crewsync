package com.djransom.crewsync.util

import kotlin.random.Random

private const val SHARE_TOKEN_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"

// The calendarFeed Cloud Function has no Firebase Auth in front of it (see functions/index.js) -
// this token is the feed's only access control, so it needs to be unguessable rather than just
// unique. 32 chars from a 62-symbol alphabet is ~190 bits of entropy.
fun randomShareToken(): String = (1..32).map { SHARE_TOKEN_CHARS[Random.nextInt(SHARE_TOKEN_CHARS.length)] }.joinToString("")

fun calendarFeedUrl(projectId: String, token: String): String =
    "https://us-central1-gen-lang-client-0438127279.cloudfunctions.net/calendarFeed?projectId=$projectId&token=$token"
