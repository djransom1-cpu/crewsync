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

    override val pageCount: Int = renderer.pageCount

    override fun renderPage(pageIndex: Int): ImageBitmap? {
        if (pageIndex < 0 || pageIndex >= pageCount) return null
        
        val page = renderer.openPage(pageIndex)
        // High quality render for blueprints
        val bitmap = Bitmap.createBitmap(page.width * 2, page.height * 2, Bitmap.Config.ARGB_8888)
        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        page.close()
        
        return bitmap.asImageBitmap()
    }

    fun close() {
        renderer.close()
        pfd.close()
    }
}

@Composable
actual fun rememberPdfRenderer(url: String): com.example.crewsync.util.PdfRenderer? {
    val context = LocalContext.current
    var renderer by remember { mutableStateOf<AndroidPdfRenderer?>(null) }

    LaunchedEffect(url) {
        withContext(Dispatchers.IO) {
            try {
                val client = OkHttpClient()
                val request = Request.Builder().url(url).build()
                val response = client.newCall(request).execute()
                
                if (response.isSuccessful) {
                    val tempFile = File(context.cacheDir, "temp_blueprint.pdf")
                    val fos = FileOutputStream(tempFile)
                    fos.write(response.body?.bytes() ?: return@withContext)
                    fos.close()
                    
                    withContext(Dispatchers.Main) {
                        renderer = AndroidPdfRenderer(tempFile)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            renderer?.close()
        }
    }

    return renderer
}
