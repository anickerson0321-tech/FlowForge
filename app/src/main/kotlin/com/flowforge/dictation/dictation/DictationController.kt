package com.flowforge.dictation.dictation

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.flowforge.core.cleanup.CleanupCommand
import com.flowforge.core.cleanup.CleanupMode
import com.flowforge.core.cleanup.CleanupResult
import com.flowforge.core.cleanup.InsertionContext
import com.flowforge.core.cleanup.TextCleaner
import com.flowforge.core.cloud.AiCleanup
import com.flowforge.core.cloud.CloudException
import com.flowforge.core.cloud.OpenAiCompatibleClient
import com.flowforge.core.privacy.CloudPurpose
import com.flowforge.core.privacy.ConsentPolicy
import com.flowforge.core.recognition.EngineKind
import com.flowforge.core.recognition.EngineSelection
import com.flowforge.core.recognition.EngineSelector
import com.flowforge.core.recognition.RecognitionError
import com.flowforge.core.recognition.RecognitionErrorKind
import com.flowforge.core.session.DictationStateMachine
import com.flowforge.core.session.Phase
import com.flowforge.core.session.SessionEffect
import com.flowforge.core.session.SessionEvent
import com.flowforge.core.session.SessionState
import com.flowforge.dictation.graph
import com.flowforge.dictation.speech.AndroidSpeechEngine
import com.flowforge.dictation.speech.CloudSpeechEngine
import com.flowforge.dictation.speech.EngineAvailability
import com.flowforge.dictation.speech.EngineRequest
import com.flowforge.dictation.speech.RecognitionEngine

/** Final text ready for insertion, plus everything needed for undo/recovery and history. */
data class DictationOutput(
    val cleanup: CleanupResult,
    /** Text to insert: the cleaned text, or the AI-polished version if it passed validation. */
    val text: String,
    val aiApplied: Boolean,
    val note: String?,
    val engineName: String,
) {
    val original: String get() = cleanup.original
    val wantsUndoOfPrevious: Boolean get() = cleanup.command == CleanupCommand.UNDO_PREVIOUS_INSERTION
}

data class DeliveryReport(val outcome: String, val targetApp: String)

/**
 * Runs dictation for one surface (the floating bubble or the voice keyboard): engine selection,
 * recording lifecycle (via the pure [DictationStateMachine]), cleanup and hand-off for insertion.
 * Main thread only.
 */
class DictationController(private val context: Context, private val surface: Surface) {

    interface Surface {
        val surfaceName: String
        fun onStateChanged(state: SessionState)
        /** Text around the cursor of the target field, read just before cleanup (may be EMPTY). */
        fun insertionContext(): InsertionContext
        /** Insert (or present) the output; call [done] when finished. */
        fun deliver(output: DictationOutput, done: (DeliveryReport) -> Unit)
        /** True for fields that request no personalized learning (incognito). */
        fun isIncognito(): Boolean = false
        /** Short informational message (engine fallback, AI cleanup discarded, ...). */
        fun onNotice(message: String) {}
    }

    private val graph = context.graph
    private val machine = DictationStateMachine()
    private val handler = Handler(Looper.getMainLooper())
    private var engine: RecognitionEngine? = null
    private var engineName = ""
    private var released = false

    var state: SessionState = SessionState()
        private set

    private val maxDurationRunnable = Runnable { dispatch(SessionEvent.MaxDurationReached) }

    init {
        // Warm the dictionary cache off the main thread so cleanup and vocabulary hints are ready.
        graph.io.execute { runCatching { graph.dictionary.current() } }
    }

    fun toggle() {
        when {
            state.isRecording -> stop()
            state.phase == Phase.PROCESSING -> Unit
            else -> start()
        }
    }

    fun start() {
        if (released) return
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            dispatch(SessionEvent.PermissionDenied)
            return
        }
        dispatch(SessionEvent.StartRequested)
    }

    fun stop() = dispatch(SessionEvent.StopRequested)
    fun cancel() = dispatch(SessionEvent.CancelRequested)
    fun interrupt(reason: String) = dispatch(SessionEvent.Interrupted(reason))

    /** Clears an error/recovered-text state back to idle. */
    fun dismiss() {
        if (!state.isActive) dispatch(SessionEvent.CancelRequested)
    }

    fun release() {
        if (state.isActive) dispatch(SessionEvent.Interrupted("dictation surface closed"))
        released = true
        releaseEngine()
        handler.removeCallbacksAndMessages(null)
    }

    private fun dispatch(event: SessionEvent) {
        val t = machine.reduce(state, event, SystemClock.elapsedRealtime())
        val changed = t.state != state
        state = t.state
        t.effects.forEach(::run)
        if (!state.isRecording) handler.removeCallbacks(maxDurationRunnable)
        if (!state.isActive) releaseEngine()
        if (changed) surface.onStateChanged(state)
    }

    private fun run(effect: SessionEffect) {
        when (effect) {
            is SessionEffect.StartEngine -> startEngine(effect.sessionId)
            SessionEffect.StopEngine -> engine?.stop()
            SessionEffect.CancelEngine -> engine?.cancel()
            is SessionEffect.Process -> process(effect.sessionId, effect.transcript)
            is SessionEffect.ShowError -> Unit // surfaced through state.error
        }
    }

    private fun startEngine(sessionId: Int) {
        val settings = graph.settings.dictation
        val env = EngineAvailability.environment(context, graph.settings)
        when (val selection = EngineSelector.select(settings.enginePreference, env)) {
            is EngineSelection.Unavailable -> dispatch(SessionEvent.Failed(sessionId, selection.error))
            is EngineSelection.Use -> {
                selection.note?.let(surface::onNotice)
                val e = createEngine(selection.kind, settings.maxRecordingSeconds)
                if (e == null) {
                    dispatch(
                        SessionEvent.Failed(
                            sessionId,
                            RecognitionError(RecognitionErrorKind.NOT_CONFIGURED, "The cloud engine isn't set up.", false, "Add an endpoint and API key in Speech engine settings."),
                        ),
                    )
                    return
                }
                engine = e
                engineName = e.name
                val vocabulary = runCatching { graph.dictionary.cachedTerms() }.getOrDefault(emptyList())
                e.start(sessionId, EngineRequest(settings.languageTag, settings.continuous, vocabulary)) { ev -> dispatch(ev) }
                handler.removeCallbacks(maxDurationRunnable)
                handler.postDelayed(maxDurationRunnable, settings.maxRecordingSeconds * 1000L)
            }
        }
    }

    private fun createEngine(kind: EngineKind, maxSeconds: Int): RecognitionEngine? = when (kind) {
        EngineKind.ON_DEVICE -> AndroidSpeechEngine(context, onDevice = true)
        EngineKind.SYSTEM -> AndroidSpeechEngine(context, onDevice = false)
        EngineKind.CLOUD -> graph.settings.cloudConfig()?.let { CloudSpeechEngine(context, it, graph.io, maxSeconds) }
    }

    private fun releaseEngine() {
        engine?.release()
        engine = null
    }

    private fun process(sessionId: Int, transcript: String) {
        val insertionContext = runCatching { surface.insertionContext() }.getOrDefault(InsertionContext.EMPTY)
        val settings = graph.settings.dictation
        val privacy = graph.settings.privacy
        val engineUsed = engineName
        graph.background({
            val result = try {
                TextCleaner(graph.dictionary.current()).clean(transcript, settings.cleanupOptions(insertionContext))
            } catch (t: Throwable) {
                // A cleanup bug must never lose the user's words: fall back to the raw transcript.
                Log.e(TAG, "cleanup failed", t)
                CleanupResult(transcript, transcript.trim(), CleanupMode.VERBATIM, emptyList(), listOf("Cleanup failed; inserted the raw transcript."))
            }
            var text = result.text
            var ai = false
            var note: String? = null
            if (settings.aiCleanupEnabled && result.command == CleanupCommand.NONE && text.isNotBlank()) {
                val cfg = graph.settings.cloudConfig()
                when {
                    cfg == null -> note = "AI cleanup is on but no cloud provider is set up."
                    !ConsentPolicy.isValid(privacy.cleanupConsent, CloudPurpose.AI_CLEANUP, cfg.baseUrl) ->
                        note = "AI cleanup needs your consent in Privacy settings; used on-device cleanup."
                    !EngineAvailability.networkAvailable(context) -> note = "Offline: used on-device cleanup only."
                    else -> try {
                        val vocab = graph.dictionary.current().entries.map { it.term }
                        val candidate = OpenAiCompatibleClient(cfg).chat(AiCleanup.systemPrompt(settings.cleanupMode, vocab), text)
                        val validation = AiCleanup.validate(text, candidate)
                        if (validation.accepted) {
                            text = AiCleanup.stripWrapping(candidate)
                            ai = true
                        } else {
                            note = "AI cleanup was discarded (${validation.reasons.first()}); used on-device cleanup."
                        }
                    } catch (e: CloudException) {
                        note = "AI cleanup unavailable (${e.message}); used on-device cleanup."
                    }
                }
            }
            DictationOutput(result, text, ai, note, engineUsed)
        }) { r ->
            if (state.sessionId != sessionId || state.phase != Phase.PROCESSING) return@background
            val output = r.getOrElse {
                DictationOutput(
                    CleanupResult(transcript, transcript.trim(), CleanupMode.VERBATIM, emptyList(), emptyList()),
                    transcript.trim(), false, "Cleanup failed; inserted the raw transcript.", engineUsed,
                )
            }
            output.note?.let(surface::onNotice)
            try {
                surface.deliver(output) { report ->
                    saveHistory(output, report)
                    if (state.sessionId == sessionId) dispatch(SessionEvent.Delivered)
                }
            } catch (e: RuntimeException) {
                // Never crash or get stuck in PROCESSING because another app's field misbehaved.
                Log.e(TAG, "delivery failed", e)
                runCatching {
                    context.getSystemService(android.content.ClipboardManager::class.java)
                        .setPrimaryClip(android.content.ClipData.newPlainText("FlowForge dictation", output.text))
                }
                surface.onNotice("Couldn't insert the text (${e.javaClass.simpleName}); it was copied to the clipboard instead.")
                saveHistory(output, DeliveryReport("failed", ""))
                if (state.sessionId == sessionId && state.phase == Phase.PROCESSING) dispatch(SessionEvent.Delivered)
            }
        }
    }

    private fun saveHistory(output: DictationOutput, report: DeliveryReport) {
        val privacy = graph.settings.privacy
        if (privacy.respectIncognitoFields && surface.isIncognito()) return
        if (output.text.isBlank()) return
        val mode = output.cleanup.mode
        graph.io.execute {
            runCatching {
                graph.history.add(output.original, output.text, mode, output.engineName, report.outcome, report.targetApp)
            }.onFailure { Log.w(TAG, "history save failed", it) }
        }
    }

    private companion object {
        const val TAG = "FlowForgeDictation"
    }
}
