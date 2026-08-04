package com.example.crewsync.util

import android.net.Uri
import android.provider.OpenableColumns
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.storage.Data
import dev.gitlive.firebase.storage.storage
import java.io.File

private var appContext: Context? = null

@Composable
actual fun rememberFilePickerLauncher(onFilePicked: (PickedFile) -> Unit): () -> Unit {
    val context = LocalContext.current.applicationContext
    appContext = context
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            val name = getFileName(context, it) ?: "unnamed_file"
            onFilePicked(PickedFile(name, it))
        }
    }
    return { launcher.launch("*/*") }
}

@Composable
actual fun rememberCameraLauncher(onFilePicked: (PickedFile) -> Unit): () -> Unit {
    val context = LocalContext.current
    appContext = context.applicationContext
    
    var tempUri by remember { mutableStateOf<Uri?>(null) }
    
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success && tempUri != null) {
            onFilePicked(PickedFile("photo_${System.currentTimeMillis()}.jpg", tempUri!!))
        }
    }
    
    return {
        val file = File(context.cacheDir, "temp_photo.jpg")
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        tempUri = uri
        launcher.launch(uri)
    }
}

actual suspend fun uploadFile(path: String, platformFile: Any): String {
    val storageRef = Firebase.storage.reference(path)
    val uri = platformFile as Uri
    val bytes = appContext?.contentResolver?.openInputStream(uri)?.use { it.readBytes() }
        ?: throw Exception("Could not read file")
    
    // In dev.gitlive.firebase:firebase-storage:2.5.0, Data has a constructor for ByteArray on Android
    storageRef.putData(Data(bytes))
    return storageRef.getDownloadUrl()
}

private fun getFileName(context: android.content.Context, uri: Uri): String? {
    var name: String? = null
    val cursor = context.contentResolver.query(uri, null, null, null, null)
    cursor?.use {
        if (it.moveToFirst()) {
            val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex != -1) {
                name = it.getString(nameIndex)
            }
        }
    }
    return name
}
