package com.djransom.crewsync.util

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap

@Composable
expect fun rememberPdfRenderer(url: String): PdfRenderer?

interface PdfRenderer {
    val pageCount: Int
    fun renderPage(pageIndex: Int): ImageBitmap?
}
