@file:JvmName("BusinessCardScannerAndroid")

package com.djransom.crewsync.util

import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

actual suspend fun recognizeTextInImage(platformFile: Any): String {
    val context = appContext ?: return ""
    val uri = platformFile as Uri
    val image = InputImage.fromFilePath(context, uri)
    val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    return suspendCancellableCoroutine { cont ->
        recognizer.process(image)
            .addOnSuccessListener { visionText -> cont.resume(visionText.text) }
            .addOnFailureListener { e -> cont.resumeWithException(e) }
    }
}
