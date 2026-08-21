package com.djransom.crewsync.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.suspendCancellableCoroutine
import org.jetbrains.skia.Image as SkiaImage
import org.w3c.dom.CanvasRenderingContext2D
import org.w3c.dom.HTMLCanvasElement
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

// pdf.js is loaded as a plain global script in index.html (window.pdfjsLib) rather than an
// npm/webpack dependency, to keep the JS interop simple - its API surface used here is small.
private fun pdfjsLib(): dynamic = js("window.pdfjsLib")

private suspend fun awaitJsPromise(promise: dynamic): dynamic = suspendCancellableCoroutine { cont ->
    promise.then(
        { value: dynamic -> cont.resume(value) },
        { err: dynamic -> cont.resumeWithException(Exception(err?.toString() ?: "pdf.js error")) }
    )
}

private fun base64ToByteArray(base64: String): ByteArray {
    val binary = window.atob(base64)
    return ByteArray(binary.length) { i -> binary[i].code.toByte() }
}

class JsPdfRenderer(
    override val pageCount: Int,
    private val pages: List<ImageBitmap?>
) : PdfRenderer {
    override fun renderPage(pageIndex: Int): ImageBitmap? = pages.getOrNull(pageIndex)
}

@Composable
actual fun rememberPdfRenderer(url: String): PdfRenderer? {
    var renderer by remember(url) { mutableStateOf<JsPdfRenderer?>(null) }

    LaunchedEffect(url) {
        renderer = null
        if (url.isBlank()) return@LaunchedEffect
        try {
            val lib = pdfjsLib()

            val docParams: dynamic = js("({})")
            docParams.url = url
            docParams.disableWorker = true // avoids needing to separately host pdf.worker.js

            val doc = awaitJsPromise(lib.getDocument(docParams).promise)
            val numPages = doc.numPages as Int

            val canvas = document.createElement("canvas") as HTMLCanvasElement
            val context = canvas.getContext("2d") as CanvasRenderingContext2D

            val rendered = mutableListOf<ImageBitmap?>()
            for (pageNum in 1..numPages) {
                val page = awaitJsPromise(doc.getPage(pageNum))

                val viewportParams: dynamic = js("({})")
                viewportParams.scale = 2.0 // render at 2x for a crisp zoomed-in annotation surface
                val viewport = page.getViewport(viewportParams)
                canvas.width = (viewport.width as Double).toInt()
                canvas.height = (viewport.height as Double).toInt()

                val renderParams: dynamic = js("({})")
                renderParams.canvasContext = context
                renderParams.viewport = viewport
                awaitJsPromise(page.render(renderParams).promise)

                val dataUrl = canvas.toDataURL("image/png")
                val bytes = base64ToByteArray(dataUrl.substringAfter(","))
                rendered.add(SkiaImage.makeFromEncoded(bytes).toComposeImageBitmap())
            }

            renderer = JsPdfRenderer(numPages, rendered)
        } catch (e: Throwable) {
            console.error("PDF render failed:", e)
            renderer = null
        }
    }

    return renderer
}
