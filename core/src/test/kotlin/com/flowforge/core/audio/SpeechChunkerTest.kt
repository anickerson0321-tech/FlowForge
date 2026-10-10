package com.flowforge.core.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.sin

class SpeechChunkerTest {
    private val rate = Pcm.SAMPLE_RATE
    private val rnd = Random(7)

    /** "Speech": a loud, modulated tone. */
    private fun voice(ms: Int): FloatArray = FloatArray(rate * ms / 1000) { i ->
        (0.3 * sin(2 * PI * 220 * i / rate) * (0.6 + 0.4 * sin(2 * PI * 3 * i / rate))).toFloat()
    }

    /** Quiet background noise around -66 dBFS. */
    private fun quiet(ms: Int): FloatArray = FloatArray(rate * ms / 1000) { ((rnd.nextFloat() - 0.5f) * 0.0017f) }

    private fun pcm16(samples: FloatArray): ByteArray {
        val b = ByteArray(samples.size * 2)
        samples.forEachIndexed { i, f ->
            val s = (f * 32767).toInt()
            b[2 * i] = s.toByte()
            b[2 * i + 1] = (s shr 8).toByte()
        }
        return b
    }

    private fun feedAll(c: SpeechChunker, vararg parts: FloatArray, step: Int = 1600): List<SpeechChunker.Chunk> {
        val all = parts.fold(FloatArray(0)) { acc, p -> acc + p }
        val out = ArrayList<SpeechChunker.Chunk>()
        var i = 0
        while (i < all.size) {
            val end = minOf(all.size, i + step)
            out += c.feed(pcm16(all.copyOfRange(i, end)))
            i = end
        }
        c.flush()?.let { out += it }
        return out
    }

    @Test
    fun `short dictation stays one chunk`() {
        val chunks = feedAll(SpeechChunker(), quiet(300), voice(2500), quiet(400))
        assertEquals(1, chunks.size)
        assertTrue(chunks[0].hasSpeech)
        assertEquals(3200L, chunks[0].durationMs)
    }

    @Test
    fun `splits in the middle of a pause after the minimum length`() {
        val chunks = feedAll(SpeechChunker(), voice(5000), quiet(1000), voice(3000))
        assertEquals(2, chunks.size)
        // The cut lands inside the pause: no speech is split.
        val first = chunks[0].durationMs
        assertTrue("cut at $first", first in 5000L..6000L)
        assertTrue(chunks.all { it.hasSpeech })
        assertEquals(9000L, chunks.sumOf { it.durationMs })
        assertEquals(first, chunks[1].startMs)
    }

    @Test
    fun `short pauses inside a sentence do not split`() {
        val chunks = feedAll(SpeechChunker(), voice(5000), quiet(300), voice(2000), quiet(300), voice(2000))
        assertEquals(1, chunks.size)
    }

    @Test
    fun `long speech without pauses is cut at the maximum length`() {
        val chunks = feedAll(SpeechChunker(maxChunkMs = 10_000), voice(25_000))
        assertTrue(chunks.size >= 3)
        assertTrue(chunks.dropLast(1).all { it.durationMs <= 10_000 })
        assertEquals(25_000L, chunks.sumOf { it.durationMs })
    }

    @Test
    fun `silence is flagged so it is never transcribed`() {
        val chunks = feedAll(SpeechChunker(), quiet(3000))
        assertEquals(1, chunks.size)
        assertFalse(chunks[0].hasSpeech)
        val dead = feedAll(SpeechChunker(), FloatArray(rate * 2))
        assertFalse(dead[0].hasSpeech)
    }

    @Test
    fun `steady background noise is not mistaken for speech`() {
        val hum = FloatArray(rate * 6) { i -> (0.01 * sin(2 * PI * 120 * i / rate)).toFloat() } // about -43 dBFS
        val chunks = feedAll(SpeechChunker(), hum, voice(2000), hum.copyOf(rate))
        assertTrue(chunks.any { it.hasSpeech })
        // Speech after six seconds of hum is still detected and kept whole.
        val speechChunk = chunks.last { it.hasSpeech }
        assertTrue(speechChunk.durationMs >= 2000)
    }

    @Test
    fun `float and pcm input agree, flush empties the buffer`() {
        val a = SpeechChunker()
        val b = SpeechChunker()
        val v = voice(1000)
        a.feed(v)
        b.feed(pcm16(v))
        val fa = a.flush()!!
        val fb = b.flush()!!
        assertEquals(fa.samples.size, fb.samples.size)
        assertNull(a.flush())
    }
}
