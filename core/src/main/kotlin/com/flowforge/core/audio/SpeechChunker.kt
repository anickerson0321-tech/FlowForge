package com.flowforge.core.audio

import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Splits a live 16 kHz mono recording into utterance-sized chunks at natural pauses, so the
 * offline recognizer can transcribe earlier sentences while the user is still talking. When the
 * user stops, only the last chunk is left to decode.
 *
 * Cuts happen in the middle of a pause of at least [pauseMs] once a chunk is [minChunkMs] long, or
 * at the quietest moment of the last few seconds when a chunk reaches [maxChunkMs] without a pause.
 * Each chunk says whether it contains speech, so silence is never sent to the recognizer (which
 * avoids made-up words on silent input). Not thread-safe: feed from one thread.
 */
class SpeechChunker(
    private val sampleRate: Int = Pcm.SAMPLE_RATE,
    private val minChunkMs: Int = 4_000,
    private val maxChunkMs: Int = 24_000,
    private val pauseMs: Int = 600,
) {
    class Chunk(val samples: FloatArray, val hasSpeech: Boolean, val startMs: Long) {
        val durationMs: Long get() = samples.size * 1000L / Pcm.SAMPLE_RATE
    }

    private val frame = sampleRate / 50 // 20 ms
    private val maxSamples = sampleRate.toLong() * maxChunkMs / 1000
    private var buf = FloatArray(sampleRate * 2)
    private var len = 0
    private var frames = 0
    private var frameDb = FloatArray((maxSamples / frame + 4).toInt())
    private var noiseFloorDb = -60f
    private var consumedSamples = 0L

    /** Adds 16-bit little-endian PCM; returns any chunks that became complete. */
    fun feed(pcm: ByteArray, length: Int = pcm.size): List<Chunk> {
        val n = length / 2
        ensure(len + n)
        var i = 0
        while (i + 1 < length) {
            val s = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xff)).toShort()
            buf[len++] = s / 32768f
            i += 2
        }
        return analyzeAndCut()
    }

    /** Adds float samples in [-1, 1]; returns any chunks that became complete. */
    fun feed(samples: FloatArray): List<Chunk> {
        ensure(len + samples.size)
        System.arraycopy(samples, 0, buf, len, samples.size)
        len += samples.size
        return analyzeAndCut()
    }

    /** Returns the remaining audio as a final chunk (or null if nothing is buffered). */
    fun flush(): Chunk? {
        if (len == 0) return null
        analyzeFrames()
        val chunk = cut(len)
        frames = 0
        return chunk
    }

    /** Zeroes buffered audio. */
    fun clear() {
        java.util.Arrays.fill(buf, 0f)
        len = 0
        frames = 0
    }

    private fun ensure(capacity: Int) {
        if (capacity <= buf.size) return
        var size = buf.size
        while (size < capacity) size *= 2
        val next = FloatArray(size)
        System.arraycopy(buf, 0, next, 0, len)
        java.util.Arrays.fill(buf, 0f)
        buf = next
    }

    private fun analyzeFrames() {
        while ((frames + 1) * frame <= len) {
            if (frames >= frameDb.size) frameDb = frameDb.copyOf(frameDb.size * 2)
            val db = rmsDb(frames * frame, frame)
            frameDb[frames++] = db
            // Track the background level: follow it down at once, up only slowly (speech is louder).
            noiseFloorDb = if (db < noiseFloorDb) maxOf(db, -90f) else noiseFloorDb + (db - noiseFloorDb) * 0.004f
        }
    }

    private fun analyzeAndCut(): List<Chunk> {
        analyzeFrames()
        val out = ArrayList<Chunk>(1)
        while (true) {
            val at = findCut() ?: break
            out += cut(at)
        }
        return out
    }

    /** Sample index to cut at, or null to keep accumulating. */
    private fun findCut(): Int? {
        val minFrames = minChunkMs / 20
        val pauseFrames = pauseMs / 20
        if (frames >= minFrames) {
            // Trailing pause long enough, after some speech: cut in the middle of the pause.
            var silent = 0
            var f = frames - 1
            while (f >= 0 && !isSpeech(frameDb[f])) { silent++; f-- }
            if (silent >= pauseFrames && f >= 0) return (frames - silent / 2) * frame
        }
        if (len >= maxSamples) {
            // No pause: cut at the quietest 100 ms in the last 3 seconds.
            val window = 5
            val searchFrom = maxOf(minFrames, frames - 150)
            var best = frames
            var bestDb = Float.MAX_VALUE
            var f = searchFrom
            while (f + window <= frames) {
                var sum = 0f
                for (k in f until f + window) sum += frameDb[k]
                if (sum < bestDb) { bestDb = sum; best = f + window / 2 }
                f++
            }
            return best * frame
        }
        return null
    }

    private fun cut(at: Int): Chunk {
        val cutFrames = minOf(frames, at / frame)
        var speechFrames = 0
        for (f in 0 until cutFrames) if (isSpeech(frameDb[f])) speechFrames++
        // A trailing partial frame (flush) counts too.
        if (at > cutFrames * frame && isSpeech(rmsDb(cutFrames * frame, at - cutFrames * frame))) speechFrames++
        val chunk = Chunk(buf.copyOfRange(0, at), speechFrames >= MIN_SPEECH_FRAMES, consumedSamples * 1000 / sampleRate)
        consumedSamples += at
        System.arraycopy(buf, at, buf, 0, len - at)
        java.util.Arrays.fill(buf, len - at, len, 0f)
        len -= at
        System.arraycopy(frameDb, cutFrames, frameDb, 0, frames - cutFrames)
        frames -= cutFrames
        return chunk
    }

    private fun isSpeech(db: Float) = db > SPEECH_MIN_DB && db > noiseFloorDb + SPEECH_ABOVE_FLOOR_DB

    private fun rmsDb(from: Int, count: Int): Float {
        if (count <= 0) return -90f
        var sum = 0.0
        for (i in from until from + count) sum += buf[i] * buf[i]
        val rms = sqrt(sum / count)
        return if (rms <= 1e-9) -90f else (20 * log10(rms)).toFloat().coerceIn(-90f, 0f)
    }

    private companion object {
        const val SPEECH_MIN_DB = -50f
        const val SPEECH_ABOVE_FLOOR_DB = 10f
        const val MIN_SPEECH_FRAMES = 5 // 100 ms
    }
}
