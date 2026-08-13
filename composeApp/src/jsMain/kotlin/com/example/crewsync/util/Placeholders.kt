package com.example.crewsync.util

import androidx.compose.runtime.Composable

@Composable
actual fun rememberBiometricAuthenticator(onAuthenticated: () -> Unit, onError: (String) -> Unit): () -> Unit {
    return { onError("Biometrics not supported in browser.") }
}

@Composable
actual fun rememberContactPickerLauncher(onContactPicked: (PickedContact) -> Unit): () -> Unit {
    return { /* Not supported in browser */ }
}
