package com.djransom.crewsync.util

import androidx.compose.runtime.Composable
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.storage.Data
import dev.gitlive.firebase.storage.storage
import kotlinx.browser.document
import kotlinx.coroutines.suspendCancellableCoroutine
import org.khronos.webgl.Uint8Array
import org.w3c.dom.HTMLInputElement
import org.w3c.files.File
import org.w3c.files.get
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private fun launchFileInput(accept: String, capture: String?, onFilePicked: (PickedFile) -> Unit) {
    val input = document.createElement("input") as HTMLInputElement
    input.type = "file"
    input.accept = accept
    if (capture != null) input.setAttribute("capture", capture)
    input.addEventListener("change", {
        val file = input.files?.get(0)
        if (file != null) onFilePicked(PickedFile(file.name, file))
    })
    input.click()
}

@Composable
actual fun rememberFilePickerLauncher(onFilePicked: (PickedFile) -> Unit): () -> Unit {
    return { launchFileInput(accept = "*/*", capture = null, onFilePicked = onFilePicked) }
}

@Composable
actual fun rememberCameraLauncher(onFilePicked: (PickedFile) -> Unit): () -> Unit {
    // "capture" hints mobile browsers to open the camera directly instead of a general
    // picker; desktop browsers that don't support it just fall back to a normal file dialog.
    return { launchFileInput(accept = "image/*", capture = "environment", onFilePicked = onFilePicked) }
}

private suspend fun readFileAsUint8Array(file: File): Uint8Array = suspendCancellableCoroutine { cont ->
    val reader = js("new FileReader()")
    reader.onload = { _: dynamic ->
        val buffer: org.khronos.webgl.ArrayBuffer = reader.result
        cont.resume(Uint8Array(buffer))
        Unit
    }
    reader.onerror = { _: dynamic ->
        cont.resumeWithException(Exception("Failed to read file"))
        Unit
    }
    reader.readAsArrayBuffer(file)
}

actual suspend fun uploadFile(path: String, platformFile: Any): String {
    val storageRef = Firebase.storage.reference(path)
    val file = platformFile as File
    val bytes = readFileAsUint8Array(file)
    storageRef.putData(Data(bytes))
    return storageRef.getDownloadUrl()
}
