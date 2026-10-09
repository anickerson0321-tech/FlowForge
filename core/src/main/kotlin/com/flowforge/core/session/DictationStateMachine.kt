package com.flowforge.core.session

import com.flowforge.core.recognition.RecognitionError
import com.flowforge.core.recognition.RecognitionErrors

enum class Phase { IDLE, STARTING, LISTENING, PROCESSING, ERROR }

data class SessionState(
    val phase: Phase = Phase.IDLE,
    val sessionId: Int = 0,
    /** Text finalized so far in this session (continuous dictation produces several segments). */
    val committed: String = "",
    /** In-progress hypothesis for the current segment; shown in the bubble, never inserted. */
    val partial: String = "",
    val levelDb: Float = 0f,
    val error: RecognitionError? = null,
    /** Text captured before an interruption or error; offered for manual copy, never auto-inserted. */
    val recoveredText: String = "",
    val startedAtMillis: Long = 0,
    val lastEndedAtMillis: Long = Long.MIN_VALUE / 2,
) {
    val isActive: Boolean get() = phase == Phase.STARTING || phase == Phase.LISTENING || phase == Phase.PROCESSING
    val isRecording: Boolean get() = phase == Phase.STARTING || phase == Phase.LISTENING
    val displayText: String get() = joinSegments(committed, partial)
}

sealed class SessionEvent {
    /** User tapped to start. */
    object StartRequested : SessionEvent()

    /** User tapped to stop: finish and process what was said. */
    object StopRequested : SessionEvent()

    /** User cancelled: discard everything. */
    object CancelRequested : SessionEvent()

    /** Recording reached the configured maximum duration. */
    object MaxDurationReached : SessionEvent()

    object PermissionDenied : SessionEvent()

    /** The OS or another app took the microphone, the service is being destroyed, etc. */
    data class Interrupted(val reason: String) : SessionEvent()

    /** Cleanup/insertion finished (successfully or not); return to idle. */
    object Delivered : SessionEvent()

    // Engine callbacks carry the session they belong to so late callbacks from a
    // cancelled session are ignored.
    data class EngineReady(val sessionId: Int) : SessionEvent()
    data class Partial(val sessionId: Int, val text: String) : SessionEvent()
    data class Segment(val sessionId: Int, val text: String) : SessionEvent()
    data class Final(val sessionId: Int, val text: String) : SessionEvent()
    data class Level(val sessionId: Int, val db: Float) : SessionEvent()
    data class Failed(val sessionId: Int, val error: RecognitionError) : SessionEvent()
}

sealed class SessionEffect {
    data class StartEngine(val sessionId: Int) : SessionEffect()
    /** Ask the engine to finish gracefully and deliver its final result. */
    object StopEngine : SessionEffect()
    /** Abort the engine without results. */
    object CancelEngine : SessionEffect()
    /** Run cleanup and insertion on the session transcript. */
    data class Process(val sessionId: Int, val transcript: String) : SessionEffect()
    data class ShowError(val error: RecognitionError) : SessionEffect()
}

data class Transition(val state: SessionState, val effects: List<SessionEffect> = emptyList())

/**
 * Pure state machine for one dictation surface (bubble or keyboard). The Android layer feeds it
 * events and executes the returned effects; all lifecycle rules live here so they are unit tested:
 *
 *  - a start within [minRestartIntervalMs] of the previous session ending is ignored
 *    (double-tap/bounce protection against accidental recording);
 *  - engine callbacks for an old session id are ignored;
 *  - cancel never processes or inserts text;
 *  - interruptions and errors keep captured text in [SessionState.recoveredText] for manual copy;
 *  - "nothing heard" after some speech was committed still processes the committed speech.
 */
class DictationStateMachine(private val minRestartIntervalMs: Long = 350) {

    fun reduce(state: SessionState, event: SessionEvent, nowMillis: Long): Transition = when (event) {
        SessionEvent.StartRequested -> start(state, nowMillis)
        SessionEvent.StopRequested, SessionEvent.MaxDurationReached -> stop(state)
        SessionEvent.CancelRequested ->
            if (state.isActive) {
                Transition(SessionState(sessionId = state.sessionId, lastEndedAtMillis = nowMillis), listOf(SessionEffect.CancelEngine))
            } else {
                Transition(state.copy(phase = Phase.IDLE, error = null, recoveredText = ""))
            }
        SessionEvent.PermissionDenied -> fail(state, RecognitionErrors.permissionDenied, nowMillis, cancelEngine = state.isRecording)
        is SessionEvent.Interrupted ->
            if (state.isActive) fail(state, RecognitionErrors.interrupted(event.reason), nowMillis, cancelEngine = true) else Transition(state)
        SessionEvent.Delivered ->
            if (state.phase == Phase.PROCESSING) {
                Transition(SessionState(sessionId = state.sessionId, lastEndedAtMillis = nowMillis))
            } else {
                Transition(state)
            }
        is SessionEvent.EngineReady -> ifCurrent(state, event.sessionId) {
            if (state.phase == Phase.STARTING) Transition(state.copy(phase = Phase.LISTENING)) else Transition(state)
        }
        is SessionEvent.Partial -> ifCurrent(state, event.sessionId) {
            if (state.isRecording) Transition(state.copy(phase = Phase.LISTENING, partial = event.text)) else Transition(state)
        }
        is SessionEvent.Level -> ifCurrent(state, event.sessionId) {
            if (state.isRecording) Transition(state.copy(levelDb = event.db)) else Transition(state)
        }
        is SessionEvent.Segment -> ifCurrent(state, event.sessionId) {
            if (state.isActive) {
                Transition(state.copy(committed = joinSegments(state.committed, event.text), partial = ""))
            } else {
                Transition(state)
            }
        }
        is SessionEvent.Final -> ifCurrent(state, event.sessionId) { finish(state, event.text, nowMillis) }
        is SessionEvent.Failed -> ifCurrent(state, event.sessionId) {
            when {
                !state.isActive -> Transition(state)
                // Silence at the end of a dictation that already captured speech is a normal ending.
                event.error.isSilence && (state.committed.isNotBlank() || state.partial.isNotBlank()) ->
                    finish(state, "", nowMillis)
                else -> fail(state, event.error, nowMillis, cancelEngine = false)
            }
        }
    }

    private fun start(state: SessionState, now: Long): Transition {
        if (state.isActive) return Transition(state)
        if (now - state.lastEndedAtMillis < minRestartIntervalMs) return Transition(state)
        val id = state.sessionId + 1
        return Transition(
            SessionState(phase = Phase.STARTING, sessionId = id, startedAtMillis = now, lastEndedAtMillis = state.lastEndedAtMillis),
            listOf(SessionEffect.StartEngine(id)),
        )
    }

    private fun stop(state: SessionState): Transition = when (state.phase) {
        Phase.STARTING, Phase.LISTENING -> Transition(state.copy(phase = Phase.PROCESSING), listOf(SessionEffect.StopEngine))
        else -> Transition(state)
    }

    private fun finish(state: SessionState, finalText: String, now: Long): Transition {
        if (!state.isActive) return Transition(state)
        // Prefer the final result; fall back to the last partial if the engine returned nothing new.
        val tail = finalText.ifBlank { state.partial }
        val transcript = joinSegments(state.committed, tail)
        if (transcript.isBlank()) {
            val error = RecognitionErrors.fromAndroidCode(RecognitionErrors.ERROR_SPEECH_TIMEOUT)
            return Transition(
                SessionState(phase = Phase.ERROR, sessionId = state.sessionId, error = error, lastEndedAtMillis = now),
                listOf(SessionEffect.ShowError(error)),
            )
        }
        return Transition(
            state.copy(phase = Phase.PROCESSING, committed = transcript, partial = ""),
            listOf(SessionEffect.Process(state.sessionId, transcript)),
        )
    }

    private fun fail(state: SessionState, error: RecognitionError, now: Long, cancelEngine: Boolean): Transition {
        val recovered = state.displayText
        val effects = ArrayList<SessionEffect>()
        if (cancelEngine) effects += SessionEffect.CancelEngine
        effects += SessionEffect.ShowError(error)
        return Transition(
            SessionState(phase = Phase.ERROR, sessionId = state.sessionId, error = error, recoveredText = recovered, lastEndedAtMillis = now),
            effects,
        )
    }

    private inline fun ifCurrent(state: SessionState, id: Int, block: () -> Transition): Transition =
        if (id == state.sessionId) block() else Transition(state)
}

internal fun joinSegments(a: String, b: String): String {
    val x = a.trim()
    val y = b.trim()
    return when {
        x.isEmpty() -> y
        y.isEmpty() -> x
        else -> "$x $y"
    }
}
