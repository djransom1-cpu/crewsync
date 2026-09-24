package com.djransom.crewsync.util

import android.content.Context
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import com.djransom.crewsync.data.model.Task
import java.io.ByteArrayOutputStream
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// US Letter at 72dpi points - matches PDRectangle.LETTER on the desktop target, so the two
// platforms' generated pages line up the same way.
private const val PAGE_WIDTH = 612
private const val PAGE_HEIGHT = 792
private const val MARGIN = 50f
private const val LINE_HEIGHT = 18f

actual fun printPlannerOutline(projectName: String, buckets: List<String>, tasks: List<Task>) {
    val context = ContextHolder.context ?: return
    try {
        val pdfBytes = renderPlannerPdfBytes(projectName, buckets, tasks)
        val printManager = context.getSystemService(Context.PRINT_SERVICE) as PrintManager
        val jobName = "${projectName.ifBlank { "Project" }} Planner"
        printManager.print(jobName, PlannerPdfPrintAdapter(pdfBytes), PrintAttributes.Builder().build())
    } catch (_: Exception) {
    }
}

private fun renderPlannerPdfBytes(projectName: String, buckets: List<String>, tasks: List<Task>): ByteArray {
    val lines = buildPlannerPrintLines(buckets, tasks)
    val titlePaint = Paint().apply { textSize = 18f; isFakeBoldText = true; isAntiAlias = true }
    val subPaint = Paint().apply { textSize = 10f; color = android.graphics.Color.DKGRAY; isAntiAlias = true }
    val bodyPaint = Paint().apply { textSize = 11f; isAntiAlias = true }
    val boldPaint = Paint().apply { textSize = 11f; isFakeBoldText = true; isAntiAlias = true }
    val bucketPaint = Paint().apply { textSize = 13f; isFakeBoldText = true; isAntiAlias = true }

    val document = PdfDocument()
    var pageNumber = 1
    var page = document.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create())
    var canvas = page.canvas
    var y = MARGIN + titlePaint.textSize

    canvas.drawText("${projectName.ifBlank { "Project" }} - Task Planner", MARGIN, y, titlePaint)
    y += 20f
    canvas.drawText("Printed ${SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date())}", MARGIN, y, subPaint)
    y += LINE_HEIGHT * 1.5f

    fun newPage() {
        document.finishPage(page)
        pageNumber += 1
        page = document.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create())
        canvas = page.canvas
        y = MARGIN + bodyPaint.textSize
    }

    lines.forEach { line ->
        if (y > PAGE_HEIGHT - MARGIN) newPage()
        val indentPx = MARGIN + line.indent * 18f
        val paint = when {
            line.indent == 0 -> bucketPaint
            line.bold -> boldPaint
            else -> bodyPaint
        }
        val prefix = if (line.isChecklistItem) (if (line.isChecked) "☑ " else "☐ ") else ""
        canvas.drawText(prefix + line.text, indentPx, y, paint)
        y += LINE_HEIGHT
    }

    document.finishPage(page)

    val bytes = ByteArrayOutputStream().use { out ->
        document.writeTo(out)
        out.toByteArray()
    }
    document.close()
    return bytes
}

// The document is already fully rendered to PDF bytes before this adapter is handed to
// PrintManager - onWrite just streams those bytes to the destination, the standard pattern for
// "print a PDF that already exists" rather than laying out content live per print-preview page.
private class PlannerPdfPrintAdapter(private val pdfBytes: ByteArray) : PrintDocumentAdapter() {
    override fun onLayout(
        oldAttributes: PrintAttributes?,
        newAttributes: PrintAttributes,
        cancellationSignal: CancellationSignal?,
        callback: LayoutResultCallback,
        extras: Bundle?
    ) {
        if (cancellationSignal?.isCanceled == true) {
            callback.onLayoutCancelled()
            return
        }
        val info = PrintDocumentInfo.Builder("planner.pdf")
            .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
            .build()
        callback.onLayoutFinished(info, true)
    }

    override fun onWrite(
        pages: Array<out PageRange>?,
        destination: ParcelFileDescriptor,
        cancellationSignal: CancellationSignal?,
        callback: WriteResultCallback
    ) {
        try {
            FileOutputStream(destination.fileDescriptor).use { out -> out.write(pdfBytes) }
            callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
        } catch (e: IOException) {
            callback.onWriteFailed(e.message)
        }
    }
}
