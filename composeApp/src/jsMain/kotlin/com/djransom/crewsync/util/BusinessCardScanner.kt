package com.djransom.crewsync.util

import kotlinx.coroutines.suspendCancellableCoroutine
import org.w3c.files.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

// Tesseract.js is loaded as a plain global script in index.html (window.Tesseract), same
// approach as pdf.js - keeps the JS interop simple for a small, one-shot API surface.
private fun tesseract(): dynamic = js("window.Tesseract")

actual suspend fun recognizeTextInImage(platformFile: Any): String {
    val file = platformFile as File
    val result: dynamic = suspendCancellableCoroutine { cont ->
        val promise = tesseract().recognize(file, "eng")
        promise.then(
            { value: dynamic -> cont.resume(value) },
            { err: dynamic -> cont.resumeWithException(Exception(err?.toString() ?: "OCR error")) }
        )
    }
    return result.data.text as? String ?: ""
}
