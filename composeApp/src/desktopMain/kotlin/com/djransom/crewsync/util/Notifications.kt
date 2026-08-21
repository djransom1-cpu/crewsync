package com.djransom.crewsync.util

import java.awt.Image
import java.awt.SystemTray
import java.awt.Toolkit
import java.awt.TrayIcon
import java.awt.TrayIcon.MessageType

private var trayIcon: TrayIcon? = null

private fun getOrCreateTrayIcon(): TrayIcon? {
    if (!SystemTray.isSupported()) return null
    if (trayIcon == null) {
        try {
            val systemTray = SystemTray.getSystemTray()
            val image: Image = Toolkit.getDefaultToolkit().createImage(ByteArray(0))
            val icon = TrayIcon(image, "Crewsync")
            icon.isImageAutoSize = true
            systemTray.add(icon)
            trayIcon = icon
        } catch (_: Exception) {}
    }
    return trayIcon
}

actual fun notifyTaskUpdate(title: String, message: String) {
    try {
        val icon = getOrCreateTrayIcon()
        if (icon != null) {
            icon.displayMessage(title, message, MessageType.INFO)
        } else {
            println("NOTIFICATION: $title - $message")
        }
    } catch (e: Exception) {
        println("NOTIFICATION: $title - $message")
    }
}

actual fun notifyChatMessage(projectId: String, senderName: String, message: String) {
    try {
        val icon = getOrCreateTrayIcon()
        if (icon != null) {
            icon.displayMessage("Crew Chat - $senderName", message, MessageType.INFO)
        } else {
            println("CHAT NOTIFICATION: [$projectId] $senderName: $message")
        }
    } catch (e: Exception) {
        println("CHAT NOTIFICATION: [$projectId] $senderName: $message")
    }
}
