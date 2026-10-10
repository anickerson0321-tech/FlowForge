package com.flowforge.dictation.speech

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.flowforge.core.audio.SpeechChunker
import com.flowforge.core.models.SpeechModel
import com.flowforge.core.recognition.RecognitionErrors
import com.flowforge.core.session.SessionEvent
import java.util.Arrays
import java.util.concurrent.atomic.AtomicInteger

/**
 * FlowForge's own offline engine. The microphone records continuously (no restarts between
 * sentences, so nothing is dropped), the audio is split at natural pauses, and each finished
 * piece is transcribed by the downloaded model while the user keeps talking. When the user stops,
 * only the last piece is left to transcribe. Audio stays in memory and is zeroed after use.
 */
class LocalModelEngine(
    private val context: Context,
    private val model: SpeechModel,
    private val maxSeconds: Int,
) : RecognitionEngine {
    override val name: String = "offline model (${model.id})"

    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()
    private var chunker: SpeechChunker? = null
    private var recorder: PcmRecorder? = null
    private var sink: ((SessionEvent) -> Unit)? = null
    private var sessionId = 0
    private val pending = AtomicInteger(0)
    private var stopping = false
    private var flushed = false
    @Volatile private var done = false
    @Volatile private var cancelled = false
    private var using = false
    private var stopAt = 0L

    override fun start(sessionId: Int, request: EngineRequest, sink: (SessionEvent) -> Unit) {
        this.sessionId = sessionId
        this.sink = sink
        cancelled = false
        stopping = false
        flushed = false
        done = false
        pending.set(0)
        EngineMetrics.lastEngine = name
        EngineMetrics.sessions++
        val started = SystemClock.elapsedRealtime()
        using = true
        LocalAsr.beginUse()
        // Load (or keep) the model while the user talks. If it can't load, say so right away
        // instead of letting the user talk into a recognizer that will never answer.
        LocalAsr.preload(context, model) { error ->
            if (error != null && sessionId == this.sessionId) fail(sessionId, error)
        }
        synchronized(lock) { chunker = SpeechChunker() }
        val rec = PcmRecorder(
            context, maxSeconds,
            onLevel = { level -> sink(SessionEvent.Level(sessionId, level)) },
            onSilenced = { sink(SessionEvent.Interrupted("another app is using the microphone")) },
            onReadError = { sink(SessionEvent.Interrupted("the microphone stopped working")) },
            onAudio = { bytes, n ->
                // Recording thread: queue finished pieces straight onto the recognizer thread so they
                // are transcribed in order, even if the user stops while a post is in flight.
                val chunks = synchronized(lock) { chunker?.feed(bytes, n) }
                chunks?.forEach { submit(it) }
            },
        )
        if (!rec.start()) {
            synchronized(lock) { chunker = null }
            sink(SessionEvent.Failed(sessionId, RecognitionErrors.fromAndroidCode(RecognitionErrors.ERROR_AUDIO)))
            return
        }
        recorder = rec
        EngineMetrics.lastStartLatencyMs = SystemClock.elapsedRealtime() - started
        sink(SessionEvent.EngineReady(sessionId))
    }

    private fun submit(chunk: SpeechChunker.Chunk) {
        if (!chunk.hasSpeech || cancelled) {
            Arrays.fill(chunk.samples, 0f)
            return
        }
        pending.incrementAndGet()
        val id = sessionId
        LocalAsr.run(
            context, model,
            skip = { cancelled || done },
            work = { t ->
                try {
                    if (cancelled) "" else t.transcribe(chunk.samples)
                } finally {
                    Arrays.fill(chunk.samples, 0f)
                }
            },
            then = { r -> onResult(id, r) },
        )
    }

    /** Main thread, in submission order. */
    private fun onResult(id: Int, r: Result<String>) {
        pending.decrementAndGet()
        if (cancelled || done || id != sessionId) return
        r.fold(
            onSuccess = { text -> if (text.isNotBlank()) sink?.invoke(SessionEvent.Segment(id, text)) },
            onFailure = { e ->
                fail(id, e)
                return
            },
        )
        finishIfDone()
    }

    private fun fail(id: Int, e: Throwable) {
        if (cancelled || done || id != sessionId) return
        done = true
        cancelRecording()
        endUse()
        sink?.invoke(
            SessionEvent.Failed(
                id,
                RecognitionErrors.engineUnavailable(
                    e.message ?: "The offline model failed.",
                    "Dictate again to use Android's recognizer instead. To fix the offline model, delete and re-download it (or pick the smaller one) in Speech settings.",
                ),
            ),
        )
    }

    private fun endUse() {
        if (using) {
            using = false
            LocalAsr.endUse()
        }
    }

    override fun stop() {
        if (done || stopping) return
        stopping = true
        stopAt = SystemClock.elapsedRealtime()
        recorder?.stop()
        recorder = null
        val last = synchronized(lock) {
            val c = chunker?.flush()
            chunker = null
            c
        }
        last?.let(::submit)
        flushed = true
        finishIfDone()
    }

    private fun finishIfDone() {
        if (!stopping || !flushed || done || pending.get() > 0) return
        done = true
        endUse()
        EngineMetrics.lastFinalLatencyMs = SystemClock.elapsedRealtime() - stopAt
        // Every piece was already delivered as a Segment; Final closes the dictation.
        sink?.invoke(SessionEvent.Final(sessionId, ""))
    }

    override fun cancel() {
        cancelled = true
        cancelRecording()
        endUse()
    }

    private fun cancelRecording() {
        recorder?.stop()
        recorder = null
        synchronized(lock) {
            chunker?.clear()
            chunker = null
        }
    }

    override fun release() {
        cancel()
        sink = null
    }
}
