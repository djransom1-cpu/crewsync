package com.example.crewsync

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import com.example.crewsync.util.initializeFirebase
import kotlinx.browser.document
import kotlinx.browser.window

external fun onSkikoInit(callback: () -> Unit)

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    js("""
        if (typeof globalThis.os === 'undefined') {
            globalThis.os = { tmpdir: function() { return '/tmp'; } };
        } else if (typeof globalThis.os.tmpdir === 'undefined') {
            globalThis.os.tmpdir = function() { return '/tmp'; };
        }
    """)

    val launchApp = {
        fun mount() {
            val body = document.body ?: return
            try {
                initializeFirebase()
            } catch (_: Throwable) {
            }
            ComposeViewport(body) {
                App()
            }
        }

        if (document.body != null) {
            mount()
        } else {
            window.addEventListener("DOMContentLoaded", { mount() })
        }
    }

    try {
        val skikoInit = window.asDynamic().onSkikoInit
        if (skikoInit != null) {
            onSkikoInit {
                launchApp()
            }
        } else {
            launchApp()
        }
    } catch (_: Throwable) {
        launchApp()
    }
}
