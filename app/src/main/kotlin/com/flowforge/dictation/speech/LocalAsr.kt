package com.flowforge.dictation.speech

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.flowforge.core.models.ModelCatalog
import com.flowforge.core.models.ModelInstaller
import com.flowforge.core.models.SpeechModel
import com.flowforge.core.recognition.LocalModelStatus
import com.flowforge.dictation.data.SettingsStore
import java.io.File
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Owns the loaded offline recognizer for the whole process. Loading a model takes seconds and a
 * lot of memory, so the bubble holds it loaded while it is shown; otherwise (e.g. after a test in
 * the app) it is freed [IDLE_RELEASE_MS] after its last use. All recognizer work runs on one
 * dedicated thread, in submission order. A model that failed to load is not retried until it is
 * re-downloaded, switched or deleted ([unloadNow]), so dictation falls back to another engine.
 */
object LocalAsr {
    private const val TAG = "FlowForgeLocalAsr"
    private const val IDLE_RELEASE_MS = 60_000L

    private val main = Handler(Looper.getMainLooper())
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "flowforge-asr").apply { isDaemon = true }
    }
    private val holders = HashSet<Any>()

    // Accessed only on the worker thread.
    private var loaded: LocalTranscriber? = null

    @Volatile var lastLoadMillis: Long = -1
        private set
    @Volatile var lastError: String? = null
        private set
    /** Id of the model whose last load failed, or null. */
    @Volatile private var failedModelId: String? = null
    /** Dictations currently using the recognizer (main thread). */
    private var inUse = 0

    private val idleRelease = Runnable { if (holders.isEmpty() && inUse == 0) worker.execute { unload() } }

    fun modelsRoot(context: Context) = File(context.noBackupFilesDir, "models")
    fun installer(context: Context) = ModelInstaller(modelsRoot(context))

    fun selectedModel(settings: SettingsStore): SpeechModel =
        ModelCatalog.byId(settings.dictation.localModelId) ?: ModelCatalog.default

    fun deviceSupported(): Boolean = Build.SUPPORTED_64_BIT_ABIS.contains("arm64-v8a")

    /** The bundled models are English-only. A blank language means the phone's language. */
    fun languageSupported(tag: String): Boolean {
        val lang = (tag.ifBlank { Locale.getDefault().toLanguageTag() }).substringBefore('-').substringBefore('_')
        return lang.equals("en", ignoreCase = true)
    }

    /** Cheap (reads a small manifest); fine to call before each dictation. */
    fun status(context: Context, settings: SettingsStore): LocalModelStatus = when {
        !deviceSupported() -> LocalModelStatus.DEVICE_UNSUPPORTED
        !languageSupported(settings.dictation.languageTag) -> LocalModelStatus.LANGUAGE_UNSUPPORTED
        !installer(context).isInstalled(selectedModel(settings)) -> LocalModelStatus.NOT_DOWNLOADED
        failedModelId == selectedModel(settings).id -> LocalModelStatus.LOAD_FAILED
        else -> LocalModelStatus.READY
    }

    /** A surface that may dictate soon. Main thread. */
    fun hold(owner: Any) {
        holders += owner
        main.removeCallbacks(idleRelease)
    }

    fun unhold(owner: Any) {
        holders -= owner
        scheduleIdleRelease()
    }

    private fun scheduleIdleRelease() {
        if (holders.isNotEmpty() || inUse > 0) return
        main.removeCallbacks(idleRelease)
        main.postDelayed(idleRelease, IDLE_RELEASE_MS)
    }

    /** A dictation starts/stops using the recognizer (main thread). */
    fun beginUse() {
        inUse++
        main.removeCallbacks(idleRelease)
    }

    fun endUse() {
        if (inUse > 0) inUse--
        scheduleIdleRelease()
    }

    /** Android asked the app to free memory: drop the model unless a dictation is using it. */
    fun trimMemory() {
        if (inUse == 0) worker.execute { unload() }
    }

    /**
     * Starts loading [model] in the background so the first dictation doesn't wait for it.
     * [done] (main thread) receives the load error, or null on success.
     */
    fun preload(context: Context, model: SpeechModel, done: ((Throwable?) -> Unit)? = null) {
        val dir = installer(context).modelDir(model)
        worker.execute {
            val error = runCatching { ensureLoaded(model, dir) }.exceptionOrNull()
            main.post {
                done?.invoke(error)
                scheduleIdleRelease()
            }
        }
    }

    /**
     * Runs [work] with the loaded recognizer on the recognizer thread, loading [model] first if
     * needed. If [skip] is true by the time the job runs (its dictation was cancelled or failed),
     * nothing is loaded or run and [then] gets a [SkippedException]. [then] runs on the main thread.
     */
    fun <T> run(context: Context, model: SpeechModel, skip: () -> Boolean, work: (LocalTranscriber) -> T, then: (Result<T>) -> Unit) {
        val dir = installer(context).modelDir(model)
        worker.execute {
            val r = if (skip()) Result.failure(SkippedException()) else runCatching { work(ensureLoaded(model, dir)) }
            r.exceptionOrNull()?.takeIf { it !is SkippedException }?.let { Log.w(TAG, "offline recognition failed", it) }
            main.post {
                then(r)
                scheduleIdleRelease()
            }
        }
    }

    class SkippedException : Exception("skipped")

    /** Frees the model now and forgets load failures (after a download, switch or delete). */
    fun unloadNow() {
        failedModelId = null
        lastError = null
        worker.execute { unload() }
    }

    private fun ensureLoaded(model: SpeechModel, dir: File): LocalTranscriber {
        loaded?.let { if (it.model.id == model.id) return it }
        if (failedModelId == model.id) throw IllegalStateException("The offline model couldn't be loaded (${lastError ?: "unknown error"}).")
        unload()
        val started = SystemClock.elapsedRealtime()
        val threads = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 4)
        val t = try {
            LocalTranscriber.load(model, dir, threads)
        } catch (e: Throwable) {
            // UnsatisfiedLinkError, OutOfMemoryError or a damaged model: report, don't crash.
            lastError = e.message ?: e.javaClass.simpleName
            failedModelId = model.id
            throw IllegalStateException("The offline model couldn't be loaded (${e.javaClass.simpleName}).", e)
        }
        lastLoadMillis = SystemClock.elapsedRealtime() - started
        lastError = null
        Log.i(TAG, "loaded ${model.id} in $lastLoadMillis ms with $threads threads")
        loaded = t
        return t
    }

    private fun unload() {
        loaded?.let {
            runCatching { it.release() }
            Log.i(TAG, "released ${it.model.id}")
        }
        loaded = null
    }
}
