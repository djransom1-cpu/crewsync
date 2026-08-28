package com.djransom.crewsync.util

import androidx.compose.runtime.Composable
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.storage.Data
import dev.gitlive.firebase.storage.storage
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

@Composable
actual fun rememberFilePickerLauncher(onFilePicked: (PickedFile) -> Unit): () -> Unit {
    return {
        val fileDialog = FileDialog(null as Frame?, "Select File", FileDialog.LOAD)
        fileDialog.isVisible = true
        if (fileDialog.file != null) {
            val file = File(fileDialog.directory, fileDialog.file)
            onFilePicked(PickedFile(file.name, file))
        }
    }
}

@Composable
actual fun rememberCameraLauncher(onFilePicked: (PickedFile) -> Unit): () -> Unit {
    return {
        // Camera not typically available via simple AWT, skip for now
    }
}

actual suspend fun uploadFile(path: String, platformFile: Any): String {
    val storageRef = Firebase.storage.reference(path)
    val file = platformFile as File
    storageRef.putData(Data(file.readBytes()))
    return storageRef.getDownloadUrl()
}

actual suspend fun recognizeTextInImage(platformFile: Any): String = ""
