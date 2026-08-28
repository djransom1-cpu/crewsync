package com.djransom.crewsync.util

import android.graphics.Bitmap
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.storage.Data
import dev.gitlive.firebase.storage.storage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

class AndroidPdfRenderer(private val file: File) : com.djransom.crewsync.util.PdfRenderer {
    private val pfd: ParcelFileDescriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer: PdfRenderer = PdfRenderer(pfd)
    private var lastBitmap: Bitmap? = null

    override val pageCount: Int = renderer.pageCount

    override fun renderPage(pageIndex: Int): ImageBitmap? {
        if (pageIndex < 0 || pageIndex >= pageCount) return null
        
        lastBitmap?.recycle()
        lastBitmap = null

        val page = renderer.openPage(pageIndex)
        // High quality render capped within max GPU texture limits (2560px max bound)
        val maxDimension = 2560f
        val scale = minOf(maxDimension / page.width, maxDimension / page.height, 2.0f)
        val renderWidth = (page.width * scale).toInt().coerceAtLeast(1)
        val renderHeight = (page.height * scale).toInt().coerceAtLeast(1)

        val bitmap = Bitmap.createBitmap(renderWidth, renderHeight, Bitmap.Config.ARGB_8888)
        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        page.close()
        
        lastBitmap = bitmap
        return bitmap.asImageBitmap()
    }

    fun close() {
        lastBitmap?.recycle()
        lastBitmap = null
        renderer.close()
        pfd.close()
    }
}

@Composable
actual fun rememberPdfRenderer(url: String): com.djransom.crewsync.util.PdfRenderer? {
    val context = LocalContext.current
    var renderer by remember { mutableStateOf<AndroidPdfRenderer?>(null) }

    LaunchedEffect(url) {
        if (url.isBlank()) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            try {
                val client = OkHttpClient()
                val request = Request.Builder().url(url).build()
                val response = client.newCall(request).execute()
                
                if (response.isSuccessful) {
                    // Keyed by the URL itself (every file's download URL is unique) rather than
                    // a fixed name - a shared filename meant opening a second file while the
                    // first's download/open was still in flight could overwrite the bytes out
                    // from under it, silently showing the wrong sheet's content.
                    val tempFile = File(context.cacheDir, "blueprint_${url.hashCode()}.pdf")
                    response.body?.byteStream()?.use { input ->
                        FileOutputStream(tempFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                    
                    withContext(Dispatchers.Main) {
                        renderer?.close()
                        renderer = AndroidPdfRenderer(tempFile)
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
        val pdfDocument = PdfDocument()
        try {
            pages.forEachIndexed { index, bitmap ->
                val androidBitmap = bitmap.asAndroidBitmap()
                val pageInfo = PdfDocument.PageInfo.Builder(androidBitmap.width, androidBitmap.height, index + 1).create()
                val page = pdfDocument.startPage(pageInfo)
                page.canvas.drawBitmap(androidBitmap, 0f, 0f, null)
                pdfDocument.finishPage(page)
            }
            val bytes = ByteArrayOutputStream().use { out -> pdfDocument.writeTo(out); out.toByteArray() }

            val storageRef = Firebase.storage.reference(storagePath)
            storageRef.putData(Data(bytes))
            storageRef.getDownloadUrl()
        } finally {
            pdfDocument.close()
        }
    }
}
