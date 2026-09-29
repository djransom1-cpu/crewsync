package com.djransom.crewsync.util

import com.djransom.crewsync.data.model.Task
import kotlinx.browser.document
import org.w3c.dom.HTMLIFrameElement
import kotlin.js.Date

// Same approach as iOS: build an HTML page and let the browser's own print dialog paginate it.
// Printed from a hidden same-document iframe rather than window.open() - a popup can be blocked,
// and the site's cross-origin-isolation headers (see the COOP/COEP service worker) make a new
// top-level window's document harder to reach than an about:blank iframe's.
actual fun printPlannerOutline(projectName: String, buckets: List<String>, tasks: List<Task>, detailed: Boolean) {
    try {
        val iframe = document.createElement("iframe") as HTMLIFrameElement
        iframe.style.position = "fixed"
        iframe.style.right = "0"
        iframe.style.bottom = "0"
        iframe.style.width = "0"
        iframe.style.height = "0"
        iframe.style.border = "0"
        document.body?.appendChild(iframe) ?: return

        val frameWindow = iframe.contentWindow ?: return
        val frameDoc = frameWindow.document
        frameDoc.open()
        frameDoc.write(buildPlannerHtml(projectName, buckets, tasks, detailed))
        frameDoc.close()

        // print() blocks until the dialog closes in Chrome/Firefox but not in Safari, so remove
        // the iframe on afterprint rather than straight after the call.
        frameWindow.addEventListener("afterprint", { iframe.remove() })
        frameWindow.focus()
        frameWindow.print()
    } catch (e: Throwable) {
        console.error("printPlannerOutline failed", e)
    }
}

private fun buildPlannerHtml(projectName: String, buckets: List<String>, tasks: List<Task>, detailed: Boolean): String {
    val lines = buildPlannerPrintLines(buckets, tasks, detailed)
    val dateStr = Date().toLocaleDateString()

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
            line.bold ->
                body.append("<div style='margin-left:${marginLeft}px;font-weight:bold;font-size:15px;margin-top:6px;'>${escapeHtml(line.text)}</div>")
            else ->
                body.append("<div style='margin-left:${marginLeft}px;font-size:14px;color:#333;'>${escapeHtml(line.text)}</div>")
        }
    }

    return """
        <!doctype html>
        <html>
        <head><meta charset="utf-8"><title>${escapeHtml(plannerPrintHeading(projectName, detailed))}</title></head>
        <body style="font-family: -apple-system, Helvetica, Arial, sans-serif;">
        <h2 style="margin-bottom:0;">${escapeHtml(plannerPrintHeading(projectName, detailed))}</h2>
        <div style="color:#666;font-size:12px;">Printed $dateStr</div>
        $body
        </body>
        </html>
    """.trimIndent()
}

private fun escapeHtml(text: String): String =
    text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
