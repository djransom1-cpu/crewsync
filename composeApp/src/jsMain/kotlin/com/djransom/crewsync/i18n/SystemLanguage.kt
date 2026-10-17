package com.djransom.crewsync.i18n

import kotlinx.browser.window

actual fun systemLanguageCode(): String = window.navigator.language.take(2)
