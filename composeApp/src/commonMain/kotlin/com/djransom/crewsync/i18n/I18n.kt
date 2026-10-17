package com.djransom.crewsync.i18n

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * The app's languages. Every piece of on-screen text goes through [t] with its English wording as
 * the key; the other languages are tables keyed by that English (see Translations*.kt). Anything
 * not in a table - a word nobody translated yet, a name someone typed - just shows as written.
 *
 * Only what's SHOWN is translated. Values the app stores and checks (bucket names like
 * "In Progress", roles, recurrence, contact types) stay English in the database; screens wrap them
 * in [t] where they're displayed.
 */
object I18n {
    /** (code, name in its own language) */
    val languages = listOf(
        "en" to "English",
        "pl" to "Polski",
        "nl" to "Nederlands",
        "fr" to "Français",
        "de" to "Deutsch",
    )

    /** "" = follow the device's language. */
    var chosen by mutableStateOf("")
        private set

    /** The language in use. Reading it inside a composable recomposes that screen when it changes. */
    val current: String get() = chosen.ifEmpty { systemLanguageCode().lowercase().take(2) }.let { c -> if (languages.any { it.first == c }) c else "en" }

    const val SETTINGS_KEY = "app_language"

    fun init(saved: String) { chosen = saved }

    fun choose(code: String, save: (String) -> Unit) { chosen = code; save(code) }
}

/** The device's language (two letters, e.g. "pl"). */
expect fun systemLanguageCode(): String

private val tables: Map<String, Map<String, String>> by lazy {
    mapOf("pl" to translationsPl, "nl" to translationsNl, "fr" to translationsFr, "de" to translationsDe)
}

/**
 * On-screen text in the user's language. [en] is the English wording; `{0}`, `{1}`… in it are
 * filled from [args] (numbers, names, dates - never translated).
 */
fun t(en: String, vararg args: Any?): String {
    val lang = I18n.current
    val s = if (lang == "en") en else tables[lang]?.get(en) ?: en
    if (args.isEmpty()) return s
    var out = s
    args.forEachIndexed { i, a -> out = out.replace("{$i}", a?.toString() ?: "") }
    return out
}

private val MONTHS = listOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")
private val MONTHS_SHORT = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
private val DAYS_SHORT = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

/** Month name in the user's language (1 = January). */
fun monthName(number: Int): String = t(MONTHS[(number - 1).coerceIn(0, 11)])
/** Short month name ("Jan"; "sty" in Polish). Keyed "Jan (short)" in the tables, so "May" doesn't clash with the full name. */
fun monthShort(number: Int): String {
    val en = MONTHS_SHORT[(number - 1).coerceIn(0, 11)]
    return if (I18n.current == "en") en else t("$en (short)").removeSuffix(" (short)")
}
/** "Oct 17" in English, "17 paź" / "17 okt" / "17 oct" / "17. Okt" elsewhere. */
fun monthDay(month: Int, day: Int): String = if (I18n.current == "en") "${monthShort(month)} $day" else t("{1} {0}|date", monthShort(month), day)
/** Short weekday name (1 = Monday … 7 = Sunday, ISO). */
fun dayShort(isoDay: Int): String = t(DAYS_SHORT[(isoDay - 1).coerceIn(0, 6)])

/** A clock time: 2:30 PM in English, 14:30 in the other languages. */
fun clockTime(hour: Int, minute: Int): String =
    if (I18n.current == "en") {
        val h = if (hour % 12 == 0) 12 else hour % 12
        "$h:${minute.toString().padStart(2, '0')} ${if (hour < 12) "AM" else "PM"}"
    } else "$hour:${minute.toString().padStart(2, '0')}"

/** An hour on the day grid: 3 PM in English, 15:00 in the other languages. */
fun hourLabel(hour: Int): String =
    if (I18n.current == "en") { if (hour == 0) "12 AM" else if (hour < 12) "$hour AM" else if (hour == 12) "12 PM" else "${hour - 12} PM" }
    else "$hour:00"

/** A date in numbers the way each country writes it: 10/17/2026, 17.10.2026, 17-10-2026, 17/10/2026. */
fun numericDate(month: Int, day: Int, year: Int? = null): String = when (I18n.current) {
    "en" -> "$month/$day" + (year?.let { "/$it" } ?: "")
    "nl" -> "$day-$month" + (year?.let { "-$it" } ?: "")
    "fr" -> "$day/$month" + (year?.let { "/$it" } ?: "")
    else -> "$day.$month" + (year?.let { ".$it" } ?: "")   // pl, de
}
