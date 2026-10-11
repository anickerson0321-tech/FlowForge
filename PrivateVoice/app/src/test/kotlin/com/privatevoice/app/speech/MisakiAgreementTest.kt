package com.privatevoice.app.speech

import com.privatevoice.app.speech.kokoro.EnglishG2P
import com.privatevoice.app.speech.kokoro.TextNormalizer
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Measures how closely the Kotlin G2P matches misaki (the G2P Kokoro was trained with)
 * on reference sentences. misaki uses a neural POS tagger; we use heuristics, so a few
 * differences are expected.
 */
class MisakiAgreementTest {
    private fun lev(a: String, b: String): Int {
        val d = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = d[0]; d[0] = i
            for (j in 1..b.length) {
                val tmp = d[j]
                d[j] = minOf(d[j] + 1, d[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = tmp
            }
        }
        return d[b.length]
    }

    @Test fun agreementWithMisakiReference() {
        val g2p = EnglishG2P(TestAssets.lexicon)
        val norm = TextNormalizer()
        val rows = javaClass.getResourceAsStream("/misaki_reference.tsv")!!.bufferedReader().readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }.map { it.split('\t') }
        var exact = 0; var charErr = 0; var chars = 0; var wordsSame = 0; var words = 0
        for ((text, ref) in rows) {
            val ours = g2p.phonemize(norm.normalize(text))
            if (ours == ref) exact++
            charErr += lev(ours, ref); chars += ref.length
            val rw = ref.split(' '); val ow = ours.split(' ')
            words += rw.size
            wordsSame += rw.zip(ow).count { (x, y) -> x == y }
            if (ours != ref) println("DIFF  $text\n  misaki: $ref\n  ours:   $ours")
        }
        val per = 100.0 * charErr / chars
        println("Exact sentence matches: $exact/${rows.size}; aligned word agreement ${"%.1f".format(100.0 * wordsSame / words)}%; phoneme error rate ${"%.1f".format(per)}%")
        assertTrue("phoneme error rate too high: $per", per < 15.0)
    }
}
