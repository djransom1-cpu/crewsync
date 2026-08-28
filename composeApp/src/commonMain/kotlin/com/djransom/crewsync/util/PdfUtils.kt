package com.djransom.crewsync.util

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap

@Composable
expect fun rememberPdfRenderer(url: String): PdfRenderer?

interface PdfRenderer {
    val pageCount: Int
    fun renderPage(pageIndex: Int): ImageBitmap?
}

// Assembles already-flattened page images (background + markups baked in, produced in
// commonMain via Compose's own offscreen drawing) into a real multi-page PDF and uploads it,
// returning a download URL. Handles both steps platform-side since Firebase Storage's `Data`
// type isn't guaranteed to share a constructor across every KMP target. Returns null on
// platforms where this isn't implemented yet.
expect suspend fun exportMarkedUpPdf(storagePath: String, pages: List<ImageBitmap>): String?
