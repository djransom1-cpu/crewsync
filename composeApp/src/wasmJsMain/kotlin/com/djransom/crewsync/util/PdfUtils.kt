package com.example.crewsync.util

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap

actual class PdfRendererActual actual constructor(url: String) {
    actual fun renderPage(pageIndex: Int): ImageBitmap? {
        return null
    }
}

@Composable
actual fun rememberPdfRenderer(url: String): PdfRendererActual? {
    return null
}
