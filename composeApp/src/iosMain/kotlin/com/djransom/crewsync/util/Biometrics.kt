package com.djransom.crewsync.util

import com.djransom.crewsync.i18n.t
import androidx.compose.runtime.Composable
import platform.LocalAuthentication.LAContext
import platform.LocalAuthentication.LAPolicyDeviceOwnerAuthenticationWithBiometrics

@Composable
actual fun rememberBiometricAuthenticator(onAuthenticated: () -> Unit, onError: (String) -> Unit): () -> Unit {
    return {
        val context = LAContext()
        context.evaluatePolicy(
            LAPolicyDeviceOwnerAuthenticationWithBiometrics,
            localizedReason = t("Log in to Crewsync")
        ) { success, authError ->
            if (success) {
                onAuthenticated()
            } else {
                onError(authError?.localizedDescription ?: t("Authentication failed"))
            }
        }
    }
}
