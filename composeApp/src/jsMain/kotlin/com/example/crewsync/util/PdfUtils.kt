package com.example.crewsync.util

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap

@Composable
actual fun rememberPdfRenderer(url: String): PdfRenderer? {
    return null // Web uses native iframe or object tag for PDF viewing
}
