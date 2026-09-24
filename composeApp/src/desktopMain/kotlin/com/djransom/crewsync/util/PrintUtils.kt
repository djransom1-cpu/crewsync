package com.djransom.crewsync.util

import com.djransom.crewsync.data.model.Task
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import java.awt.Desktop
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date

actual fun printPlannerOutline(projectName: String, buckets: List<String>, tasks: List<Task>) {
    CoroutineScope(Dispatchers.IO).launch {
        try {
            val file = File.createTempFile("crewsync_planner_", ".pdf")
            file.deleteOnExit()
            renderPlannerPdf(projectName, buckets, tasks, file)

            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.PRINT)) {
                Desktop.getDesktop().print(file)
            } else if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                // No direct "print" verb registered for PDFs on this machine (uncommon) - opening
                // it at least gets the user to a viewer they can print from themselves.
                Desktop.getDesktop().open(file)
            }
        } catch (_: Exception) {
        }
    }
}

private fun renderPlannerPdf(projectName: String, buckets: List<String>, tasks: List<Task>, outFile: File) {
    val lines = buildPlannerPrintLines(buckets, tasks)
    val pageHeight = PDRectangle.LETTER.height
    val margin = 50f
    val bodyFontSize = 11f
    val lineHeight = 16f

    val document = PDDocument()
    try {
        var page = PDPage(PDRectangle.LETTER)
        document.addPage(page)
        var stream = PDPageContentStream(document, page)
        var y = pageHeight - margin

        fun newPage() {
            stream.close()
            page = PDPage(PDRectangle.LETTER)
            document.addPage(page)
            stream = PDPageContentStream(document, page)
            y = pageHeight - margin
        }

        fun drawLine(text: String, x: Float, size: Float, font: PDType1Font) {
            stream.beginText()
            stream.setFont(font, size)
            stream.newLineAtOffset(x, y)
            stream.showText(sanitizeForPdf(text))
            stream.endText()
        }

        drawLine("${projectName.ifBlank { "Project" }} - Task Planner", margin, 16f, PDType1Font.HELVETICA_BOLD)
        y -= 20f
        drawLine("Printed ${SimpleDateFormat("MMM d, yyyy").format(Date())}", margin, 9f, PDType1Font.HELVETICA)
        y -= lineHeight * 1.5f

        lines.forEach { line ->
            if (y < margin + lineHeight) newPage()
            val indentPx = margin + line.indent * 18f
            val font = if (line.bold) PDType1Font.HELVETICA_BOLD else PDType1Font.HELVETICA
            val size = if (line.indent == 0) 13f else bodyFontSize
            val prefix = if (line.isChecklistItem) (if (line.isChecked) "[x] " else "[ ] ") else ""
            drawLine(prefix + line.text, indentPx, size, font)
            y -= lineHeight
        }

        stream.close()
        document.save(outFile)
    } finally {
        document.close()
    }
}

// PDType1Font's standard fonts only support WinAnsiEncoding (roughly Latin-1) - a stray character
// outside that range (a smart quote pasted from elsewhere, an emoji, etc.) throws mid-render and
// would silently kill the whole print job. Strip to the safe range rather than let one bad
// character in a task title blank the page.
private fun sanitizeForPdf(text: String): String = text.map { if (it.code in 32..255) it else '?' }.joinToString("")
