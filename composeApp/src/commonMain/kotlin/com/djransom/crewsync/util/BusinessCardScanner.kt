package com.djransom.crewsync.util

/** Runs on-device/in-browser text recognition on a photo (no network round-trip, no per-scan
 * cost) and returns the raw recognized text, one line per line of text found in the image. */
expect suspend fun recognizeTextInImage(platformFile: Any): String

data class ScannedCardInfo(
    val name: String = "",
    val jobTitle: String = "",
    val company: String = "",
    val email: String = "",
    val phone: String = ""
)

private val EMAIL_REGEX = Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""")
private val PHONE_REGEX = Regex("""(\+?1[-.\s]?)?\(?\d{3}\)?[-.\s]?\d{3}[-.\s]?\d{4}""")
private val URL_HINTS = listOf("www.", "http://", "https://", ".com", ".net", ".org", ".io")
private val TITLE_HINTS = listOf(
    "manager", "director", "owner", "president", "ceo", "cfo", "coo", "vp",
    "engineer", "foreman", "superintendent", "estimator", "coordinator",
    "specialist", "supervisor", "electrician", "plumber", "technician"
)

/** Best-effort heuristic parse of OCR'd business-card text into structured fields. This is
 * meant as a starting point for the user to review/correct in the contact form, not a
 * guaranteed-accurate parse - business card layouts vary too much for that. */
fun parseBusinessCardText(raw: String): ScannedCardInfo {
    val lines = raw.lines().map { it.trim() }.filter { it.isNotBlank() }

    val email = lines.firstNotNullOfOrNull { EMAIL_REGEX.find(it)?.value }.orEmpty()
    val phone = lines.firstNotNullOfOrNull { PHONE_REGEX.find(it)?.value }.orEmpty()

    val remaining = lines.filterNot { line ->
        EMAIL_REGEX.containsMatchIn(line) ||
            PHONE_REGEX.containsMatchIn(line) ||
            URL_HINTS.any { line.contains(it, ignoreCase = true) }
    }

    val titleLine = remaining.firstOrNull { line ->
        TITLE_HINTS.any { line.contains(it, ignoreCase = true) }
    }
    val nonTitleLines = remaining.filterNot { it == titleLine }

    return ScannedCardInfo(
        name = nonTitleLines.getOrNull(0).orEmpty(),
        jobTitle = titleLine.orEmpty(),
        company = nonTitleLines.getOrNull(1).orEmpty(),
        email = email,
        phone = phone
    )
}
