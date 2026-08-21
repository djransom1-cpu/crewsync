package com.djransom.crewsync.util

import platform.Foundation.NSURL
import platform.UIKit.UIApplication

actual fun makePhoneCall(phoneNumber: String) {
    val url = NSURL.URLWithString("tel:$phoneNumber")
    if (url != null && UIApplication.sharedApplication.canOpenURL(url)) {
        UIApplication.sharedApplication.openURL(url)
    }
}
