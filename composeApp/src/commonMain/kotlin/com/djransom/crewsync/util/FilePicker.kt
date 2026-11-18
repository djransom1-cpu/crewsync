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

/**
 * The text of a file the person picked (with [rememberFilePickerLauncher]), read as UTF-8 - for
 * a CSV import. Throws if it can't be read.
 */
expect suspend fun readPickedFileText(platformFile: Any): String
