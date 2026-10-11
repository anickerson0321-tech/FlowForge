package com.privatevoice.app.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import com.privatevoice.app.PrivateVoiceApp
import com.privatevoice.app.R
import com.privatevoice.app.audio.PlaybackState
import com.privatevoice.app.messaging.PendingState
import com.privatevoice.app.reader.ReadResult
import com.privatevoice.app.ui.MainActivity

/**
 * PrivateVoice's own notification: "Read aloud" when a message is waiting and
 * Stop / Pause / Repeat while reading. It never shows message text; the sender
 * name is hidden on the lock screen via a public version.
 */
class ControlsNotifier(private val context: Context) {
    private val nm = context.getSystemService(NotificationManager::class.java)

    init {
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Message reader controls", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Read-aloud controls. Never shows message text."
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                setSound(null, null)
                enableVibration(false)
            },
        )
    }

    fun canPost(): Boolean = nm.areNotificationsEnabled()

    /** Updates or removes the controls notification for the current state. */
    fun update(pending: PendingState, playback: PlaybackState, enabled: Boolean) {
        if (!canPost()) return
        val playing = playback !is PlaybackState.Idle
        if (playing) { nm.notify(ID, build(pending, playback)); return }
        if (!enabled || pending.count == 0) { nm.cancel(ID); return }
        nm.notify(ID, build(pending, playback))
    }

    /** Notification used while the playback foreground service runs. */
    fun build(pending: PendingState, playback: PlaybackState): Notification {
        val (title, actions) = when (playback) {
            is PlaybackState.Speaking -> "Reading message from ${playback.label}" to listOf(ACTION_PAUSE to "Pause", ACTION_STOP to "Stop")
            is PlaybackState.Preparing -> "Preparing voice…" to listOf(ACTION_STOP to "Stop")
            is PlaybackState.Paused -> "Paused" to listOf(ACTION_RESUME to "Resume", ACTION_STOP to "Stop")
            PlaybackState.Idle -> {
                val who = pending.latestSender ?: "someone"
                val more = if (pending.count > 1) " (+${pending.count - 1} more)" else ""
                "Message from $who$more" to listOf(ACTION_READ to "Read aloud", ACTION_REPEAT to "Repeat", ACTION_CLEAR to "Dismiss")
            }
        }
        val publicVersion = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_voice)
            .setContentTitle(if (playback is PlaybackState.Idle) "New message ready to read" else "Reading a message")
            .build()
        val b = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_voice)
            .setContentTitle(title)
            .setContentText(if (playback is PlaybackState.Idle) "Tap Read aloud to listen privately" else "PrivateVoice · on-device voice")
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(if (playback is PlaybackState.Idle) Notification.CATEGORY_REMINDER else Notification.CATEGORY_TRANSPORT)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .setContentIntent(activityIntent())
            .setDeleteIntent(action(ACTION_DISMISSED))
            .setOngoing(playback !is PlaybackState.Idle)
        actions.forEach { (a, label) -> b.addAction(Notification.Action.Builder(null as Icon?, label, action(a)).build()) }
        return b.build()
    }

    private fun activityIntent(): PendingIntent = PendingIntent.getActivity(
        context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun action(name: String): PendingIntent = PendingIntent.getBroadcast(
        context, name.hashCode(), Intent(context, ReaderActionReceiver::class.java).setAction(name),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun cancel() = nm.cancel(ID)

    companion object {
        const val CHANNEL = "reader_controls"
        const val ID = 1001
        const val ACTION_READ = "com.privatevoice.app.action.READ_LATEST"
        const val ACTION_STOP = "com.privatevoice.app.action.STOP"
        const val ACTION_PAUSE = "com.privatevoice.app.action.PAUSE"
        const val ACTION_RESUME = "com.privatevoice.app.action.RESUME"
        const val ACTION_REPEAT = "com.privatevoice.app.action.REPEAT"
        const val ACTION_CLEAR = "com.privatevoice.app.action.CLEAR"
        const val ACTION_DISMISSED = "com.privatevoice.app.action.DISMISSED"
    }
}

/** Handles taps on the controls notification. Not exported; only our PendingIntents reach it. */
class ReaderActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val g = PrivateVoiceApp.graph(context)
        when (intent.action) {
            ControlsNotifier.ACTION_READ -> {
                // A notification action is a user interaction, so Android allows the playback service to start.
                g.startPlaybackService()
                if (g.reader.readLatest() == ReadResult.NOTHING_PENDING) g.toast("No messages waiting")
            }
            ControlsNotifier.ACTION_STOP -> g.reader.stop()
            ControlsNotifier.ACTION_PAUSE -> g.reader.pause()
            ControlsNotifier.ACTION_RESUME -> g.reader.resume()
            ControlsNotifier.ACTION_REPEAT -> { g.startPlaybackService(); if (!g.reader.repeat()) g.toast("Nothing to repeat") }
            ControlsNotifier.ACTION_CLEAR -> g.reader.clearTemporary()
            ControlsNotifier.ACTION_DISMISSED -> {}
        }
    }
}
