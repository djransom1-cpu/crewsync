package com.djransom.crewsync.util

import com.djransom.crewsync.data.model.Task
import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSDateFormatterStyle
import platform.UIKit.UIMarkupTextPrintFormatter
import platform.UIKit.UIPrintInfo
import platform.UIKit.UIPrintInfoOutputType
import platform.UIKit.UIPrintInteractionController

// Built as an HTML string rather than a hand-paginated PDF (the approach the other two
// platforms take) - UIMarkupTextPrintFormatter handles page breaks itself from markup, which
// is considerably less code than replicating Desktop/Android's manual line-by-page layout in
// Core Graphics.
actual fun printPlannerOutline(projectName: String, buckets: List<String>, tasks: List<Task>) {
    val printInfo = UIPrintInfo.printInfo()
    printInfo.outputType = UIPrintInfoOutputType.UIPrintInfoOutputGeneral
    printInfo.jobName = "${projectName.ifBlank { "Project" }} Planner"

    val controller = UIPrintInteractionController.sharedPrintController() ?: return
    controller.printInfo = printInfo
    controller.printFormatter = UIMarkupTextPrintFormatter(markupText = buildPlannerHtml(projectName, buckets, tasks))
    controller.presentAnimated(true, completionHandler = null)
}

private fun buildPlannerHtml(projectName: String, buckets: List<String>, tasks: List<Task>): String {
    val lines = buildPlannerPrintLines(buckets, tasks)
    val formatter = NSDateFormatter().apply { dateStyle = NSDateFormatterStyle.NSDateFormatterMediumStyle }
    val dateStr = formatter.stringFromDate(NSDate())

    val body = StringBuilder()
    lines.forEach { line ->
        val marginLeft = line.indent * 20
        when {
            line.indent == 0 ->
                body.append("<h3 style='margin:16px 0 4px 0;'>${escapeHtml(line.text)}</h3>")
            line.isChecklistItem -> {
                val box = if (line.isChecked) "&#9745;" else "&#9744;"
                body.append("<div style='margin-left:${marginLeft}px;font-size:14px;'>$box ${escapeHtml(line.text)}</div>")
            }
            else ->
                body.append("<div style='margin-left:${marginLeft}px;font-weight:bold;font-size:15px;margin-top:6px;'>${escapeHtml(line.text)}</div>")
        }
    }

    return """
        <html>
        <body style="font-family: -apple-system, Helvetica, sans-serif;">
        <h2 style="margin-bottom:0;">${escapeHtml(projectName.ifBlank { "Project" })} - Task Planner</h2>
        <div style="color:#666;font-size:12px;">Printed $dateStr</div>
        $body
        </body>
        </html>
    """.trimIndent()
}

private fun escapeHtml(text: String): String =
    text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
