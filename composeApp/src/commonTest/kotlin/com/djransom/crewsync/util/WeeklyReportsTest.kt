package com.djransom.crewsync.util

import com.djransom.crewsync.data.model.DailyLog
import com.djransom.crewsync.data.model.ProjectFile
import com.djransom.crewsync.data.model.WeeklyIssue
import com.djransom.crewsync.data.model.WeeklyPhoto
import com.djransom.crewsync.data.model.WeeklyReport
import com.djransom.crewsync.data.model.WeeklyWorkGroup
import com.djransom.crewsync.i18n.I18n
import com.djransom.crewsync.util.ScheduleSampleData.day
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The app's side of the weekly report: the week it falls in, editing "who did it" as text, the
 * photos it can choose from, and the email text. (The report is built on the server - see
 * functions/weeklyReport.js and its tests.) Run with: gradlew :composeApp:desktopTest
 */
class WeeklyReportsTest {
    @BeforeTest
    fun english() = I18n.init("en")

    @Test
    fun aWeekRunsMondayToSunday() {
        assertEquals(day(2026, 11, 9), day(2026, 11, 9).weekMonday())
        assertEquals(day(2026, 11, 9), day(2026, 11, 12).weekMonday())
        assertEquals(day(2026, 11, 9), day(2026, 11, 15).weekMonday(), "Sunday ends the week")
        assertEquals("Nov 9 - 15, 2026", weekRangeText(day(2026, 11, 9)))
        assertEquals("Nov 30 - Dec 6, 2026", weekRangeText(day(2026, 11, 30)))
        assertEquals("Dec 28 - Jan 3, 2027", weekRangeText(day(2026, 12, 28)))
        assertEquals("Mon Nov 9", shortLogDay("2026-11-09"))
    }

    @Test
    fun workGroupsEditAsWhoBlocks() {
        val groups = listOf(
            WeeklyWorkGroup("NACE", listOf("framed the lanai walls (Mon, Wed)", "roof sheathing (Fri)")),
            WeeklyWorkGroup("D & W Mechanical", listOf("started plumbing rough-in (Mon)"))
        )
        val text = workGroupsToText(groups)
        assertEquals("NACE:\nframed the lanai walls (Mon, Wed)\nroof sheathing (Fri)\n\nD & W Mechanical:\nstarted plumbing rough-in (Mon)", text)
        assertEquals(groups, workGroupsFromText(text, "NACE"), "what goes out comes back")
    }

    @Test
    fun editedWhoBlocksAreTidied() {
        val typed = """
            Sealed the windows
            A+ Concrete:
            - poured the patio
            • stripped forms

            NACE:
            - note: deck boards
            a+ concrete:
            set the curbs
        """.trimIndent()
        assertEquals(
            listOf(
                WeeklyWorkGroup("NACE", listOf("Sealed the windows", "note: deck boards")),   // lines before any heading go to the crew
                WeeklyWorkGroup("A+ Concrete", listOf("poured the patio", "stripped forms", "set the curbs"))
            ),
            workGroupsFromText(typed, "NACE"),
            "bullets dropped, a '- ...:' item isn't a heading, and the same who in two blocks becomes one group"
        )
        assertEquals(emptyList(), workGroupsFromText("\n\nNACE:\n\n", "NACE"), "a heading with nothing under it is dropped")
    }

    @Test
    fun photosAreChosenFromTheWeeksLogs() {
        fun log(date: String, vararg names: String) = DailyLog(id = date, date = date, photos = names.map { ProjectFile(name = it, url = "https://example.com/$it") })
        val logs = listOf(log("2026-11-13", "c.jpg"), log("2026-11-09", "a.jpg", "b.jpg"), log("2026-11-16", "next-week.jpg"), log("2026-11-08", "last-week.jpg"))
        assertEquals(
            listOf(WeeklyPhoto("2026-11-09", "a.jpg", "https://example.com/a.jpg"), WeeklyPhoto("2026-11-09", "b.jpg", "https://example.com/b.jpg"), WeeklyPhoto("2026-11-13", "c.jpg", "https://example.com/c.jpg")),
            weekPhotoChoices(logs, day(2026, 11, 9))
        )
    }

    @Test
    fun theEmailTextStandsOnItsOwn() {
        val report = WeeklyReport(
            weekStart = "2026-11-09", summary = "4 days logged, 6 crew at peak.",
            weather = listOf("Mon Nov 9: 34°-41°F, light rain"), precipDays = 1,
            crew = listOf("Mon Nov 9: NACE 6; subs: D & W Mechanical"), subsOnSite = listOf("D & W Mechanical"),
            workCompleted = listOf(WeeklyWorkGroup("NACE", listOf("framed the lanai walls (Mon, Wed)"))),
            issues = listOf(WeeklyIssue("Roof leak at the chimney", "2026-11-13", "open"), WeeklyIssue("Meter can", "2026-11-09", "cleared")),
            nextWeek = listOf("NACE - continue roof"), pdfUrl = "https://example.com/w.pdf"
        )
        val text = weeklyReportPlainText(report, "Martin")
        assertTrue(text.startsWith("Weekly Report - Martin\nWeek of Nov 9 - 15, 2026 (Draft)"))
        assertTrue("Rain or snow on 1 day(s)" in text)
        assertTrue("  NACE\n    - framed the lanai walls (Mon, Wed)" in text)
        assertTrue("OPEN: Roof leak at the chimney (since Fri Nov 13)" in text)
        assertTrue("Cleared: Meter can (since Mon Nov 9)" in text)
        assertTrue(text.endsWith("PDF: https://example.com/w.pdf"))
        assertEquals("Weekly Report - Martin - week of Nov 9 - 15, 2026", weeklyReportSubject("Martin", day(2026, 11, 9)))
    }

    @Test
    fun aPdfIsCurrentOnlyWhileNothingHasChangedSince() {
        assertFalse(WeeklyReport().pdfIsCurrent())
        assertTrue(WeeklyReport(pdfUrl = "u", pdfBuiltAt = 10, updatedAt = 10).pdfIsCurrent())
        assertFalse(WeeklyReport(pdfUrl = "u", pdfBuiltAt = 10, updatedAt = 11).pdfIsCurrent(), "edited after the PDF was made")
    }
}
