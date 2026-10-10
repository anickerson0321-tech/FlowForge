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
 * lot of memory, so it is loaded once when a dictation surface (bubble or Try-it box) opens and
 * kept while any surface holds it; it is freed [IDLE_RELEASE_MS] after the last one closes.
 * All recognizer work runs on one dedicated thread, in submission order.
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

    private val idleRelease = Runnable { if (holders.isEmpty()) worker.execute { unload() } }

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
        else -> LocalModelStatus.READY
    }

    /** A surface that may dictate soon. Main thread. */
    fun hold(owner: Any) {
        holders += owner
        main.removeCallbacks(idleRelease)
    }

    fun unhold(owner: Any) {
        holders -= owner
        if (holders.isEmpty()) {
            main.removeCallbacks(idleRelease)
            main.postDelayed(idleRelease, IDLE_RELEASE_MS)
        }
    }

    /** Starts loading [model] in the background so the first dictation doesn't wait for it. */
    fun preload(context: Context, model: SpeechModel) {
        val dir = installer(context).modelDir(model)
        worker.execute { runCatching { ensureLoaded(model, dir) } }
    }

    /**
     * Runs [work] with the loaded recognizer on the recognizer thread, loading [model] first if
     * needed. [then] receives the result on the main thread.
     */
    fun <T> run(context: Context, model: SpeechModel, work: (LocalTranscriber) -> T, then: (Result<T>) -> Unit) {
        val dir = installer(context).modelDir(model)
        worker.execute {
            val r = runCatching { work(ensureLoaded(model, dir)) }
            r.exceptionOrNull()?.let { Log.w(TAG, "offline recognition failed", it) }
            main.post { then(r) }
        }
    }

    /** Frees the model now (e.g. after it was deleted or replaced). */
    fun unloadNow() = worker.execute { unload() }

    private fun ensureLoaded(model: SpeechModel, dir: File): LocalTranscriber {
        loaded?.let { if (it.model.id == model.id) return it }
        unload()
        val started = SystemClock.elapsedRealtime()
        val threads = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 4)
        val t = try {
            LocalTranscriber.load(model, dir, threads)
        } catch (e: Throwable) {
            // UnsatisfiedLinkError, OutOfMemoryError or a damaged model: report, don't crash.
            lastError = e.message ?: e.javaClass.simpleName
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
