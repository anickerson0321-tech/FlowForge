package com.flowforge.dictation.speech

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.flowforge.core.recognition.RecognitionErrors
import com.flowforge.core.session.SessionEvent

/**
 * Wraps [SpeechRecognizer]. With [onDevice] it uses Android's on-device recognizer, which keeps
 * audio on the phone; otherwise the user's default recognition service (which may use the network).
 *
 * Continuous dictation: SpeechRecognizer ends each utterance at a pause, so when the user has not
 * asked to stop, each result is emitted as a [SessionEvent.Segment] and listening restarts at once.
 * Prolonged silence (no speech for [silenceTimeoutMs]) ends the session automatically.
 */
class AndroidSpeechEngine(
    private val context: Context,
    private val onDevice: Boolean,
    private val silenceTimeoutMs: Long = 20_000,
) : RecognitionEngine {
    override val name: String = if (onDevice) "on-device" else "system"

    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var sink: ((SessionEvent) -> Unit)? = null
    private var sessionId = 0
    private var request: EngineRequest? = null
    private var stopping = false
    private var finished = false
    private var readyReported = false
    private var startedAt = 0L
    private var stopAt = 0L
    private var lastSpeechAt = 0L
    private var lastLevelAt = 0L
    private var busyRetries = 0
    /** True between startListening() and the matching onResults/onError. */
    private var listening = false

    private val watchdog = Runnable { finish("") }

    override fun start(sessionId: Int, request: EngineRequest, sink: (SessionEvent) -> Unit) {
        check(Looper.myLooper() == Looper.getMainLooper()) { "SpeechRecognizer must be used on the main thread" }
        this.sessionId = sessionId
        this.request = request
        this.sink = sink
        stopping = false
        finished = false
        readyReported = false
        busyRetries = 0
        startedAt = SystemClock.elapsedRealtime()
        lastSpeechAt = startedAt
        EngineMetrics.lastEngine = name
        EngineMetrics.sessions++
        val r = try {
            if (onDevice) SpeechRecognizer.createOnDeviceSpeechRecognizer(context) else SpeechRecognizer.createSpeechRecognizer(context)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not create recognizer", e)
            emit(SessionEvent.Failed(sessionId, RecognitionErrors.engineUnavailable(
                "The speech service could not be started.", "Check that a speech recognition service is installed and enabled.",
            )))
            return
        }
        recognizer = r
        r.setRecognitionListener(listener)
        listen()
    }

    private fun listen() {
        val req = request ?: return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            if (req.languageTag.isNotBlank()) putExtra(RecognizerIntent.EXTRA_LANGUAGE, req.languageTag)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, onDevice)
            // Ask capable recognizers for punctuation/capitals; FlowForge's cleanup handles the rest.
            putExtra(RecognizerIntent.EXTRA_ENABLE_FORMATTING, RecognizerIntent.FORMATTING_OPTIMIZE_QUALITY)
            // Dictation should reproduce the user's words, not censor them.
            putExtra(RecognizerIntent.EXTRA_MASK_OFFENSIVE_WORDS, false)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
        }
        try {
            recognizer?.startListening(intent)
            listening = true
        } catch (e: RuntimeException) {
            Log.w(TAG, "startListening failed", e)
            emit(SessionEvent.Failed(sessionId, RecognitionErrors.fromAndroidCode(RecognitionErrors.ERROR_CLIENT)))
        }
    }

    override fun stop() {
        if (finished) return
        stopping = true
        stopAt = SystemClock.elapsedRealtime()
        if (!listening) {
            // Between two continuous segments: everything said is already committed.
            finish("")
            return
        }
        try {
            recognizer?.stopListening()
        } catch (e: RuntimeException) {
            Log.w(TAG, "stopListening failed", e)
        }
        // Some services never answer after stopListening(); don't leave the user waiting.
        handler.removeCallbacks(watchdog)
        handler.postDelayed(watchdog, STOP_WATCHDOG_MS)
    }

    override fun cancel() {
        finished = true
        stopping = true
        handler.removeCallbacksAndMessages(null)
        try {
            recognizer?.cancel()
        } catch (e: RuntimeException) {
            Log.w(TAG, "cancel failed", e)
        }
    }

    override fun release() {
        handler.removeCallbacksAndMessages(null)
        finished = true
        try {
            recognizer?.destroy()
        } catch (e: RuntimeException) {
            Log.w(TAG, "destroy failed", e)
        }
        recognizer = null
        sink = null
    }

    private fun emit(event: SessionEvent) {
        if (finished && event !is SessionEvent.Final && event !is SessionEvent.Failed) return
        sink?.invoke(event)
    }

    private fun finish(text: String) {
        if (finished) return
        finished = true
        handler.removeCallbacks(watchdog)
        if (stopAt > 0) EngineMetrics.lastFinalLatencyMs = SystemClock.elapsedRealtime() - stopAt
        sink?.invoke(SessionEvent.Final(sessionId, text))
    }

    private fun fail(code: Int) {
        if (finished) return
        finished = true
        handler.removeCallbacks(watchdog)
        sink?.invoke(SessionEvent.Failed(sessionId, RecognitionErrors.fromAndroidCode(code)))
    }

    private fun restartSoon() {
        handler.postDelayed({ if (!finished && !stopping) listen() }, RESTART_DELAY_MS)
    }

    private fun firstResult(bundle: Bundle?): String =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            if (!readyReported) {
                readyReported = true
                EngineMetrics.lastStartLatencyMs = SystemClock.elapsedRealtime() - startedAt
                emit(SessionEvent.EngineReady(sessionId))
            }
        }

        override fun onBeginningOfSpeech() {
            lastSpeechAt = SystemClock.elapsedRealtime()
        }

        override fun onRmsChanged(rmsdB: Float) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastLevelAt < 60) return
            lastLevelAt = now
            // Typical RMS range reported by recognizers is about -2..10 dB.
            emit(SessionEvent.Level(sessionId, ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)))
        }

        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit

        override fun onPartialResults(partialResults: Bundle?) {
            val text = firstResult(partialResults)
            if (text.isNotEmpty()) {
                lastSpeechAt = SystemClock.elapsedRealtime()
                emit(SessionEvent.Partial(sessionId, text))
            }
        }

        override fun onResults(results: Bundle?) {
            listening = false
            val text = firstResult(results)
            val continuous = request?.continuous == true
            if (continuous && !stopping) {
                if (text.isNotEmpty()) {
                    lastSpeechAt = SystemClock.elapsedRealtime()
                    emit(SessionEvent.Segment(sessionId, text))
                }
                if (SystemClock.elapsedRealtime() - lastSpeechAt > silenceTimeoutMs) finish("") else restartSoon()
            } else {
                finish(text)
            }
        }

        override fun onError(error: Int) {
            listening = false
            val continuous = request?.continuous == true
            val silence = error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT || error == SpeechRecognizer.ERROR_NO_MATCH
            when {
                finished -> Unit
                // A pause while dictating continuously: keep listening unless silence lasted too long.
                silence && continuous && !stopping ->
                    if (SystemClock.elapsedRealtime() - lastSpeechAt > silenceTimeoutMs) finish("") else restartSoon()
                // Stopped by the user: end normally with whatever was committed.
                stopping && (silence || error == SpeechRecognizer.ERROR_CLIENT) -> finish("")
                // Never reopen the microphone after the user asked to stop.
                stopping && error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> finish("")
                error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY && busyRetries < 3 -> {
                    busyRetries++
                    handler.postDelayed({ if (!finished && !stopping) listen() }, 300L * busyRetries)
                }
                else -> fail(error)
            }
        }
    }

    private companion object {
        const val TAG = "FlowForgeSpeech"
        const val STOP_WATCHDOG_MS = 3500L
        const val RESTART_DELAY_MS = 50L
    }
}
