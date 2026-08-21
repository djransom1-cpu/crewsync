package com.djransom.crewsync.util

import androidx.compose.runtime.Composable
import platform.LocalAuthentication.LAContext
import platform.LocalAuthentication.LAPolicyDeviceOwnerAuthenticationWithBiometrics

@Composable
actual fun rememberBiometricAuthenticator(onAuthenticated: () -> Unit, onError: (String) -> Unit): () -> Unit {
    return {
        val context = LAContext()
        context.evaluatePolicy(
            LAPolicyDeviceOwnerAuthenticationWithBiometrics,
            localizedReason = "Log in to Crewsync"
        ) { success, authError ->
            if (success) {
                onAuthenticated()
            } else {
                onError(authError?.localizedDescription ?: "Authentication failed")
            }
        }
    }
}
