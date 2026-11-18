package com.djransom.crewsync.util

import com.djransom.crewsync.util.ScheduleSampleData.day
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Reading a schedule from a CSV file, as the Schedule tab's Import does.
 * Run with: gradlew :composeApp:desktopTest
 */
class ScheduleCsvTest {
    private val plain = WorkCalendar()
    private fun read(text: String, existing: List<String> = emptyList(), dayFirstByDefault: Boolean = false) =
        parseScheduleCsv(text, existing, plain, dayFirstByDefault)

    /** The Martin plan of Sep 21, 2026, the way it might be typed into a spreadsheet and saved as CSV. */
    private val martinCsv = """
        Name,Start,Finish,Group,Milestone
        Exterior walls at chimney,9/25/2026,,Exterior / Site,yes
        Roof complete,9/21/2026,10/15/2026,Exterior / Site,
        Siding,9/21/2026,11/15/2026,Exterior / Site,
        Septic & grading,10/15/2026,11/15/2026,Exterior / Site,
        Remaining concrete,11/1/2026,11/15/2026,Exterior / Site,
        Electrical service,10/5/2026,10/9/2026,Rough trades,
        Mechanical & plumbing,10/15/2026,11/19/2026,Rough trades,
        Electrical rough-in,10/29/2026,11/19/2026,Rough trades,
        Insulation,11/19/2026,12/4/2026,Interior,
        Drywall,12/4/2026,1/4/2027,Interior,
        "Tile & flooring",1/4/2027,1/23/2027,Interior,
        Painting,1/9/2027,3/20/2027,Interior,
        Trim & cabinets,1/23/2027,3/5/2027,Interior,
        Finish MEP,3/15/2027,4/5/2027,Closeout,
    """.trimIndent()

    // ---- the Martin plan ----

    @Test
    fun theMartinPlanReadsAsFourteenPhases() {
        val csv = read(martinCsv)
        assertNull(csv.fileProblem)
        assertEquals(14, csv.importable.size)
        assertEquals(false, csv.dayFirst, "9/21/2026 can only be month first")
        val roof = csv.rows.first { it.name == "Roof complete" }
        assertEquals(day(2026, 9, 21), roof.start)
        assertEquals(day(2026, 10, 15), roof.end)
        assertEquals(19, roof.durationDays)
        assertEquals("Exterior / Site", roof.group)
        assertEquals(3, roof.line, "its line in the file, the heading being line 1")
        val chimney = csv.rows.first()
        assertTrue(chimney.isMilestone)
        assertEquals(day(2026, 9, 25), chimney.end, "a milestone needs only its one day")
        assertEquals(1, chimney.durationDays)
        assertEquals(day(2026, 11, 15), csv.rows.first { it.name == "Siding" }.end, "a Sunday is kept as written")
        assertEquals(day(2027, 1, 4), csv.rows.first { it.name == "Drywall" }.end, "over New Year")
        assertEquals(listOf("Exterior / Site", "Rough trades", "Interior", "Closeout"), csv.rows.map { it.group }.distinct())
    }

    @Test
    fun phasesAlreadyOnTheScheduleAreSkippedSoAnImportCanBeRunAgain() {
        val csv = read(martinCsv, existing = listOf("roof  COMPLETE", "Siding"))
        assertEquals(12, csv.importable.size)
        assertEquals(listOf("Roof complete", "Siding"), csv.rows.filter { it.alreadyOnSchedule }.map { it.name })
        assertTrue(csv.rows.none { it.problem != null })
    }

    // ---- the file's shape ----

    @Test
    fun columnsAreFoundByTheirHeadingInAnyOrderAndCase() {
        val csv = read("GROUP;Finish Date;Task Name;start date;Trade\nInterior;12/4/2026;Insulation;11/19/2026;Insulators\n")
        assertEquals(listOf("Insulation"), csv.importable.map { it.name })
        val row = csv.rows.single()
        assertEquals(day(2026, 11, 19) to day(2026, 12, 4), row.start to row.end)
        assertEquals("Interior", row.group)
        assertEquals("Insulators", row.trade)
    }

    @Test
    fun aFileWithoutTheColumnsItNeedsSaysSo() {
        assertEquals(CsvFileProblem.EMPTY, read("").fileProblem)
        assertEquals(CsvFileProblem.EMPTY, read("\n\n ,, \n").fileProblem)
        assertEquals(CsvFileProblem.NO_NAME_COLUMN, read("What,Start,Finish\nRoof,9/21/2026,10/15/2026").fileProblem)
        assertEquals(CsvFileProblem.NO_DATE_COLUMNS, read("Name,Start\nRoof,9/21/2026").fileProblem)
    }

    @Test
    fun quotesSeparatorsAndLineEndingsAreHandled() {
        assertEquals(
            listOf(listOf("Name", "Start"), listOf("Tile, grout & seal", "say \"now\""), listOf("Two\nlines", "")),
            splitCsv("﻿Name,Start\r\n\"Tile, grout & seal\",\"say \"\"now\"\"\"\r\n\"Two\nlines\",\r\n")
        )
        assertEquals(listOf(listOf("a", "b"), listOf("c", "d")), splitCsv("a\tb\nc\td"), "tab separated")
        assertEquals(listOf(listOf("Name", "Start", "Finish"), listOf("Tile, grout", "21.9.2026", "")), splitCsv("Name;Start;Finish\nTile, grout;21.9.2026;"), "semicolons, as Excel writes CSV in much of Europe")
    }

    @Test
    fun blankLinesAreLeftOut() {
        val csv = read("Name,Start,Finish\n\nRoof,9/21/2026,10/15/2026\n,,\n\nSiding,9/21/2026,11/15/2026\n")
        assertEquals(listOf("Roof", "Siding"), csv.rows.map { it.name })
        assertEquals(listOf(3, 6), csv.rows.map { it.line })
    }

    // ---- dates ----

    @Test
    fun datesAreReadInTheFormsSpreadsheetsWriteThem() {
        val expected = day(2026, 9, 21)
        for (text in listOf("2026-09-21", "2026/9/21", "9/21/2026", "9/21/26", "09-21-2026", "Sep 21, 2026", "September 21 2026", "21 Sep 2026", "21-Sep-26", "Mon 9/21/26 8:00 AM", "Monday, September 21, 2026", "2026-09-21T08:00:00Z")) {
            assertEquals(expected, parseCsvDate(text, dayFirst = false), text)
        }
        assertEquals(expected, parseCsvDate("21/9/2026", dayFirst = true))
        assertEquals(expected, parseCsvDate("21.09.2026", dayFirst = true))
        assertNull(parseCsvDate("next Tuesday", dayFirst = false))
        assertNull(parseCsvDate("2/30/2026", dayFirst = false), "not a real date")
        assertNull(parseCsvDate("", dayFirst = false))
    }

    @Test
    fun theFileSettlesWhetherTheDayOrTheMonthComesFirst() {
        // 21/9 can only be day first, so 1/10 in the same file is the 1st of October.
        val european = read("Name,Start,Finish\nRoof,21/9/2026,1/10/2026\n")
        assertEquals(true, european.dayFirst)
        assertEquals(day(2026, 9, 21) to day(2026, 10, 1), european.rows.single().let { it.start to it.end })
        // Nothing over 12: the app's language decides (English is month first).
        assertEquals(day(2026, 1, 10), read("Name,Start,Finish\nRoof,1/10/2026,2/10/2026\n").rows.single().start)
        assertEquals(day(2026, 10, 1), read("Name,Start,Finish\nRoof,1/10/2026,2/10/2026\n", dayFirstByDefault = true).rows.single().start)
        assertNull(read("Name,Start,Finish\nRoof,2026-09-21,2026-10-15\n").dayFirst, "no numeric dates, nothing to settle")
    }

    // ---- rows that can't be imported ----

    @Test
    fun rowsWithProblemsSayWhatIsWrong() {
        val csv = read(
            """
            Name,Start,Finish
            Roof,9/21/2026,10/15/2026
            ,9/21/2026,10/15/2026
            Siding,,11/15/2026
            Septic,soon,11/15/2026
            Concrete,11/1/2026,
            Drywall,12/4/2026,whenever
            Painting,3/20/2027,1/9/2027
            roof,9/21/2026,10/15/2026
            """.trimIndent()
        )
        assertEquals(listOf("Roof"), csv.importable.map { it.name })
        assertEquals(
            listOf(
                null, CsvRowProblem.NO_NAME, CsvRowProblem.NO_START, CsvRowProblem.BAD_START, CsvRowProblem.NO_FINISH,
                CsvRowProblem.BAD_FINISH, CsvRowProblem.FINISH_BEFORE_START, CsvRowProblem.REPEATED_NAME
            ),
            csv.rows.map { it.problem }
        )
        assertEquals("soon", csv.rows[3].problemDetail)
        assertEquals("whenever", csv.rows[5].problemDetail)
    }
}
