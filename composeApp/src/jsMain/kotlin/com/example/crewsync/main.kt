package com.example.crewsync

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import com.example.crewsync.util.initializeFirebase
import kotlinx.browser.document
import kotlinx.browser.window

external fun onSkikoInit(callback: () -> Unit)

@OptIn(ExperimentalComposeUiApi::class)
fun main() {

    var mounted = false

    fun mount() {
        if (mounted) return
        val body = document.body ?: return
        mounted = true
        try {
            initializeFirebase()
        } catch (_: Throwable) {
        }
        ComposeViewport(body) {
            App()
        }
    }

    val launchApp = {
        if (document.body != null) {
            mount()
        } else {
            window.addEventListener("DOMContentLoaded", { mount() })
        }
    }

    try {
        val win = window.asDynamic()
        if (win.onSkikoInit != null && js("typeof win.onSkikoInit === 'function'")) {
            onSkikoInit { launchApp() }
        } else if (win.Module != null) {
            val mod = win.Module
            if (mod.runtimeInitialized == true || mod.calledRun == true) {
                launchApp()
            } else {
                val prevInit = mod.onRuntimeInitialized
                mod.onRuntimeInitialized = {
                    if (prevInit != null && js("typeof prevInit === 'function'")) {
                        prevInit()
                    }
                    launchApp()
                }
            }
        } else {
            // Fallback delayed launch to allow skiko.wasm async instantiation
            window.setTimeout({ launchApp() }, 100)
        }
    } catch (_: Throwable) {
        launchApp()
    }
}
