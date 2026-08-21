package com.djransom.crewsync.util

import androidx.compose.runtime.Composable

@Composable
expect fun rememberFilePickerLauncher(onFilePicked: (PickedFile) -> Unit): () -> Unit

@Composable
expect fun rememberCameraLauncher(onFilePicked: (PickedFile) -> Unit): () -> Unit

data class PickedFile(
    val name: String,
    val platformFile: Any
)

expect suspend fun uploadFile(path: String, platformFile: Any): String
