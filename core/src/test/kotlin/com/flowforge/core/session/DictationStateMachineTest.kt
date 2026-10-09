package com.flowforge.core.session

import com.flowforge.core.recognition.RecognitionErrorKind
import com.flowforge.core.recognition.RecognitionErrors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DictationStateMachineTest {
    private val sm = DictationStateMachine(minRestartIntervalMs = 300)
    private var now = 10_000L
    private var state = SessionState()
    private val effects = ArrayList<SessionEffect>()

    private fun send(e: SessionEvent, advanceMs: Long = 10): SessionState {
        now += advanceMs
        val t = sm.reduce(state, e, now)
        state = t.state
        effects += t.effects
        return state
    }

    private val id get() = state.sessionId

    @Test
    fun `full session lifecycle`() {
        send(SessionEvent.StartRequested)
        assertEquals(Phase.STARTING, state.phase)
        assertEquals(SessionEffect.StartEngine(1), effects.last())
        send(SessionEvent.EngineReady(id))
        assertEquals(Phase.LISTENING, state.phase)
        send(SessionEvent.Partial(id, "hello wor"))
        assertEquals("hello wor", state.displayText)
        send(SessionEvent.StopRequested)
        assertEquals(Phase.PROCESSING, state.phase)
        assertEquals(SessionEffect.StopEngine, effects.last())
        send(SessionEvent.Final(id, "hello world"))
        assertEquals(SessionEffect.Process(1, "hello world"), effects.last())
        send(SessionEvent.Delivered)
        assertEquals(Phase.IDLE, state.phase)
    }

    @Test
    fun `continuous segments are joined`() {
        send(SessionEvent.StartRequested)
        send(SessionEvent.Segment(id, "first part."))
        send(SessionEvent.Partial(id, "second"))
        send(SessionEvent.Segment(id, "second part."))
        send(SessionEvent.StopRequested)
        send(SessionEvent.Final(id, ""))
        assertEquals(SessionEffect.Process(1, "first part. second part."), effects.last())
    }

    @Test
    fun `final falls back to the last partial`() {
        send(SessionEvent.StartRequested)
        send(SessionEvent.Partial(id, "call me later"))
        send(SessionEvent.StopRequested)
        send(SessionEvent.Final(id, ""))
        assertEquals(SessionEffect.Process(1, "call me later"), effects.last())
    }

    @Test
    fun `cancel discards text and never processes`() {
        send(SessionEvent.StartRequested)
        send(SessionEvent.Partial(id, "secret"))
        send(SessionEvent.CancelRequested)
        assertEquals(Phase.IDLE, state.phase)
        assertEquals(SessionEffect.CancelEngine, effects.last())
        send(SessionEvent.Final(1, "secret"))
        assertTrue(effects.none { it is SessionEffect.Process })
    }

    @Test
    fun `rapid second tap is ignored to prevent accidental recording`() {
        send(SessionEvent.StartRequested)
        send(SessionEvent.CancelRequested)
        send(SessionEvent.StartRequested, advanceMs = 50)
        assertEquals(Phase.IDLE, state.phase)
        send(SessionEvent.StartRequested, advanceMs = 400)
        assertEquals(Phase.STARTING, state.phase)
        assertEquals(2, state.sessionId)
    }

    @Test
    fun `start while active is ignored`() {
        send(SessionEvent.StartRequested)
        send(SessionEvent.StartRequested, advanceMs = 1000)
        assertEquals(1, effects.count { it is SessionEffect.StartEngine })
    }

    @Test
    fun `repeated sessions get fresh ids and ignore stale callbacks`() {
        repeat(5) { n ->
            send(SessionEvent.StartRequested, advanceMs = 1000)
            assertEquals(n + 1, state.sessionId)
            send(SessionEvent.Segment(id, "take $n"))
            send(SessionEvent.StopRequested)
            send(SessionEvent.Final(id, ""))
            send(SessionEvent.Delivered)
        }
        val processed = effects.filterIsInstance<SessionEffect.Process>().map { it.transcript }
        assertEquals((0 until 5).map { "take $it" }, processed)
        // A late callback from session 3 must not touch session 6.
        send(SessionEvent.StartRequested, advanceMs = 1000)
        send(SessionEvent.Partial(3, "old"))
        send(SessionEvent.Final(3, "old"))
        assertEquals("", state.displayText)
        assertEquals(Phase.STARTING, state.phase)
    }

    @Test
    fun `network error keeps captured speech for recovery`() {
        send(SessionEvent.StartRequested)
        send(SessionEvent.Partial(id, "half a sentence"))
        send(SessionEvent.Failed(id, RecognitionErrors.fromAndroidCode(RecognitionErrors.ERROR_NETWORK)))
        assertEquals(Phase.ERROR, state.phase)
        assertEquals(RecognitionErrorKind.NETWORK, state.error!!.kind)
        assertEquals("half a sentence", state.recoveredText)
        assertTrue(effects.none { it is SessionEffect.Process })
    }

    @Test
    fun `silence after speech finishes normally`() {
        send(SessionEvent.StartRequested)
        send(SessionEvent.Segment(id, "done talking"))
        send(SessionEvent.Failed(id, RecognitionErrors.fromAndroidCode(RecognitionErrors.ERROR_SPEECH_TIMEOUT)))
        assertEquals(SessionEffect.Process(1, "done talking"), effects.last())
    }

    @Test
    fun `silence with nothing said is a gentle error`() {
        send(SessionEvent.StartRequested)
        send(SessionEvent.Failed(id, RecognitionErrors.fromAndroidCode(RecognitionErrors.ERROR_NO_MATCH)))
        assertEquals(Phase.ERROR, state.phase)
        assertTrue(state.error!!.isSilence)
    }

    @Test
    fun `permission revoked mid session`() {
        send(SessionEvent.StartRequested)
        send(SessionEvent.EngineReady(id))
        send(SessionEvent.PermissionDenied)
        assertEquals(Phase.ERROR, state.phase)
        assertEquals(RecognitionErrorKind.PERMISSION, state.error!!.kind)
        assertTrue(effects.contains(SessionEffect.CancelEngine))
    }

    @Test
    fun `interruption cancels the engine and keeps text`() {
        send(SessionEvent.StartRequested)
        send(SessionEvent.Segment(id, "before the call"))
        send(SessionEvent.Interrupted("phone call"))
        assertEquals(Phase.ERROR, state.phase)
        assertEquals(RecognitionErrorKind.INTERRUPTED, state.error!!.kind)
        assertEquals("before the call", state.recoveredText)
        assertTrue(effects.contains(SessionEffect.CancelEngine))
    }

    @Test
    fun `interruption while idle does nothing`() {
        send(SessionEvent.Interrupted("service restart"))
        assertEquals(Phase.IDLE, state.phase)
        assertTrue(effects.isEmpty())
    }

    @Test
    fun `max duration behaves like stop`() {
        send(SessionEvent.StartRequested)
        send(SessionEvent.Segment(id, "long talk"))
        send(SessionEvent.MaxDurationReached)
        assertEquals(Phase.PROCESSING, state.phase)
        assertEquals(SessionEffect.StopEngine, effects.last())
    }

    @Test
    fun `new session can start after an error`() {
        send(SessionEvent.StartRequested)
        send(SessionEvent.Failed(id, RecognitionErrors.fromAndroidCode(RecognitionErrors.ERROR_AUDIO)))
        send(SessionEvent.StartRequested, advanceMs = 1000)
        assertEquals(Phase.STARTING, state.phase)
        assertEquals(null, state.error)
    }
}
