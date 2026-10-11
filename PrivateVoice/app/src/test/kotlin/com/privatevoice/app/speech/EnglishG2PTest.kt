package com.privatevoice.app.speech

import com.privatevoice.app.speech.kokoro.EnglishG2P
import com.privatevoice.app.speech.kokoro.KokoroVocab
import com.privatevoice.app.speech.kokoro.LetterToSound
import com.privatevoice.app.speech.kokoro.NumberWords
import com.privatevoice.app.speech.kokoro.TextNormalizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EnglishG2PTest {
    private val g2p = EnglishG2P(TestAssets.lexicon)
    private val norm = TextNormalizer()
    private fun ph(text: String) = g2p.phonemize(norm.normalize(text))

    @Test fun matchesMisakiReferenceForSimpleSentence() {
        // Reference produced by misaki 0.9.4 (spaCy tagger, no espeak) for the same sentence.
        assertEquals("həlˈO, ɑɹ wi stˈɪl ɡˈOɪŋ ˈWt tənˈIt?", ph("Hello, are we still going out tonight?"))
    }

    @Test fun articleAndToDependOnNextWord() {
        assertTrue(ph("the apple").startsWith("ði "))
        assertTrue(ph("the car").startsWith("ðə "))
        assertTrue(ph("go to it").contains(" tʊ "))
        assertTrue(ph("go to bed").contains(" tə "))
    }

    @Test fun suffixRules() {
        assertTrue(ph("cats").endsWith("ts"))
        assertTrue(ph("wanted").endsWith("ᵻd"))
        assertFalse(ph("Kayleighs").contains("?"))
    }

    @Test fun numbersAndCurrency() {
        assertEquals("twenty twenty-five", NumberWords.year(2025))
        assertEquals("one hundred one", NumberWords.cardinal(101))
        assertEquals("twenty-first", NumberWords.ordinal(21))
        assertEquals("nineteen oh five", NumberWords.year(1905))
        val n = norm.normalize("It's \$4.50 at 3:30pm on the 21st, 50% off")
        assertEquals("It's four dollars and fifty cents at three thirty PM on the twenty-first, fifty percent off", n)
    }

    @Test fun messagesWithLinksEmojiAndShorthand() {
        val n = norm.normalize("omg 😂😂 see https://www.example.com/x?y=1 brb!!!")
        assertEquals("oh my gosh, laughing, see a link to example dot com be right back!", n)
        val skip = TextNormalizer(emojiMode = TextNormalizer.EmojiMode.SKIP).normalize("ok 👍")
        assertEquals("okay", skip)
    }

    @Test fun outOfVocabularyNamesStillProducePhonemes() {
        for (name in listOf("Jaxxon", "Brexley", "Zorblat")) {
            val p = ph(name)
            assertTrue("$name -> $p", p.isNotBlank() && p.any { it in EnglishG2P.VOWELS })
        }
        assertEquals("ˈɛf bˈi ˈI", LetterToSound.spell("fbi"))
        // "10am" must be read as letters, not as the article "a".
        assertTrue(ph("at 10am").endsWith("ˌAˈɛm"))
    }

    @Test fun allOutputCharactersAreInKokoroVocabulary() {
        val text = "Hey!! Are we still on for 7:45 tonight? Bring \$20 & your girlfriend's charger lol 🙏 — Dr. Smith said it's fine."
        val p = ph(text)
        val missing = p.filter { !KokoroVocab.contains(it) }
        assertEquals("unexpected characters in: $p", "", missing)
    }

    @Test fun longInputIsSplitWithinModelLimit() {
        val text = (1..60).joinToString(" ") { "This is sentence number $it, and it keeps going." }
        val chunks = com.privatevoice.app.speech.kokoro.KokoroSynthesizer.splitPhonemes(ph(text))
        assertTrue(chunks.size > 5)
        assertTrue(chunks.all { it.codePointCount(0, it.length) <= KokoroVocab.MAX_PHONEMES })
    }
}
