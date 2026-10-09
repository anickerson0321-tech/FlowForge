package com.flowforge.dictation.speech

import com.flowforge.core.session.SessionEvent

data class EngineRequest(
    /** BCP-47 tag; empty = device default. */
    val languageTag: String,
    /** Keep listening across pauses until stop() is called. */
    val continuous: Boolean,
    /** Personal dictionary terms, used as a hint by engines that support one. */
    val vocabulary: List<String>,
)

/**
 * A speech recognizer driven by [com.flowforge.dictation.dictation.DictationController].
 * All methods are called on the main thread and every event is delivered on the main thread,
 * tagged with the session id passed to [start].
 */
interface RecognitionEngine {
    val name: String
    fun start(sessionId: Int, request: EngineRequest, sink: (SessionEvent) -> Unit)

    /** Finish gracefully: deliver a [SessionEvent.Final] (possibly empty) soon after. */
    fun stop()

    /** Abort without results. */
    fun cancel()

    /** Free native resources. The engine is not reused after this. */
    fun release()
}

/** Simple timing measurements surfaced in Help → Diagnostics (measured on the device, not claimed). */
object EngineMetrics {
    @Volatile var lastStartLatencyMs: Long = -1
    @Volatile var lastFinalLatencyMs: Long = -1
    @Volatile var lastEngine: String = ""
    @Volatile var sessions: Int = 0
}
