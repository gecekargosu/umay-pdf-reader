package com.jarvis.mobile.services

import android.app.Notification
import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Notification listener service that captures notifications from apps
 * and broadcasts them to the lock screen for display.
 *
 * Requires the user to grant Notification Access permission.
 */
class JarvisNotificationListener : NotificationListenerService() {

    companion object {
        private const val TAG = "JarvisNotifListener"
        const val ACTION_NOTIFICATION_RECEIVED = "com.jarvis.mobile.NOTIFICATION_RECEIVED"
        const val ACTION_NOTIFICATION_REMOVED = "com.jarvis.mobile.NOTIFICATION_REMOVED"
        const val EXTRA_PACKAGE_NAME = "package_name"
        const val EXTRA_APP_NAME = "app_name"
        const val EXTRA_TITLE = "title"
        const val EXTRA_TEXT = "text"
        const val EXTRA_COUNT = "count"
        const val EXTRA_TIMESTAMP = "timestamp"

        // Supported apps we want to display
        private val SUPPORTED_APPS = setOf(
            "com.whatsapp",
            "com.whatsapp.w4b",
            "com.instagram.android",
            "com.google.android.gm",
            "com.google.android.apps.messaging",
            "com.samsung.android.messaging",
            "com.android.dialer",
            "com.google.android.dialer"
        )
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return

        val packageName = sbn.packageName ?: return

        // Only process notifications from supported apps
        if (!SUPPORTED_APPS.contains(packageName)) return

        val notification = sbn.notification ?: return
        val extras = notification.extras ?: return

        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()

        // Use the full message if available, otherwise use the short text
        val displayText = bigText ?: text

        // Count notifications from same package
        val existingCount = getNotificationsForPackage(packageName).size

        // Broadcast to lock screen (do NOT setPackage — it must reach our own app)
        val intent = Intent(ACTION_NOTIFICATION_RECEIVED).apply {
            putExtra(EXTRA_PACKAGE_NAME, packageName)
            putExtra(EXTRA_APP_NAME, getAppName(packageName))
            putExtra(EXTRA_TITLE, title)
            putExtra(EXTRA_TEXT, displayText)
            putExtra(EXTRA_COUNT, existingCount + 1)
            putExtra(EXTRA_TIMESTAMP, sbn.postTime)
        }
        sendBroadcast(intent)

        // Notification received (no logging in production)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn ?: return

        val intent = Intent(ACTION_NOTIFICATION_REMOVED).apply {
            putExtra(EXTRA_PACKAGE_NAME, sbn.packageName)
        }
        sendBroadcast(intent)
    }

    private fun getNotificationsForPackage(packageName: String): List<StatusBarNotification> {
        return activeNotifications?.filter { it.packageName == packageName } ?: emptyList()
    }

    private fun getAppName(packageName: String): String {
        return when (packageName) {
            "com.whatsapp", "com.whatsapp.w4b" -> "WhatsApp"
            "com.instagram.android" -> "Instagram"
            "com.google.android.gm" -> "Gmail"
            "com.google.android.apps.messaging", "com.samsung.android.messaging" -> "SMS"
            "com.android.dialer", "com.google.android.dialer" -> "Telefon"
            else -> packageName
        }
    }
}
