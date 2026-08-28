package com.djransom.crewsync.util

import platform.Foundation.NSURL
import platform.UIKit.UIApplication

actual fun openUrl(url: String) {
    val nsUrl = NSURL.URLWithString(url)
    if (nsUrl != null) {
        UIApplication.sharedApplication.openURL(nsUrl)
    }
}

// TODO: wire up UIActivityViewController for a native share sheet - falls back to opening the
// link for now.
actual fun shareFile(url: String, name: String) {
    openUrl(url)
}

actual fun downloadFile(url: String, suggestedName: String) {
    openUrl(url)
}
