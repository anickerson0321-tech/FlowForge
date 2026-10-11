package com.privatevoice.app.speech.kokoro

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * Offline Kokoro-82M inference: text -> phonemes -> ONNX Runtime -> 24 kHz PCM.
 *
 * Pure JVM code (no Android imports) so the exact same pipeline runs in unit
 * tests with real model inference. All audio stays in memory; nothing is
 * written to disk.
 */
class KokoroSynthesizer(
    private val assets: Assets,
    private val threads: Int = 4,
) : AutoCloseable {

    /** Read-only access to bundled model assets. */
    interface Assets {
        /** The ONNX model as a direct (ideally memory-mapped) buffer. */
        fun model(): ByteBuffer
        fun voice(name: String): InputStream
        fun lexicon(): InputStream
    }

    val sampleRate = SAMPLE_RATE
    val normalizer = TextNormalizer()

    private val lock = Any()
    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null
    private var g2p: EnglishG2P? = null
    private val voices = LinkedHashMap<String, FloatArray>(8, 0.75f, true)

    val isLoaded: Boolean get() = synchronized(lock) { session != null }

    /** Loads lexicon and model. Expensive (~1-3 s); call off the main thread. */
    fun load() {
        synchronized(lock) {
            if (session != null) return
            if (g2p == null) g2p = EnglishG2P(assets.lexicon().use { Lexicon.load(it) })
            val e = OrtEnvironment.getEnvironment()
            val opts = OrtSession.SessionOptions()
            opts.setIntraOpNumThreads(threads)
            opts.setInterOpNumThreads(1)
            opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            session = e.createSession(assets.model(), opts)
            opts.close()
            env = e
        }
    }

    /** Releases the ONNX session and voice tensors (the lexicon is kept: it holds no user data). */
    fun unloadModel() {
        synchronized(lock) {
            session?.close()
            session = null
            voices.clear()
        }
    }

    override fun close() {
        synchronized(lock) {
            unloadModel()
            g2p = null
        }
    }

    fun phonemize(text: String): String {
        val g = synchronized(lock) {
            g2p ?: EnglishG2P(assets.lexicon().use { Lexicon.load(it) }).also { g2p = it }
        }
        return g.phonemize(normalizer.normalize(text))
    }

    /**
     * Synthesizes [text] with [voice], delivering audio chunk by chunk (one chunk
     * per sentence group) so playback can start before the whole message is done.
     * Stops early when [isActive] returns false.
     */
    fun synthesize(
        text: String,
        voice: String,
        speed: Float = 1.0f,
        isActive: () -> Boolean = { true },
        onChunk: (FloatArray) -> Unit,
    ) {
        load()
        val phonemes = phonemize(text)
        if (phonemes.isBlank()) return
        val style = voiceTensor(voice)
        for (chunk in splitPhonemes(phonemes)) {
            if (!isActive()) return
            val audio = infer(chunk, style, speed)
            if (!isActive()) return
            onChunk(trimSilence(audio, chunk.trimEnd().lastOrNull()))
        }
    }

    /** Runs one inference call. [phonemes] must be at most [KokoroVocab.MAX_PHONEMES] tokens. */
    fun infer(phonemes: String, style: FloatArray, speed: Float): FloatArray {
        val ids = KokoroVocab.encode(phonemes)
        val n = (ids.size - 2).coerceIn(1, KokoroVocab.MAX_PHONEMES)
        val row = (n - 1).coerceIn(0, VOICE_ROWS - 1)
        val e: OrtEnvironment
        val s: OrtSession
        synchronized(lock) {
            e = env ?: error("Kokoro model not loaded")
            s = session ?: error("Kokoro model not loaded")
        }
        val idTensor = OnnxTensor.createTensor(e, LongBuffer.wrap(ids), longArrayOf(1, ids.size.toLong()))
        val styleTensor = OnnxTensor.createTensor(e, FloatBuffer.wrap(style, row * STYLE_DIM, STYLE_DIM), longArrayOf(1, STYLE_DIM.toLong()))
        val speedTensor = OnnxTensor.createTensor(e, FloatBuffer.wrap(floatArrayOf(speed.coerceIn(0.5f, 2.0f))), longArrayOf(1))
        try {
            s.run(mapOf("input_ids" to idTensor, "style" to styleTensor, "speed" to speedTensor)).use { result ->
                val out = result.get(0) as OnnxTensor
                val fb = out.floatBuffer
                val audio = FloatArray(fb.remaining())
                fb.get(audio)
                return audio
            }
        } finally {
            idTensor.close(); styleTensor.close(); speedTensor.close()
        }
    }

    fun voiceTensor(name: String): FloatArray = synchronized(lock) {
        voices[name] ?: run {
            val bytes = assets.voice(name).use { it.readBytes() }
            require(bytes.size == VOICE_ROWS * STYLE_DIM * 4) { "Invalid voice tensor for $name" }
            val fa = FloatArray(VOICE_ROWS * STYLE_DIM)
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(fa)
            voices[name] = fa
            while (voices.size > MAX_CACHED_VOICES) voices.remove(voices.keys.first())
            fa
        }
    }

    companion object {
        const val SAMPLE_RATE = 24_000
        const val STYLE_DIM = 256
        const val VOICE_ROWS = 510
        private const val MAX_CACHED_VOICES = 6

        /**
         * Splits a phoneme string into pieces of at most [max] code points, preferring
         * sentence ends, then clause punctuation, then word boundaries.
         */
        fun splitPhonemes(ps: String, max: Int = KokoroVocab.MAX_PHONEMES, target: Int = 220): List<String> {
            val sentences = ArrayList<String>()
            val sb = StringBuilder()
            for (ch in ps) {
                sb.append(ch)
                if (ch in ".!?…") { sentences += sb.toString().trim(); sb.setLength(0) }
            }
            if (sb.isNotBlank()) sentences += sb.toString().trim()
            // Break oversize sentences at clause punctuation, then spaces.
            val pieces = ArrayList<String>()
            for (s in sentences) pieces += breakLong(s, max)
            // Merge short pieces up to the target size to keep prosody natural and calls few.
            val out = ArrayList<String>()
            val cur = StringBuilder()
            for (p in pieces) {
                if (cur.isNotEmpty() && cur.codePointCount(0, cur.length) + 1 + p.codePointCount(0, p.length) > target) {
                    out += cur.toString(); cur.setLength(0)
                }
                if (cur.isNotEmpty()) cur.append(' ')
                cur.append(p)
            }
            if (cur.isNotEmpty()) out += cur.toString()
            return out.filter { it.isNotBlank() }
        }

        private fun breakLong(s: String, max: Int): List<String> {
            if (s.codePointCount(0, s.length) <= max) return listOf(s)
            val result = ArrayList<String>()
            var rest = s
            while (rest.codePointCount(0, rest.length) > max) {
                val window = rest.substring(0, rest.offsetByCodePoints(0, max))
                var cut = window.indexOfLast { it in ",;:—" }
                if (cut < max / 3) cut = window.lastIndexOf(' ')
                if (cut <= 0) cut = window.length - 1
                result += rest.substring(0, cut + 1).trim()
                rest = rest.substring(cut + 1).trim()
            }
            if (rest.isNotBlank()) result += rest
            return result
        }

        /** Trims model padding silence, keeping a short natural tail sized by the final punctuation. */
        fun trimSilence(audio: FloatArray, lastPunct: Char?): FloatArray {
            if (audio.isEmpty()) return audio
            val threshold = 0.01f
            var start = audio.indexOfFirst { kotlin.math.abs(it) > threshold }
            var end = audio.indexOfLast { kotlin.math.abs(it) > threshold }
            if (start < 0 || end < 0) return FloatArray(0)
            start = (start - SAMPLE_RATE * 30 / 1000).coerceAtLeast(0)
            end = (end + SAMPLE_RATE * 60 / 1000).coerceAtMost(audio.size - 1)
            val pauseMs = when (lastPunct) { '.', '!', '?', '…' -> 220; ',', ';', ':', '—' -> 120; else -> 80 }
            val body = audio.copyOfRange(start, end + 1)
            return body + FloatArray(SAMPLE_RATE * pauseMs / 1000)
        }
    }
}
