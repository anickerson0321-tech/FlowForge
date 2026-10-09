package com.flowforge.dictation.speech

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.flowforge.core.audio.Pcm
import com.flowforge.core.cloud.CloudConfig
import com.flowforge.core.cloud.CloudException
import com.flowforge.core.cloud.OpenAiCompatibleClient
import com.flowforge.core.recognition.RecognitionError
import com.flowforge.core.recognition.RecognitionErrorKind
import com.flowforge.core.recognition.RecognitionErrors
import com.flowforge.core.session.SessionEvent
import java.util.Arrays
import java.util.concurrent.ExecutorService

/**
 * Records locally, then uploads the whole recording to the user's OpenAI-compatible endpoint when
 * they stop. Only constructed after consent for that exact host was checked by the controller.
 * Audio exists only in memory and is zeroed after the upload.
 */
class CloudSpeechEngine(
    private val context: Context,
    private val config: CloudConfig,
    private val io: ExecutorService,
    private val maxSeconds: Int,
) : RecognitionEngine {
    override val name: String = "cloud (${config.host})"

    private val main = Handler(Looper.getMainLooper())
    private var recorder: PcmRecorder? = null
    private var sink: ((SessionEvent) -> Unit)? = null
    private var sessionId = 0
    private var request: EngineRequest? = null
    @Volatile private var cancelled = false

    override fun start(sessionId: Int, request: EngineRequest, sink: (SessionEvent) -> Unit) {
        this.sessionId = sessionId
        this.sink = sink
        this.request = request
        cancelled = false
        EngineMetrics.lastEngine = name
        EngineMetrics.sessions++
        val started = SystemClock.elapsedRealtime()
        val rec = PcmRecorder(
            context, maxSeconds,
            onLevel = { level -> sink(SessionEvent.Level(sessionId, level)) },
            onSilenced = { sink(SessionEvent.Interrupted("another app is using the microphone")) },
        )
        if (!rec.start()) {
            sink(SessionEvent.Failed(sessionId, RecognitionErrors.fromAndroidCode(RecognitionErrors.ERROR_AUDIO)))
            return
        }
        recorder = rec
        EngineMetrics.lastStartLatencyMs = SystemClock.elapsedRealtime() - started
        // No partial text: the transcript only exists after upload. (Status is shown by the UI, never
        // as a partial, because partials can become the inserted text.)
        sink(SessionEvent.EngineReady(sessionId))
    }

    override fun stop() {
        val rec = recorder ?: return
        recorder = null
        val pcm = rec.stop()
        val id = sessionId
        val req = request
        if (pcm.size < Pcm.SAMPLE_RATE / 2) { // under 250 ms of audio
            Arrays.fill(pcm, 0)
            sink?.invoke(SessionEvent.Final(id, ""))
            return
        }
        val stopAt = SystemClock.elapsedRealtime()
        io.execute {
            val wav = Pcm.toWav(pcm)
            Arrays.fill(pcm, 0)
            val result: SessionEvent = try {
                val vocab = req?.vocabulary?.take(50)?.joinToString(", ")
                val text = OpenAiCompatibleClient(config).transcribe(wav, req?.languageTag, vocab)
                SessionEvent.Final(id, text)
            } catch (e: CloudException) {
                SessionEvent.Failed(id, mapError(e))
            } finally {
                Arrays.fill(wav, 0)
            }
            main.post {
                EngineMetrics.lastFinalLatencyMs = SystemClock.elapsedRealtime() - stopAt
                if (!cancelled) sink?.invoke(result)
            }
        }
    }

    override fun cancel() {
        cancelled = true
        recorder?.stop()?.let { Arrays.fill(it, 0) }
        recorder = null
    }

    override fun release() {
        cancel()
        sink = null
    }

    private fun mapError(e: CloudException): RecognitionError = when {
        e.status == 401 || e.status == 403 -> RecognitionError(
            RecognitionErrorKind.NOT_CONFIGURED, e.message ?: "API key rejected.", false,
            "Check the API key in Speech engine settings.",
        )
        e.status == 429 -> RecognitionError(RecognitionErrorKind.TOO_MANY_REQUESTS, e.message ?: "Rate limited.", true, "Wait a minute or check your provider quota.")
        e.status < 0 -> RecognitionError(RecognitionErrorKind.NETWORK, e.message ?: "Network error.", true, "Check your connection or switch to on-device recognition.")
        else -> RecognitionError(RecognitionErrorKind.SERVER, e.message ?: "Provider error.", e.retryable, "Try again, or switch to on-device recognition.")
    }
}
