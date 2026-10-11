package com.privatevoice.app.notifications

import android.content.ComponentName
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.privatevoice.app.PrivateVoiceApp

/**
 * Receives message notifications through Android's supported notification
 * access. Message content is handed to the in-memory repository only.
 */
class PrivateVoiceListenerService : NotificationListenerService() {

    private val graph get() = PrivateVoiceApp.graph(this)

    override fun onListenerConnected() {
        graph.listenerConnected.value = true
        // Messages already on screen when we connect are not read automatically.
        runCatching {
            activeNotifications?.forEach { sbn ->
                if (sbn.packageName == packageName) return@forEach
                val snap = AndroidNotificationExtractor.extract(sbn)
                graph.reader.primeExisting(snap.key, graph.parser.parse(snap))
            }
        }
    }

    override fun onListenerDisconnected() {
        graph.listenerConnected.value = false
        graph.reader.onListenerDisconnected()
        // Ask the system to bind us again (e.g. after the process was trimmed).
        runCatching { requestRebind(ComponentName(this, PrivateVoiceListenerService::class.java)) }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null || sbn.packageName == packageName) return
        try {
            val settings = graph.settings.current
            val snap = AndroidNotificationExtractor.extract(sbn)
            val supported = SupportedApps.DEFAULT.keys + settings.extraApps
            if (!graph.parser.isCandidate(snap, supported, settings.readAnyMessagingApp)) return
            val parsed = graph.parser.parse(snap)
            if (parsed.isEmpty()) return
            graph.reader.onMessagesPosted(snap.key, snap.packageName, graph.appLabel(snap.packageName), parsed)
        } catch (e: Exception) {
            // Malformed notification extras: ignore. Never log notification contents.
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn == null || sbn.packageName == packageName) return
        graph.reader.onNotificationRemoved(sbn.key)
    }

    companion object {
        fun isEnabled(context: Context): Boolean =
            context.getSystemService(android.app.NotificationManager::class.java)
                .isNotificationListenerAccessGranted(ComponentName(context, PrivateVoiceListenerService::class.java))
    }
}
