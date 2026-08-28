package com.djransom.crewsync.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.djransom.crewsync.data.model.User
import com.djransom.crewsync.util.rememberBiometricAuthenticator
import com.djransom.crewsync.util.rememberSettings
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.auth.auth
import dev.gitlive.firebase.firestore.firestore
import kotlinx.coroutines.launch

// Firebase's JVM/desktop Auth client surfaces raw REST error bodies (full HTTP response JSON)
// as the exception message, rather than a clean error code the way the native Android/iOS SDKs
// do - shown as-is, it looks like a crash rather than "wrong password". This maps the known
// error codes (still present as a substring within that raw body) to plain text instead.
private fun friendlyAuthError(e: Exception, isRegistering: Boolean): String {
    val raw = e.message ?: ""
    return when {
        raw.contains("INVALID_LOGIN_CREDENTIALS") || raw.contains("INVALID_PASSWORD") || raw.contains("EMAIL_NOT_FOUND") ->
            "Incorrect email or password. If you don't have an account yet, use \"Don't have an account? Sign Up\" below."
        raw.contains("EMAIL_EXISTS") ->
            "An account with this email already exists - try logging in instead."
        raw.contains("WEAK_PASSWORD") ->
            "That password is too weak - please use at least 6 characters."
        raw.contains("INVALID_EMAIL") ->
            "That doesn't look like a valid email address."
        raw.contains("USER_DISABLED") ->
            "This account has been disabled. Contact an admin for help."
        raw.contains("TOO_MANY_ATTEMPTS_TRY_LATER") ->
            "Too many failed attempts. Please wait a bit and try again."
        else -> if (isRegistering) "Registration failed. Please try again." else "Login failed. Please try again."
    }
}

@Composable
fun LoginScreen(onLoginSuccess: () -> Unit) {
    val settings = rememberSettings()
    val firestore = Firebase.firestore
    
    var email by remember { mutableStateOf(settings.getString("saved_email", "")) }
    var password by remember { mutableStateOf("") }
    var rememberMe by remember { mutableStateOf(email.isNotEmpty()) }
    
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var successMessage by remember { mutableStateOf<String?>(null) }
    var isRegistering by remember { mutableStateOf(false) }
    var showForgotPasswordDialog by remember { mutableStateOf(false) }
    
    val scope = rememberCoroutineScope()
    val auth = Firebase.auth

    val biometricAuthenticator = rememberBiometricAuthenticator(
        onAuthenticated = { onLoginSuccess() },
        onError = { msg -> errorMessage = msg }
    )

    LaunchedEffect(Unit) {
        if (rememberMe && auth.currentUser != null) {
            biometricAuthenticator()
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
        Text(
            text = if (isRegistering) "Create Account" else "Crewsync",
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(32.dp))
        
        TextField(
            value = email,
            onValueChange = { 
                email = it 
                if (errorMessage != null) errorMessage = null
            },
            label = { Text("Email") },
            modifier = Modifier.fillMaxWidth(),
            enabled = !isLoading,
            singleLine = true
        )
        Spacer(modifier = Modifier.height(8.dp))
        
        TextField(
            value = password,
            onValueChange = { 
                password = it 
                if (errorMessage != null) errorMessage = null
            },
            label = { Text("Password") },
            modifier = Modifier.fillMaxWidth(),
            visualTransformation = PasswordVisualTransformation(),
            enabled = !isLoading,
            singleLine = true
        )
        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = rememberMe,
                onCheckedChange = { rememberMe = it }
            )
            Text("Remember Me")
        }

        if (errorMessage != null) {
            Text(
                text = errorMessage!!,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(modifier = Modifier.height(16.dp))
        }

        if (successMessage != null) {
            Text(
                text = successMessage!!,
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(modifier = Modifier.height(16.dp))
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        val cleanEmail = email.trim()
        val cleanPassword = password.trim()
        val isInputValid = cleanEmail.isNotEmpty() && cleanPassword.isNotEmpty()

        Button(
            onClick = { 
                scope.launch {
                    isLoading = true
                    errorMessage = null
                    try {
                        if (isRegistering) {
                            val authResult = auth.createUserWithEmailAndPassword(cleanEmail, cleanPassword)
                            val uid = authResult.user?.uid ?: ""
                            
                            // Check if a placeholder profile exists for this email
                            val inviteSnap = firestore.collection("users").document(cleanEmail).get()
                            if (inviteSnap.exists) {
                                try {
                                    val invitedUser = inviteSnap.data<User>()
                                    // Move invitation data to UID-based doc
                                    firestore.collection("users").document(uid).set(invitedUser.copy(uid = uid))
                                    // Delete the email-based doc
                                    firestore.collection("users").document(cleanEmail).delete()
                                } catch (e: Exception) {
                                    // Fallback if data format is old
                                    firestore.collection("users").document(uid).set(User(uid = uid, email = cleanEmail))
                                }
                            } else {
                                // determine role for new user
                                val usersSnap = firestore.collection("users").get()
                                val role = if (usersSnap.documents.isEmpty()) "Admin" else "Member"
                                firestore.collection("users").document(uid).set(User(uid = uid, email = cleanEmail, role = role))
                            }
                        } else {
                            auth.signInWithEmailAndPassword(cleanEmail, cleanPassword)
                        }
                        
                        if (rememberMe) {
                            settings.putString("saved_email", cleanEmail)
                        } else {
                            settings.putString("saved_email", "")
                        }
                        
                        onLoginSuccess()
                    } catch (e: Exception) {
                        errorMessage = friendlyAuthError(e, isRegistering)
                    } finally {
                        isLoading = false
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !isLoading && isInputValid
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = 2.dp
                )
            } else {
                Text(if (isRegistering) "Sign Up" else "Login")
            }
        }

        if (!isRegistering) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = { showForgotPasswordDialog = true }) {
                    Text("Forgot Password?")
                }
            }
            TextButton(onClick = { biometricAuthenticator() }) {
                Text("Login with Fingerprint")
            }
        }
        
        TextButton(
            onClick = { isRegistering = !isRegistering },
            modifier = Modifier.fillMaxWidth(),
            enabled = !isLoading
        ) {
            Text(
                if (isRegistering) "Already have an account? Login" 
                else "Don't have an account? Sign Up"
            )
        }
    }
}

    if (showForgotPasswordDialog) {
        var resetEmail by remember { mutableStateOf(email) }
        var isSending by remember { mutableStateOf(false) }
        var dialogError by remember { mutableStateOf<String?>(null) }
        
        AlertDialog(
            onDismissRequest = { if (!isSending) showForgotPasswordDialog = false },
            title = { Text("Reset Password") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Enter your email address and we'll send you a link to reset your password.")
                    
                    TextField(
                        value = resetEmail,
                        onValueChange = { resetEmail = it },
                        label = { Text("Email Address") },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isSending
                    )

                    if (dialogError != null) {
                        Text(
                            text = dialogError!!,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            isSending = true
                            dialogError = null
                            try {
                                auth.sendPasswordResetEmail(resetEmail)
                                successMessage = "Check your inbox! Reset link sent to $resetEmail"
                                showForgotPasswordDialog = false
                            } catch (e: Exception) {
                                dialogError = if (e.message?.contains("EMAIL_NOT_FOUND") == true)
                                    "No account found for that email address."
                                else
                                    "Failed to send reset email. Please try again."
                            } finally {
                                isSending = false
                            }
                        }
                    },
                    enabled = !isSending && resetEmail.contains("@")
                ) {
                    if (isSending) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    else Text("Send Reset Link")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showForgotPasswordDialog = false },
                    enabled = !isSending
                ) {
                    Text("Cancel")
                }
            }
        )
    }
}

