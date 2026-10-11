package com.privatevoice.app.speech

import com.privatevoice.app.speech.kokoro.KokoroSynthesizer
import com.privatevoice.app.speech.kokoro.Lexicon
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/** Reads the real bundled assets produced by :app:prepareVoiceAssets. */
object TestAssets : KokoroSynthesizer.Assets {
    val root: File = File(System.getProperty("pv.voiceAssets") ?: "build/generated/voiceAssets", "kokoro")
    val outputDir: File = File(System.getProperty("pv.testOutput") ?: "build/test-audio").apply { mkdirs() }

    override fun model(): ByteBuffer = RandomAccessFile(File(root, "kokoro-v1.0-q8.onnx"), "r").use { raf ->
        raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, raf.length())
    }
    override fun voice(name: String): InputStream = File(root, "voices/$name.bin").inputStream()
    override fun lexicon(): InputStream = File(root, "lexicon_us.tsv").inputStream()

    val lexicon: Lexicon by lazy { lexicon().use { Lexicon.load(it) } }

    /** Test-only WAV writer (the app never writes audio to disk). */
    fun writeWav(file: File, samples: FloatArray, sampleRate: Int) {
        val data = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (s in samples) data.putShort((s.coerceIn(-1f, 1f) * 32767).toInt().toShort())
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray()).putInt(36 + data.capacity()).put("WAVE".toByteArray())
        header.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(sampleRate).putInt(sampleRate * 2)
            .putShort(2).putShort(16)
        header.put("data".toByteArray()).putInt(data.capacity())
        file.outputStream().use { it.write(header.array()); it.write(data.array()) }
    }
}
