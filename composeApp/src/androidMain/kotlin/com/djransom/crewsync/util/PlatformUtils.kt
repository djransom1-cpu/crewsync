package com.djransom.crewsync.util

actual fun getAppVersion(): String {
    return try {
        val context = ContextHolder.context ?: return "1.0"
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        packageInfo.versionName ?: "1.0"
    } catch (e: Exception) {
        "1.0"
    }
}
