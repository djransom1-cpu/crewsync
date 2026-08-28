package com.djransom.crewsync.util

import androidx.compose.runtime.*
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.graphics.toComposeImageBitmap
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.storage.Data
import dev.gitlive.firebase.storage.storage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory
import org.apache.pdfbox.rendering.ImageType
import org.apache.pdfbox.rendering.PDFRenderer
import java.io.ByteArrayOutputStream
import java.io.File

// Mirrors AndroidPdfRenderer: same 2560px-max / 2x-scale cap on the rasterized page so a huge
// blueprint doesn't blow past reasonable texture/memory limits on either platform.
class DesktopPdfRenderer(private val document: PDDocument) : PdfRenderer {
    private val renderer = PDFRenderer(document)

    override val pageCount: Int = document.numberOfPages

    override fun renderPage(pageIndex: Int): ImageBitmap? {
        if (pageIndex < 0 || pageIndex >= pageCount) return null

        val page = document.getPage(pageIndex)
        val box = page.mediaBox
        val maxDimension = 2560f
        val scale = minOf(maxDimension / box.width, maxDimension / box.height, 2.0f)
        val dpi = 72f * scale

        val image = renderer.renderImageWithDPI(pageIndex, dpi, ImageType.ARGB)
        return image.toComposeImageBitmap()
    }

    fun close() {
        document.close()
    }
}

@Composable
actual fun rememberPdfRenderer(url: String): PdfRenderer? {
    var renderer by remember { mutableStateOf<DesktopPdfRenderer?>(null) }

    LaunchedEffect(url) {
        if (url.isBlank()) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            try {
                val client = OkHttpClient()
                val request = Request.Builder().url(url).build()
                val response = client.newCall(request).execute()

                if (response.isSuccessful) {
                    val tempFile = File.createTempFile("crewsync_blueprint", ".pdf")
                    tempFile.deleteOnExit()
                    response.body?.byteStream()?.use { input ->
                        tempFile.outputStream().use { output -> input.copyTo(output) }
                    }

                    val document = PDDocument.load(tempFile)
                    withContext(Dispatchers.Main) {
                        renderer?.close()
                        renderer = DesktopPdfRenderer(document)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    DisposableEffect(url) {
        onDispose {
            renderer?.close()
        }
    }

    return renderer
}

actual suspend fun exportMarkedUpPdf(storagePath: String, pages: List<ImageBitmap>): String? {
    if (pages.isEmpty()) return null
    return withContext(Dispatchers.IO) {
        val document = PDDocument()
        try {
            pages.forEach { bitmap ->
                val awtImage = bitmap.toAwtImage()
                val page = PDPage(PDRectangle(awtImage.width.toFloat(), awtImage.height.toFloat()))
                document.addPage(page)
                val pdImage = LosslessFactory.createFromImage(document, awtImage)
                PDPageContentStream(document, page).use { cs ->
                    cs.drawImage(pdImage, 0f, 0f, awtImage.width.toFloat(), awtImage.height.toFloat())
                }
            }
            val bytes = ByteArrayOutputStream().use { out -> document.save(out); out.toByteArray() }

            val storageRef = Firebase.storage.reference(storagePath)
            storageRef.putData(Data(bytes))
            storageRef.getDownloadUrl()
        } finally {
            document.close()
        }
    }
}
