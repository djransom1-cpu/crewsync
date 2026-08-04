package com.example.crewsync.util

import platform.Foundation.NSURL
import platform.UIKit.UIApplication

actual fun sendEmail(address: String, subject: String, body: String) {
    val urlString = "mailto:$address?subject=${subject.replace(" ", "%20")}&body=${body.replace(" ", "%20")}"
    val url = NSURL.URLWithString(urlString)
    if (url != null && UIApplication.sharedApplication.canOpenURL(url)) {
        UIApplication.sharedApplication.openURL(url)
    }
}
