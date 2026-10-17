package com.djransom.crewsync.util

import com.djransom.crewsync.i18n.t
import androidx.compose.runtime.Composable

@Composable
actual fun rememberBiometricAuthenticator(onAuthenticated: () -> Unit, onError: (String) -> Unit): () -> Unit {
    return { onError(t("Biometrics not supported in browser.")) }
}

@Composable
actual fun rememberContactPickerLauncher(onContactPicked: (PickedContact) -> Unit): () -> Unit {
    return { /* Not supported in browser */ }
}
