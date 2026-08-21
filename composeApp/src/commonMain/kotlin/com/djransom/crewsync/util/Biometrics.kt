package com.djransom.crewsync.util

import androidx.compose.runtime.Composable

@Composable
expect fun rememberBiometricAuthenticator(onAuthenticated: () -> Unit, onError: (String) -> Unit): () -> Unit
