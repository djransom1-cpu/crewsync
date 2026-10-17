package com.djransom.crewsync.util

import com.djransom.crewsync.i18n.t
import androidx.compose.runtime.Composable

@Composable
actual fun rememberBiometricAuthenticator(onAuthenticated: () -> Unit, onError: (String) -> Unit): () -> Unit {
    return {
        // Biometrics not typically supported on Desktop yet in this KMP setup
        onError(t("Biometrics not supported on this platform."))
    }
}
