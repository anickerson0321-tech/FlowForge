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
        graph.listenerService = java.lang.ref.WeakReference(this)
        // Messages already in the notification shade become available to read on demand
        // (never read automatically).
        loadExistingNotifications()
    }

    /** Adds message notifications currently in the shade to the waiting list. Returns how many were added. */
    fun loadExistingNotifications(): Int {
        var added = 0
        val settings = graph.settings.current
        val supported = SupportedApps.DEFAULT.keys + settings.extraApps
        runCatching {
            activeNotifications?.sortedBy { it.postTime }?.forEach { sbn ->
                if (sbn.packageName == packageName) return@forEach
                val snap = AndroidNotificationExtractor.extract(sbn)
                if (!graph.parser.isCandidate(snap, supported, settings.readAnyMessagingApp)) return@forEach
                val parsed = graph.parser.parse(snap)
                if (parsed.isEmpty()) return@forEach
                added += graph.reader.addExisting(snap.key, snap.packageName, graph.appLabel(snap.packageName), parsed)
            }
        }
        return added
    }

    override fun onListenerDisconnected() {
        graph.listenerConnected.value = false
        graph.listenerService = null
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
            graph.diagnostics.value = graph.diagnostics.value.let { it.copy(notificationsSeen = it.notificationsSeen + 1) }
            if (!graph.parser.isCandidate(snap, supported, settings.readAnyMessagingApp)) {
                if (snap.packageName in supported) graph.diagnostics.value = graph.diagnostics.value.copy(lastIgnoredApp = graph.appLabel(snap.packageName))
                return
            }
            val parsed = graph.parser.parse(snap)
            if (parsed.isEmpty()) return
            graph.diagnostics.value = graph.diagnostics.value.let {
                it.copy(messageNotificationsSeen = it.messageNotificationsSeen + 1, lastMessageAtMillis = System.currentTimeMillis(), lastMessageApp = graph.appLabel(snap.packageName))
            }
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
