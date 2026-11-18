package com.djransom.crewsync.util

import kotlinx.datetime.LocalDate

// ---- Reading a schedule from a CSV file --------------------------------------------------------
// The Schedule tab's Import: a spreadsheet saved as CSV with Name, Start, Finish and (optionally)
// Group columns becomes a list of phases to preview and then save. Same rules as the connector's
// import_schedule: rows go in the order given, a name already on the schedule is skipped, and a
// date on a weekend is kept as written. Pure, so it can be unit-tested (see ScheduleCsvTest).

/** Why a whole file can't be read. */
enum class CsvFileProblem { EMPTY, NO_NAME_COLUMN, NO_DATE_COLUMNS }

/** Why one row can't be imported. [detail] is the text that couldn't be read, where there is one. */
enum class CsvRowProblem { NO_NAME, NO_START, NO_FINISH, BAD_START, BAD_FINISH, FINISH_BEFORE_START, REPEATED_NAME }

/**
 * One data row of the file. [line] is its line number in the file (the header is line 1), so a
 * problem can be found in the spreadsheet. A row is imported only when it has no [problem] and
 * isn't [alreadyOnSchedule].
 */
data class CsvPhaseRow(
    val line: Int,
    val name: String,
    val group: String,
    val trade: String,
    val start: LocalDate?,
    val end: LocalDate?,
    val isMilestone: Boolean,
    val durationDays: Int,
    val problem: CsvRowProblem? = null,
    val problemDetail: String = "",
    val alreadyOnSchedule: Boolean = false
) {
    val importable: Boolean get() = problem == null && !alreadyOnSchedule
}

/**
 * What a file holds. [dayFirst] says how numeric dates such as 9/10/2026 were read - true for
 * day/month/year - or is null when the file has no dates of that kind.
 */
data class CsvSchedule(val rows: List<CsvPhaseRow>, val fileProblem: CsvFileProblem? = null, val dayFirst: Boolean? = null) {
    val importable: List<CsvPhaseRow> get() = rows.filter { it.importable }
}

/**
 * Reads a schedule CSV. [existingNames] are the phases already on the schedule (matched ignoring
 * case and spacing); [calendar] counts working days; [dayFirstByDefault] decides numeric dates
 * when the file itself doesn't settle it (a day over 12 does: 21/9/2026 can only be day-first).
 *
 * Columns are found by their heading, in any order and any case: Name (or Phase, Task, Activity),
 * Start, Finish (or End), and optional Group, Trade and Milestone (yes / true / 1). Dates may be
 * 2026-09-21, 9/21/2026, 9/21/26, Sep 21 2026, 21-Sep-26, or as Microsoft Project writes them,
 * "Mon 9/21/26 8:00 AM". Commas, semicolons or tabs between columns all work.
 */
fun parseScheduleCsv(text: String, existingNames: Collection<String>, calendar: WorkCalendar, dayFirstByDefault: Boolean): CsvSchedule {
    val table = splitCsv(text)
    val headerIndex = table.indexOfFirst { row -> row.any { it.isNotBlank() } }
    if (headerIndex < 0) return CsvSchedule(emptyList(), CsvFileProblem.EMPTY)
    val header = table[headerIndex].map { normalizeHeading(it) }
    fun column(vararg names: String): Int = header.indexOfFirst { it in names }
    val nameCol = column("name", "phase", "phase name", "task", "task name", "activity", "activity name")
    val startCol = column("start", "start date", "begin", "begins", "from")
    val finishCol = column("finish", "finish date", "end", "end date", "ends", "to")
    val groupCol = column("group", "area", "section", "category")
    val tradeCol = column("trade", "sub", "subcontractor")
    val milestoneCol = column("milestone")
    if (nameCol < 0) return CsvSchedule(emptyList(), CsvFileProblem.NO_NAME_COLUMN)
    if (startCol < 0 || finishCol < 0) return CsvSchedule(emptyList(), CsvFileProblem.NO_DATE_COLUMNS)

    // The file's lines after the heading, with their line numbers, leaving out blank ones.
    val body = table.withIndex().drop(headerIndex + 1).filter { (_, row) -> row.any { it.isNotBlank() } }
    fun cell(row: List<String>, col: Int): String = if (col >= 0) row.getOrNull(col)?.trim().orEmpty() else ""

    // Settle day/month order from the whole file before reading any date.
    val numeric = body.flatMap { (_, row) -> listOf(cell(row, startCol), cell(row, finishCol)) }.mapNotNull { numericDateParts(it) }
    val saysDayFirst = numeric.any { (a, _) -> a > 12 }
    val saysMonthFirst = numeric.any { (_, b) -> b > 12 }
    val dayFirst = when {
        numeric.isEmpty() -> null
        saysDayFirst && !saysMonthFirst -> true
        saysMonthFirst && !saysDayFirst -> false
        else -> dayFirstByDefault
    }

    val existing = existingNames.map { squashName(it) }.toSet()
    val seen = HashSet<String>()
    val rows = body.map { (index, row) ->
        val line = index + 1
        val name = cell(row, nameCol)
        val startText = cell(row, startCol)
        val finishText = cell(row, finishCol)
        val milestone = cell(row, milestoneCol).lowercase() in setOf("yes", "y", "true", "1", "x")
        val start = parseCsvDate(startText, dayFirst ?: dayFirstByDefault)
        var end = parseCsvDate(finishText, dayFirst ?: dayFirstByDefault)
        if (milestone && end == null && finishText.isEmpty()) end = start   // a milestone needs only its one day
        val base = CsvPhaseRow(
            line, name, cell(row, groupCol), cell(row, tradeCol), start,
            if (milestone && start != null) start else end, milestone, 0
        )
        val problem: Pair<CsvRowProblem, String>? = when {
            name.isEmpty() -> CsvRowProblem.NO_NAME to ""
            startText.isEmpty() -> CsvRowProblem.NO_START to ""
            start == null -> CsvRowProblem.BAD_START to startText
            finishText.isEmpty() && !milestone -> CsvRowProblem.NO_FINISH to ""
            end == null -> CsvRowProblem.BAD_FINISH to finishText
            !milestone && end < start -> CsvRowProblem.FINISH_BEFORE_START to ""
            !seen.add(squashName(name)) -> CsvRowProblem.REPEATED_NAME to ""
            else -> null
        }
        when {
            problem != null -> base.copy(problem = problem.first, problemDetail = problem.second)
            squashName(name) in existing -> base.copy(alreadyOnSchedule = true, durationDays = calendar.durationDays(base.start!!, base.end!!))
            else -> base.copy(durationDays = calendar.durationDays(base.start!!, base.end!!))
        }
    }
    return CsvSchedule(rows, null, dayFirst)
}

/** A name compared the way the connector does: case and runs of spaces don't matter. */
private fun squashName(s: String): String = s.trim().lowercase().split(" ").filter { it.isNotEmpty() }.joinToString(" ")

private fun normalizeHeading(s: String): String =
    s.trim().trim('"').lowercase().replace("_", " ").replace(":", "").split(" ").filter { it.isNotEmpty() }.joinToString(" ")

/**
 * Splits CSV text into rows of cells. Handles quoted cells (with commas, line breaks and doubled
 * quotes inside), Windows or Unix line endings, and the byte-order mark Excel puts at the start.
 * The separator is whichever of comma, semicolon or tab the first line uses most.
 */
fun splitCsv(text: String): List<List<String>> {
    val src = text.removePrefix("﻿")
    val firstLine = src.substringBefore('\n')
    val separator = listOf(',', ';', '\t').maxByOrNull { sep -> firstLine.count { it == sep } }?.takeIf { sep -> firstLine.contains(sep) } ?: ','
    val rows = mutableListOf<List<String>>()
    var row = mutableListOf<String>()
    val cell = StringBuilder()
    var quoted = false
    var i = 0
    while (i < src.length) {
        val c = src[i]
        when {
            quoted && c == '"' && src.getOrNull(i + 1) == '"' -> { cell.append('"'); i++ }
            c == '"' && (quoted || cell.isEmpty()) -> quoted = !quoted
            !quoted && c == separator -> { row += cell.toString(); cell.clear() }
            !quoted && (c == '\n' || c == '\r') -> {
                row += cell.toString(); cell.clear()
                rows += row; row = mutableListOf()
                if (c == '\r' && src.getOrNull(i + 1) == '\n') i++
            }
            else -> cell.append(c)
        }
        i++
    }
    if (cell.isNotEmpty() || row.isNotEmpty()) { row += cell.toString(); rows += row }
    return rows
}

private val MONTHS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
private val LEADING_WEEKDAY = Regex("""^(mon|tue|wed|thu|fri|sat|sun)[a-z]*\.?,?\s+""", RegexOption.IGNORE_CASE)
private val TRAILING_TIME = Regex("""[T\s]\d{1,2}:\d{2}(:\d{2}(\.\d+)?)?\s*([ap]\.?m\.?)?\s*(z|[+-]\d{2}:?\d{2})?$""", RegexOption.IGNORE_CASE)
private val ISO_DATE = Regex("""^(\d{4})[-/.](\d{1,2})[-/.](\d{1,2})$""")
private val NUMERIC_DATE = Regex("""^(\d{1,2})[-/.](\d{1,2})[-/.](\d{2}|\d{4})$""")
private val MONTH_FIRST = Regex("""^([a-z]+)\.?\s+(\d{1,2})(?:st|nd|rd|th)?,?\s+(\d{2,4})$""", RegexOption.IGNORE_CASE)
private val DAY_FIRST_WORDS = Regex("""^(\d{1,2})(?:st|nd|rd|th)?[\s-]+([a-z]+)\.?,?[\s-]+(\d{2,4})$""", RegexOption.IGNORE_CASE)

/** The text with any leading weekday and trailing time of day taken off. */
private fun bareDate(raw: String): String =
    raw.trim().trim('"').replace(LEADING_WEEKDAY, "").replace(TRAILING_TIME, "").trim()

/** The first two numbers of a numeric date such as 9/21/2026, for working out the day/month order. Null for any other kind. */
private fun numericDateParts(raw: String): Pair<Int, Int>? =
    NUMERIC_DATE.matchEntire(bareDate(raw))?.destructured?.let { (a, b, _) -> a.toInt() to b.toInt() }

/** A date as a spreadsheet might write it (see [parseScheduleCsv]), or null if it can't be read or isn't a real date. */
fun parseCsvDate(raw: String, dayFirst: Boolean): LocalDate? {
    val s = bareDate(raw)
    if (s.isEmpty()) return null
    fun year(y: String): Int = y.toInt().let { if (y.length <= 2) 2000 + it else it }
    fun month(word: String): Int? = MONTHS.indexOf(word.lowercase().take(3)).takeIf { it >= 0 }?.plus(1)
    fun make(y: Int, m: Int, d: Int): LocalDate? = try { LocalDate(y, m, d) } catch (_: Exception) { null }
    ISO_DATE.matchEntire(s)?.destructured?.let { (y, m, d) -> return make(y.toInt(), m.toInt(), d.toInt()) }
    NUMERIC_DATE.matchEntire(s)?.destructured?.let { (a, b, y) ->
        return if (dayFirst) make(year(y), b.toInt(), a.toInt()) else make(year(y), a.toInt(), b.toInt())
    }
    MONTH_FIRST.matchEntire(s)?.destructured?.let { (m, d, y) -> return month(m)?.let { make(year(y), it, d.toInt()) } }
    DAY_FIRST_WORDS.matchEntire(s)?.destructured?.let { (d, m, y) -> return month(m)?.let { make(year(y), it, d.toInt()) } }
    return null
}
