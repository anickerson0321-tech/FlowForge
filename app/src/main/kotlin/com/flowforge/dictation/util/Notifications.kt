package com.flowforge.dictation.util

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import com.flowforge.dictation.R
import com.flowforge.dictation.overlay.DictationService
import com.flowforge.dictation.ui.MainActivity

object Notifications {
    const val CHANNEL_DICTATION = "dictation"
    const val CHANNEL_ALERTS = "alerts"
    const val ID_SERVICE = 1001
    const val ID_ALERT = 1002
    const val ID_DOWNLOAD = 1003

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_DICTATION, context.getString(R.string.notif_channel_dictation), NotificationManager.IMPORTANCE_LOW).apply {
                description = context.getString(R.string.notif_channel_dictation_desc)
                setShowBadge(false)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERTS, context.getString(R.string.notif_channel_alerts), NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = context.getString(R.string.notif_channel_alerts_desc)
            },
        )
    }

    private fun openAppIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun serviceAction(context: Context, action: String, requestCode: Int): PendingIntent = PendingIntent.getService(
        context, requestCode, Intent(context, DictationService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** The ongoing notification Android requires for the microphone foreground service. */
    fun serviceNotification(context: Context, recording: Boolean): Notification {
        val title = if (recording) "Listening… tap the bubble to finish" else "Dictation bubble is ready"
        val text = if (recording) {
            "FlowForge is recording your voice right now."
        } else {
            "The microphone is off until you tap the bubble."
        }
        val builder = Notification.Builder(context, CHANNEL_DICTATION)
            .setSmallIcon(R.drawable.ic_flowforge_mark)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openAppIntent(context))
        if (recording) {
            builder.addAction(
                Notification.Action.Builder(Icon.createWithResource(context, R.drawable.ic_mic), "Stop", serviceAction(context, DictationService.ACTION_STOP_RECORDING, 1)).build(),
            )
            builder.addAction(
                Notification.Action.Builder(Icon.createWithResource(context, R.drawable.ic_mic), "Cancel", serviceAction(context, DictationService.ACTION_CANCEL_RECORDING, 2)).build(),
            )
        } else {
            builder.addAction(
                Notification.Action.Builder(Icon.createWithResource(context, R.drawable.ic_mic), "Hide bubble", serviceAction(context, DictationService.ACTION_HIDE, 3)).build(),
            )
        }
        return builder.build()
    }

    fun alert(context: Context, title: String, text: String) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (!nm.areNotificationsEnabled()) return
        val n = Notification.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_flowforge_mark)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(context))
            .build()
        nm.notify(ID_ALERT, n)
    }
}
