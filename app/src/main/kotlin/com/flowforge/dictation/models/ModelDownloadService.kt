package com.flowforge.dictation.models

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import com.flowforge.core.models.ModelCatalog
import com.flowforge.core.models.ModelInstallCancelled
import com.flowforge.core.models.SpeechModel
import com.flowforge.dictation.R
import com.flowforge.dictation.graph
import com.flowforge.dictation.speech.LocalAsr
import com.flowforge.dictation.ui.MainActivity
import com.flowforge.dictation.util.Notifications

/** Download state shown in Speech settings. Main thread only. */
object ModelDownloads {
    sealed class State {
        object Idle : State()
        data class Running(val modelId: String, val read: Long, val total: Long) : State() {
            val percent: Int get() = if (total > 0) (read * 100 / total).toInt().coerceIn(0, 100) else 0
        }
        data class Failed(val modelId: String, val message: String) : State()
        data class Done(val modelId: String) : State()
    }

    var state: State = State.Idle
        private set
    private val listeners = LinkedHashSet<(State) -> Unit>()

    fun observe(listener: (State) -> Unit) {
        listeners += listener
        listener(state)
    }

    fun unobserve(listener: (State) -> Unit) {
        listeners -= listener
    }

    internal fun set(s: State) {
        state = s
        listeners.toList().forEach { it(s) }
    }

    val isRunning: Boolean get() = state is State.Running
}

/**
 * Downloads, verifies and installs an offline speech model in the foreground (dataSync), with a
 * progress notification and a Cancel action. Started only from a button in Speech settings.
 */
class ModelDownloadService : Service() {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var cancelled = false
    private var worker: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                cancelled = true
                if (worker == null) stopSelf()
                return START_NOT_STICKY
            }
            ACTION_DOWNLOAD -> {
                val model = ModelCatalog.byId(intent.getStringExtra(EXTRA_MODEL))
                if (model == null || worker != null) {
                    if (worker == null) stopSelf()
                    return START_NOT_STICKY
                }
                try {
                    startForeground(Notifications.ID_DOWNLOAD, progressNotification(model, 0, model.downloadBytes), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                } catch (e: RuntimeException) {
                    // ForegroundServiceStartNotAllowedException and friends: report instead of crashing.
                    Log.w(TAG, "startForeground refused", e)
                    ModelDownloads.set(ModelDownloads.State.Failed(model.id, "Android didn't allow the download to start. Open FlowForge and try again."))
                    stopSelf()
                    return START_NOT_STICKY
                }
                start(model)
            }
            else -> if (worker == null) stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun start(model: SpeechModel) {
        cancelled = false
        ModelDownloads.set(ModelDownloads.State.Running(model.id, 0, model.downloadBytes))
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FlowForge:model-download")
            .apply { acquire(60 * 60 * 1000L) }
        val nm = getSystemService(NotificationManager::class.java)
        worker = Thread({
            var lastUi = 0L
            val result = runCatching {
                LocalAsr.installer(this).install(
                    model,
                    progress = { read, total ->
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastUi >= 500 || read >= total) {
                            lastUi = now
                            main.post { if (!cancelled) ModelDownloads.set(ModelDownloads.State.Running(model.id, read, total)) }
                            nm.notify(Notifications.ID_DOWNLOAD, progressNotification(model, read, total))
                        }
                    },
                    cancelled = { cancelled },
                )
            }
            main.post { finish(model, result) }
        }, "flowforge-model-download").apply { start() }
    }

    private fun finish(model: SpeechModel, result: Result<Unit>) {
        worker = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        val error = result.exceptionOrNull()
        when {
            error == null -> {
                // The model just downloaded becomes the offline model; the engine choice is left alone.
                graph.settings.updateDictation { it.copy(localModelId = model.id) }
                LocalAsr.unloadNow()
                ModelDownloads.set(ModelDownloads.State.Done(model.id))
                Notifications.alert(this, "Offline speech model ready", "${model.label} is installed. Dictation now works without internet.")
            }
            error is ModelInstallCancelled -> ModelDownloads.set(ModelDownloads.State.Idle)
            else -> {
                Log.w(TAG, "model install failed", error)
                val message = error.message ?: "The download failed."
                ModelDownloads.set(ModelDownloads.State.Failed(model.id, message))
                Notifications.alert(this, "Model download failed", "$message Open Speech settings to try again.")
            }
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        // Android 15+ limits dataSync time and requires the service to stop within seconds. The
        // worker notices the cancel flag and discards the partial download on its own.
        cancelled = true
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        cancelled = true
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    private fun progressNotification(model: SpeechModel, read: Long, total: Long): Notification {
        val percent = if (total > 0) (read * 100 / total).toInt().coerceIn(0, 100) else 0
        val cancel = PendingIntent.getService(
            this, 10, Intent(this, ModelDownloadService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val open = PendingIntent.getActivity(
            this, 11, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, Notifications.CHANNEL_DICTATION)
            .setSmallIcon(R.drawable.ic_flowforge_mark)
            .setContentTitle("Downloading offline speech model")
            .setContentText("${read / 1_000_000} of ${total / 1_000_000} MB · $percent%")
            .setProgress(100, percent, total <= 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Cancel", cancel).build())
            .build()
    }

    companion object {
        private const val TAG = "FlowForgeModelDownload"
        const val ACTION_DOWNLOAD = "com.flowforge.dictation.action.DOWNLOAD_MODEL"
        const val ACTION_CANCEL = "com.flowforge.dictation.action.CANCEL_MODEL_DOWNLOAD"
        const val EXTRA_MODEL = "model"

        fun download(context: Context, model: SpeechModel) {
            context.startForegroundService(
                Intent(context, ModelDownloadService::class.java).setAction(ACTION_DOWNLOAD).putExtra(EXTRA_MODEL, model.id),
            )
        }

        fun cancel(context: Context) {
            context.startService(Intent(context, ModelDownloadService::class.java).setAction(ACTION_CANCEL))
        }
    }
}
