package com.example.crewsync.util

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream

class AndroidPdfRenderer(private val file: File) : com.example.crewsync.util.PdfRenderer {
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
actual fun rememberPdfRenderer(url: String): com.example.crewsync.util.PdfRenderer? {
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
                    val tempFile = File(context.cacheDir, "temp_blueprint.pdf")
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
