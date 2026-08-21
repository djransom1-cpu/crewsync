package com.djransom.crewsync.util

import androidx.compose.runtime.Composable

@Composable
actual fun rememberBiometricAuthenticator(onAuthenticated: () -> Unit, onError: (String) -> Unit): () -> Unit {
    return {
        // Biometrics not typically supported on Desktop yet in this KMP setup
        onError("Biometrics not supported on this platform.")
    }
}
