package com.flowforge.dictation.speech

import com.flowforge.core.audio.Pcm
import com.flowforge.core.audio.SpeechChunker
import com.flowforge.core.models.ModelCatalog
import org.junit.AfterClass
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Opt-in: runs FlowForge's offline recognition path (chunking + sherpa-onnx transcription) on real
 * audio with a real model, using sherpa-onnx's Linux build of the same native library and the
 * Kotlin API classes from the Android AAR. Skipped unless configured:
 *
 *   FLOWFORGE_SHERPA_LIB=/path/sherpa-onnx-v1.13.8-linux-x64-jni/lib
 *   FLOWFORGE_MODEL_DIR=/path/to/installed/model   FLOWFORGE_MODEL_ID=parakeet-tdt-0.6b-v2-int8
 *   FLOWFORGE_TEST_WAVS=a.wav,b.wav (16 kHz mono 16-bit; optional)
 *
 * Desktop timings only; phones are slower.
 */
class LocalTranscriberIntegrationTest {
    companion object {
        private var transcriber: LocalTranscriber? = null

        @BeforeClass
        @JvmStatic
        fun load() {
            val dir = System.getenv("FLOWFORGE_MODEL_DIR") ?: return
            if (System.getenv("FLOWFORGE_SHERPA_LIB") == null) return
            val model = ModelCatalog.byId(System.getenv("FLOWFORGE_MODEL_ID")) ?: ModelCatalog.default
            val t0 = System.nanoTime()
            transcriber = LocalTranscriber.load(model, File(dir), threads = 4)
            println("[offline] loaded ${model.id} in ${(System.nanoTime() - t0) / 1_000_000} ms")
        }

        @AfterClass
        @JvmStatic
        fun release() {
            transcriber?.release()
            transcriber = null
        }

        fun readWav(f: File): FloatArray {
            val b = f.readBytes()
            val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
            require(bb.getShort(22).toInt() == 1 && bb.getInt(24) == Pcm.SAMPLE_RATE && bb.getShort(34).toInt() == 16) { "${f.name}: need 16 kHz mono 16-bit" }
            var pos = 12
            while (pos + 8 <= b.size) {
                val id = String(b, pos, 4, Charsets.US_ASCII)
                val len = bb.getInt(pos + 4)
                if (id == "data") return FloatArray(minOf(len, b.size - pos - 8) / 2) { bb.getShort(pos + 8 + 2 * it) / 32768f }
                pos += 8 + len
            }
            error("${f.name}: no data chunk")
        }

        fun toPcm16(samples: FloatArray): ByteArray {
            val out = ByteArray(samples.size * 2)
            samples.forEachIndexed { i, f ->
                val s = (f * 32767f).toInt().coerceIn(-32768, 32767)
                out[2 * i] = s.toByte()
                out[2 * i + 1] = (s shr 8).toByte()
            }
            return out
        }

        /** Word-level edit distance. */
        fun distance(a: List<String>, b: List<String>): Int {
            var prev = IntArray(b.size + 1) { it }
            for (i in 1..a.size) {
                val cur = IntArray(b.size + 1)
                cur[0] = i
                for (j in 1..b.size) cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = cur
            }
            return prev[b.size]
        }

        fun words(s: String) = s.lowercase().replace(Regex("[^a-z0-9' ]"), " ").split(' ').filter { it.isNotBlank() }
    }

    private fun wavs(): List<File> {
        val explicit = System.getenv("FLOWFORGE_TEST_WAVS")?.split(',')?.map(::File)
        val files = explicit ?: File(System.getenv("FLOWFORGE_MODEL_DIR") ?: "", "test_wavs").listFiles { f -> f.name.endsWith(".wav") }?.sorted().orEmpty()
        return files.filter { runCatching { readWav(it) }.isSuccess }
    }

    /** Feeds audio exactly like LocalModelEngine: 100 ms PCM blocks into the chunker. */
    private fun transcribeStreaming(t: LocalTranscriber, samples: FloatArray): Pair<String, Int> {
        val chunker = SpeechChunker()
        val pcm = toPcm16(samples)
        val texts = ArrayList<String>()
        var chunks = 0
        val block = Pcm.SAMPLE_RATE / 10 * 2
        var i = 0
        fun take(c: SpeechChunker.Chunk) {
            chunks++
            if (c.hasSpeech) t.transcribe(c.samples).takeIf { it.isNotBlank() }?.let(texts::add)
        }
        while (i < pcm.size) {
            val n = minOf(block, pcm.size - i)
            chunker.feed(pcm.copyOfRange(i, i + n)).forEach(::take)
            i += n
        }
        chunker.flush()?.let(::take)
        return texts.joinToString(" ") to chunks
    }

    @Test
    fun `transcribes real speech offline`() {
        val t = transcriber
        assumeTrue("set FLOWFORGE_SHERPA_LIB and FLOWFORGE_MODEL_DIR to run", t != null)
        val files = wavs()
        assumeTrue("no 16 kHz test wavs", files.isNotEmpty())
        for (f in files) {
            val samples = readWav(f)
            val t0 = System.nanoTime()
            val whole = t!!.transcribe(samples)
            val secs = (System.nanoTime() - t0) / 1e9
            val audio = samples.size / Pcm.SAMPLE_RATE.toDouble()
            println("[offline] ${f.name} (%.1f s audio, %.2f s, RTF %.3f): %s".format(audio, secs, secs / audio, whole))
            assertTrue("${f.name} produced no text", whole.isNotBlank())
        }
    }

    @Test
    fun `pause-based chunking keeps every word of a long dictation`() {
        val t = transcriber
        assumeTrue("set FLOWFORGE_SHERPA_LIB and FLOWFORGE_MODEL_DIR to run", t != null)
        val files = wavs()
        assumeTrue("need at least two test wavs", files.size >= 2)
        // One long "dictation": the clips separated by one-second pauses, as a user would speak.
        val gap = FloatArray(Pcm.SAMPLE_RATE)
        val clips = files.map(::readWav)
        val long = clips.flatMapIndexed { i, c -> if (i == 0) c.toList() else gap.toList() + c.toList() }.toFloatArray()
        val expected = clips.joinToString(" ") { t!!.transcribe(it) }
        val t0 = System.nanoTime()
        val (streamed, chunks) = transcribeStreaming(t!!, long)
        println("[offline] ${clips.size} clips, %.1f s, %d chunks, %.2f s total decode".format(long.size / 16000.0, chunks, (System.nanoTime() - t0) / 1e9))
        println("[offline] per clip : $expected")
        println("[offline] streamed : $streamed")
        assertTrue("expected the long dictation to be split", chunks >= 2)
        // Models may word the same audio slightly differently ("5:30" vs "five thirty") when it is
        // padded differently, but no words may be lost at chunk boundaries.
        val a = words(expected)
        val b = words(streamed)
        val d = distance(a, b)
        println("[offline] word edit distance $d of ${a.size}")
        assertTrue("streamed transcript differs by $d words", d <= maxOf(2, a.size / 20))
    }
}
