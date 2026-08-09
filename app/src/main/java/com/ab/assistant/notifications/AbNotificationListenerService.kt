package com.ab.assistant.notifications

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class AbNotificationListenerService : NotificationListenerService() {
    override fun onListenerConnected() {
        super.onListenerConnected()
        NotificationStore.replaceAll(activeNotifications.orEmpty().map(::toSummary))
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        NotificationStore.upsert(toSummary(sbn))
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        NotificationStore.remove(sbn.key)
    }

    private fun toSummary(sbn: StatusBarNotification): NotificationSummary {
        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val appName = try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString()
        } catch (_: Exception) {
            sbn.packageName
        }
        return NotificationSummary(sbn.key, appName, title, text, sbn.postTime)
    }

    companion object {
        fun isAccessEnabled(context: Context): Boolean {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                val manager = context.getSystemService(android.app.NotificationManager::class.java)
                return manager.isNotificationListenerAccessGranted(
                    ComponentName(context, AbNotificationListenerService::class.java),
                )
            }
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners",
            ).orEmpty()
            return enabled.split(':').any { component ->
                ComponentName.unflattenFromString(component)?.packageName == context.packageName
            }
        }

        fun settingsIntent() = android.content.Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
    }
}
