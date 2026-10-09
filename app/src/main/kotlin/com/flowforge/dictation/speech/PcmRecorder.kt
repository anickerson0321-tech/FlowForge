package com.flowforge.dictation.speech

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import com.flowforge.core.audio.Pcm
import java.io.ByteArrayOutputStream
import java.util.Arrays

/**
 * Records 16 kHz mono 16-bit PCM into memory only (never to disk). Used by engines that need
 * raw audio (cloud upload). Callbacks arrive on the main thread.
 */
class PcmRecorder(
    private val context: Context,
    private val maxSeconds: Int,
    private val onLevel: (Float) -> Unit,
    private val onSilenced: () -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private var record: AudioRecord? = null
    private var thread: Thread? = null
    @Volatile private var running = false
    private val buffer = ByteArrayOutputStream()
    private val maxBytes = maxSeconds * Pcm.SAMPLE_RATE * 2
    private val audioManager = context.getSystemService(AudioManager::class.java)

    private val recordingCallback = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
            val id = record?.audioSessionId ?: return
            // Android silences our capture when a higher-priority app (e.g. a call) takes the mic.
            if (configs.any { it.clientAudioSessionId == id && it.isClientSilenced }) main.post(onSilenced)
        }
    }

    /** Starts capture; returns false if the microphone could not be opened. */
    @SuppressLint("MissingPermission") // checked explicitly below
    fun start(): Boolean {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return false
        val minBuf = AudioRecord.getMinBufferSize(Pcm.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) return false
        val r = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION, Pcm.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, Pcm.SAMPLE_RATE),
            )
        } catch (e: RuntimeException) {
            return false
        }
        if (r.state != AudioRecord.STATE_INITIALIZED) {
            r.release()
            return false
        }
        try {
            r.startRecording()
        } catch (e: IllegalStateException) {
            r.release()
            return false
        }
        if (r.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            r.release()
            return false
        }
        record = r
        running = true
        audioManager.registerAudioRecordingCallback(recordingCallback, main)
        thread = Thread({ readLoop(r) }, "flowforge-pcm").apply { start() }
        return true
    }

    private fun readLoop(r: AudioRecord) {
        val chunk = ByteArray(Pcm.SAMPLE_RATE / 10 * 2) // 100 ms
        var lastLevel = 0L
        while (running) {
            val n = r.read(chunk, 0, chunk.size)
            if (n <= 0) continue
            synchronized(buffer) {
                if (buffer.size() + n <= maxBytes) buffer.write(chunk, 0, n)
            }
            val now = System.nanoTime()
            if (now - lastLevel > 60_000_000L) {
                lastLevel = now
                val level = Pcm.meterLevel(Pcm.rmsDbfs(chunk, n))
                main.post { onLevel(level) }
            }
        }
        Arrays.fill(chunk, 0)
    }

    /** Stops capture and returns the recorded PCM. The internal buffer is cleared. */
    fun stop(): ByteArray {
        running = false
        thread?.join(500)
        thread = null
        try {
            audioManager.unregisterAudioRecordingCallback(recordingCallback)
        } catch (e: RuntimeException) {
            // not registered
        }
        record?.let {
            try {
                it.stop()
            } catch (e: IllegalStateException) {
                // already stopped
            }
            it.release()
        }
        record = null
        return synchronized(buffer) {
            val bytes = buffer.toByteArray()
            buffer.reset()
            bytes
        }
    }

    val recordedMillis: Long get() = synchronized(buffer) { Pcm.durationMillis(buffer.size()) }
}
