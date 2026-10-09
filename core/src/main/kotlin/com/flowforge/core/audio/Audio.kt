package com.flowforge.core.audio

import java.io.ByteArrayOutputStream
import kotlin.math.log10
import kotlin.math.sqrt

/** PCM helpers shared by the recorder and the cloud uploader. */
object Pcm {
    const val SAMPLE_RATE = 16_000

    /** RMS level of 16-bit little-endian mono PCM in dBFS (0 = full scale, about -90 = silence). */
    fun rmsDbfs(pcm: ByteArray, length: Int = pcm.size): Float {
        val samples = length / 2
        if (samples == 0) return -90f
        var sum = 0.0
        var i = 0
        while (i + 1 < length) {
            val s = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xff)).toShort().toDouble()
            sum += s * s
            i += 2
        }
        val rms = sqrt(sum / samples) / 32768.0
        if (rms <= 1e-9) return -90f
        return (20 * log10(rms)).toFloat().coerceIn(-90f, 0f)
    }

    /** Maps dBFS to 0..1 for UI meters. */
    fun meterLevel(dbfs: Float): Float = ((dbfs + 60f) / 60f).coerceIn(0f, 1f)

    /** Wraps 16-bit mono PCM in a WAV (RIFF) container. */
    fun toWav(pcm: ByteArray, sampleRate: Int = SAMPLE_RATE): ByteArray {
        val out = ByteArrayOutputStream(44 + pcm.size)
        fun int32(v: Int) = out.write(byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte()))
        fun int16(v: Int) = out.write(byteArrayOf(v.toByte(), (v shr 8).toByte()))
        out.write("RIFF".toByteArray(Charsets.US_ASCII))
        int32(36 + pcm.size)
        out.write("WAVE".toByteArray(Charsets.US_ASCII))
        out.write("fmt ".toByteArray(Charsets.US_ASCII))
        int32(16)
        int16(1) // PCM
        int16(1) // mono
        int32(sampleRate)
        int32(sampleRate * 2) // byte rate
        int16(2) // block align
        int16(16) // bits per sample
        out.write("data".toByteArray(Charsets.US_ASCII))
        int32(pcm.size)
        out.write(pcm)
        return out.toByteArray()
    }

    fun durationMillis(pcmBytes: Int, sampleRate: Int = SAMPLE_RATE): Long = pcmBytes / 2 * 1000L / sampleRate
}
