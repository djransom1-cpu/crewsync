package com.djransom.crewsync.util

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap

@Composable
actual fun rememberPdfRenderer(url: String): PdfRenderer? = null

actual suspend fun exportMarkedUpPdf(storagePath: String, pages: List<ImageBitmap>): String? = null
