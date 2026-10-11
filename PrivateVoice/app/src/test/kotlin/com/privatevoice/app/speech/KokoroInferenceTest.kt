package com.privatevoice.app.speech

import com.privatevoice.app.speech.kokoro.KokoroSynthesizer
import org.junit.AfterClass
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import kotlin.math.sqrt

/**
 * Real Kokoro-82M inference through ONNX Runtime (no mocks). Writes WAVs to
 * build/test-audio so they can be checked with an offline speech recognizer.
 */
class KokoroInferenceTest {
    companion object {
        lateinit var synth: KokoroSynthesizer
        @BeforeClass @JvmStatic fun setUp() {
            synth = KokoroSynthesizer(TestAssets, threads = 4)
            val t0 = System.nanoTime()
            synth.load()
            println("Model load: ${(System.nanoTime() - t0) / 1_000_000} ms")
        }
        @AfterClass @JvmStatic fun tearDown() = synth.close()

        val SAMPLES = listOf(
            "af_heart" to "Hello, are we still going out tonight?",
            "am_michael" to "Hey man, the game starts at 7:30. Bring snacks!",
            "af_bella" to "Reminder: your appointment with Dr. Patel is on March 3rd at 10am.",
            "bm_george" to "Can you pick up milk, eggs and bread on the way home? Thanks!",
            "af_sarah" to "omg I can't believe it 😂 call me when you get this",
            "am_adam" to "The meeting moved to Thursday. Please review the $1,250 budget before then.",
            "bm_george" to "Message from Sarah in Family.",
            "am_onyx" to "yo r u coming to the game tmrw? bring the cooler lol",
        )
    }

    private fun rms(a: FloatArray) = sqrt(a.fold(0.0) { s, x -> s + x * x } / a.size.coerceAtLeast(1))

    @Test fun synthesizesIntelligibleSpeechForSeveralVoices() {
        SAMPLES.forEachIndexed { i, (voice, text) ->
            val chunks = ArrayList<FloatArray>()
            val t0 = System.nanoTime()
            synth.synthesize(text, voice, 1.0f) { chunks += it }
            val ms = (System.nanoTime() - t0) / 1_000_000
            val audio = chunks.fold(FloatArray(0)) { acc, c -> acc + c }
            val seconds = audio.size / KokoroSynthesizer.SAMPLE_RATE.toDouble()
            println("[$voice] \"$text\" -> ${synth.phonemize(text)}")
            println("    ${"%.2f".format(seconds)} s audio in $ms ms (RTF ${"%.2f".format(ms / 1000.0 / seconds)}), RMS ${"%.4f".format(rms(audio))}")
            assertTrue("audio too short for '$text'", seconds > 0.8)
            assertTrue("audio is silent for '$text'", rms(audio) > 0.01)
            assertTrue("audio unreasonably long", seconds < 20)
            TestAssets.writeWav(File(TestAssets.outputDir, "sample_${i}_$voice.wav"), audio, KokoroSynthesizer.SAMPLE_RATE)
        }
    }

    @Test fun speedControlChangesDuration() {
        val text = "Speaking rate should change how long this takes."
        fun dur(speed: Float): Int { var n = 0; synth.synthesize(text, "af_heart", speed) { n += it.size }; return n }
        val slow = dur(0.75f)
        val fast = dur(1.5f)
        println("slow=$slow fast=$fast samples")
        assertTrue(slow > fast * 1.4)
    }

    @Test fun cancellationStopsBetweenChunks() {
        val text = (1..10).joinToString(" ") { "Sentence $it is here to make this message long enough." }
        var chunks = 0
        synth.synthesize(text, "af_heart", 1f, isActive = { chunks < 1 }) { chunks++ }
        assertTrue(chunks == 1)
    }
}
