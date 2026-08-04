package com.example.crewsync.util

import platform.Foundation.NSBundle

actual fun getAppVersion(): String {
    val info = NSBundle.mainBundle.infoDictionary
    val version = info?.get("CFBundleShortVersionString") as? String
    val build = info?.get("CFBundleVersion") as? String
    return if (version != null) "$version ($build)" else "1.3"
}
