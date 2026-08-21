package com.djransom.crewsync.util

import androidx.compose.runtime.Composable

@Composable
actual fun rememberBiometricAuthenticator(onAuthenticated: () -> Unit, onError: (String) -> Unit): () -> Unit {
    return { onError("Biometrics not supported in browser.") }
}

@Composable
actual fun rememberContactPickerLauncher(onContactPicked: (PickedContact) -> Unit): () -> Unit {
    return { /* Not supported in browser */ }
}

@Composable
actual fun rememberFilePickerLauncher(onFilePicked: (PickedFile) -> Unit): () -> Unit {
    return { /* Web file picker logic placeholder */ }
}

@Composable
actual fun rememberCameraLauncher(onFilePicked: (PickedFile) -> Unit): () -> Unit {
    return { /* Web camera logic placeholder */ }
}

actual suspend fun uploadFile(path: String, platformFile: Any): String = ""

actual suspend fun recognizeTextInImage(platformFile: Any): String = ""
