package com.djransom.crewsync.util

import androidx.compose.runtime.Composable

@Composable
actual fun rememberContactPickerLauncher(onContactPicked: (PickedContact) -> Unit): () -> Unit {
    // On iOS, this is usually handled by presenting CNContactPickerViewController 
    // from the root UIViewController.
    return {
        println("IOS: Open Contact Picker (Requires Native Swift UI Integration)")
    }
}
