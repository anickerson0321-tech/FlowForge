package com.privatevoice.app.speech.kokoro

/**
 * English grapheme-to-phoneme conversion for Kokoro, ported from the core of
 * misaki's `en.G2P` (the G2P Kokoro v1.0 was trained with):
 *
 *  - gold/silver lexicon lookup with misaki's stress rules,
 *  - context-dependent function words ("the", "to", "a", "an", "in", "I"),
 *  - -s / -ed / -ing / possessive / contraction suffix rules,
 *  - letter spelling for acronyms, compound splitting, and a rule-based
 *    letter-to-sound fallback for out-of-vocabulary words (misaki would use
 *    espeak-ng here, which is not available on-device in this build).
 *
 * spaCy part-of-speech tagging is replaced by light neighbouring-word heuristics.
 */
class EnglishG2P(private val lexicon: Lexicon) {

    private sealed class Tok {
        data class Word(val text: String) : Tok()
        data class Punct(val text: String) : Tok()
    }

    /** Phonemizes already-normalized text (see [TextNormalizer]). */
    fun phonemize(text: String): String {
        val tokens = tokenize(text)
        val phonemes = arrayOfNulls<String>(tokens.size)
        // Right-to-left pass so each word knows whether the next one starts with a vowel.
        var futureVowel: Boolean? = null
        for (idx in tokens.indices.reversed()) {
            when (val t = tokens[idx]) {
                is Tok.Punct -> { phonemes[idx] = t.text; futureVowel = null }
                is Tok.Word -> {
                    val prev = (tokens.getOrNull(idx - 1) as? Tok.Word)?.text?.lowercase()
                    val next = (tokens.getOrNull(idx + 1) as? Tok.Word)?.text?.lowercase()
                    val ps = if (next == "to" && t.text.lowercase() == "used") (gold("used:VBD") ?: "jˈust") else word(t.text, prev, futureVowel)
                    phonemes[idx] = ps
                    val first = ps.firstOrNull { it in VOWELS || it in CONSONANTS }
                    if (first != null) futureVowel = first in VOWELS
                }
            }
        }
        val sb = StringBuilder()
        for (idx in tokens.indices) {
            val ps = phonemes[idx] ?: continue
            if (ps.isEmpty()) continue
            when (tokens[idx]) {
                is Tok.Punct -> {
                    if (ps == "(" || ps == "—") { if (sb.isNotEmpty() && sb.last() != ' ') sb.append(' ') }
                    else while (sb.isNotEmpty() && sb.last() == ' ') sb.setLength(sb.length - 1)
                    sb.append(ps)
                    if (ps != "(") sb.append(' ')
                }
                is Tok.Word -> {
                    if (sb.isNotEmpty() && sb.last() != ' ' && sb.last() != '(') sb.append(' ')
                    sb.append(ps)
                }
            }
        }
        return sb.toString().trim().replace('ɾ', 'T').replace('ʔ', 't')
    }

    private fun tokenize(text: String): List<Tok> {
        val out = ArrayList<Tok>()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c.isLetter() -> {
                    var j = i
                    while (j < text.length && (text[j].isLetter() || (text[j] == '\'' && j + 1 < text.length && text[j + 1].isLetter()))) j++
                    out += Tok.Word(text.substring(i, j))
                    i = j
                }
                c == '-' -> { i++ } // hyphenated words are read as separate words
                c in ".,!?;:…—()" -> {
                    val prev = out.lastOrNull()
                    // collapse duplicate punctuation
                    if (!(prev is Tok.Punct && prev.text == c.toString())) out += Tok.Punct(c.toString())
                    i++
                }
                else -> i++
            }
        }
        return out
    }

    /** Pronunciation of a single word in context. */
    fun word(text: String, prev: String?, futureVowel: Boolean?): String {
        if (text in SPELL_OUT) return nnp(text)
        special(text, prev, futureVowel)?.let { return it }
        val stress: Double? = when {
            text == text.lowercase() -> null
            text.length > 1 && text == text.uppercase() -> 2.0
            else -> 0.5
        }
        val tag = guessTag(prev)
        resolve(text, tag, futureVowel)?.let { return applyStress(it, stress) }
        // ALL-CAPS unknowns are acronyms.
        if (text.length in 2..5 && text == text.uppercase()) return nnp(text)
        val lower = text.lowercase()
        // elongated spellings ("yess", "nooo" already collapsed to two letters by the normalizer)
        collapseDoubles(lower)?.let { return applyStress(it, stress) }
        compound(lower)?.let { return applyStress(it, stress) }
        return LetterToSound.convert(lower)
    }

    private fun special(text: String, prev: String?, futureVowel: Boolean?): String? = when (text) {
        "a", "A" -> if (futureVowel != null) "ɐ" else "ˈA"
        "an", "An" -> "ɐn"
        "the", "The", "THE" -> if (futureVowel == true) "ði" else "ðə"
        "to", "To" -> when (futureVowel) { null -> gold("to") ?: "tˈu"; true -> "tʊ"; false -> "tə" }
        "in", "In" -> if (futureVowel == null) "ˈɪn" else "ɪn"
        "I" -> "ˌI"
        "am", "Am" -> if (futureVowel == null || text != "am") gold("am") ?: "ˈæm" else "ɐm"
        else -> null
    }

    /** Lexicon entry, optionally for a specific tag written as "word:TAG". */
    private fun gold(word: String): String? {
        val w = word.substringBefore(':')
        val tag = word.substringAfter(':', "").ifEmpty { null }
        return lexicon.raw(w)?.let { Lexicon.choose(it, tag) }
    }

    private fun guessTag(prev: String?): String? = when (prev) {
        null -> null
        in VERB_CONTEXT -> "VERB"
        in NOUN_CONTEXT -> "NOUN"
        else -> null
    }

    /** Lexicon lookup with suffix handling; returns null if unknown. */
    private fun resolve(word: String, tag: String?, futureVowel: Boolean?): String? {
        lookup(word, tag, futureVowel)?.let { return it }
        val lower = word.lowercase()
        if (lower != word) lookup(lower, tag, futureVowel)?.let { return it }
        // possessives
        if (lower.endsWith("'s") && lower.length > 2) {
            resolve(lower.dropLast(2), tag, futureVowel)?.let { return suffixS(it) }
        }
        if (lower.endsWith("s'") && lower.length > 2) {
            resolve(lower.dropLast(1), tag, futureVowel)?.let { return it }
        }
        // contractions
        for ((suf, ph) in CONTRACTIONS) {
            if (lower.endsWith(suf) && lower.length > suf.length) {
                val stem = lower.dropLast(suf.length)
                lookup(stem, tag, futureVowel)?.let { return it + ph }
            }
        }
        stemS(lower, tag)?.let { return it }
        stemEd(lower, tag)?.let { return it }
        stemIng(lower, tag)?.let { return it }
        return null
    }

    private fun lookup(word: String, tag: String?, futureVowel: Boolean?): String? {
        val raw = lexicon.find(word) ?: return null
        val effectiveTag = if (futureVowel == null && raw.contains("None=")) "None" else tag
        val ps = Lexicon.choose(raw, effectiveTag)
        return if (ps.isEmpty()) nnp(word) else ps
    }

    private fun isKnown(word: String): Boolean {
        if (lexicon.find(word) != null) return true
        if (word.length == 1 && word[0].isLetter()) return true
        return false
    }

    private fun suffixS(stem: String): String? {
        if (stem.isEmpty()) return null
        return when (stem.last()) {
            in "ptkfθ" -> stem + "s"
            in "szʃʒʧʤ" -> stem + "ᵻz"
            else -> stem + "z"
        }
    }

    private fun stemS(word: String, tag: String?): String? {
        if (word.length < 3 || !word.endsWith("s")) return null
        val stem = when {
            !word.endsWith("ss") && isKnown(word.dropLast(1)) -> word.dropLast(1)
            word.length > 4 && word.endsWith("es") && !word.endsWith("ies") && isKnown(word.dropLast(2)) -> word.dropLast(2)
            word.length > 4 && word.endsWith("ies") && isKnown(word.dropLast(3) + "y") -> word.dropLast(3) + "y"
            else -> return null
        }
        return lookup(stem, tag, false)?.let { suffixS(it) }
    }

    private fun suffixEd(stem: String): String? {
        if (stem.isEmpty()) return null
        val last = stem.last()
        return when {
            last in "pkfθʃsʧ" -> stem + "t"
            last == 'd' -> stem + "ᵻd"
            last != 't' -> stem + "d"
            stem.length < 2 -> stem + "ɪd"
            stem[stem.length - 2] in US_TAUS -> stem.dropLast(1) + "ɾᵻd"
            else -> stem + "ᵻd"
        }
    }

    private fun stemEd(word: String, tag: String?): String? {
        if (word.length < 4 || !word.endsWith("d")) return null
        val stem = when {
            !word.endsWith("dd") && isKnown(word.dropLast(1)) -> word.dropLast(1)
            word.length > 4 && word.endsWith("ed") && !word.endsWith("eed") && isKnown(word.dropLast(2)) -> word.dropLast(2)
            word.length > 5 && word.endsWith("ed") && word[word.length - 3] == word[word.length - 4] && isKnown(word.dropLast(3)) -> word.dropLast(3)
            word.length > 4 && word.endsWith("ied") && isKnown(word.dropLast(3) + "y") -> word.dropLast(3) + "y"
            else -> return null
        }
        return lookup(stem, tag, false)?.let { suffixEd(it) }
    }

    private fun suffixIng(stem: String): String? {
        if (stem.isEmpty()) return null
        if (stem.length > 1 && stem.last() == 't' && stem[stem.length - 2] in US_TAUS) return stem.dropLast(1) + "ɾɪŋ"
        return stem + "ɪŋ"
    }

    private fun stemIng(word: String, tag: String?): String? {
        if (word.length < 5 || !word.endsWith("ing")) return null
        val base = word.dropLast(3)
        val stem = when {
            word.length > 5 && isKnown(base) -> base
            isKnown(base + "e") -> base + "e"
            word.length > 5 && DOUBLED_ING.containsMatchIn(word) && isKnown(word.dropLast(4)) -> word.dropLast(4)
            else -> return null
        }
        return lookup(stem, tag, false)?.let { suffixIng(it) }
    }

    private fun nnp(word: String): String {
        val letters = word.filter { it.isLetter() }
        if (letters.isEmpty()) return ""
        val parts = letters.map { ch -> lexicon.raw(ch.uppercase())?.let { Lexicon.choose(it, null) } ?: LetterToSound.spell(ch.toString()) }
        val joined = parts.joinToString("")
        // misaki: the last letter carries the primary stress, earlier ones secondary.
        val lastStress = joined.lastIndexOf('ˈ')
        val sb = StringBuilder()
        joined.forEachIndexed { i, ch -> sb.append(if (ch == 'ˈ' && i != lastStress) 'ˌ' else ch) }
        return sb.toString()
    }

    private fun collapseDoubles(word: String): String? {
        val m = Regex("([a-z])\\1").findAll(word).toList()
        if (m.isEmpty()) return null
        for (r in m.reversed()) {
            val candidate = word.removeRange(r.range.first, r.range.first + 1)
            resolve(candidate, null, false)?.let { return it }
        }
        return null
    }

    private fun compound(word: String): String? {
        if (word.length < 6) return null
        for (split in (word.length - 3) downTo 3) {
            val a = word.substring(0, split)
            val b = word.substring(split)
            if (lexicon.find(a) == null || lexicon.find(b) == null) continue
            val pa = resolve(a, null, false) ?: continue
            val pb = resolve(b, null, false) ?: continue
            // second element loses its primary stress
            return pa + applyStress(pb, -1.0)
        }
        return null
    }

    companion object {
        const val VOWELS = "AIOQWYaiuæɑɒɔəɛɜɪʊʌᵻ"
        const val CONSONANTS = "bdfhjklmnpstvwzðŋɡɹɾʃʒʤʧθ"
        private const val US_TAUS = "AIOWYiuæɑəɛɪɹʊʌ"
        private const val PRIMARY = 'ˈ'
        private const val SECONDARY = 'ˌ'
        private val DOUBLED_ING = Regex("([bcdgklmnprstvxz])\\1ing$|cking$")
        /** Acronyms that are always read letter by letter, even when the lexicon has a word reading. */
        private val SPELL_OUT = setOf("AM", "PM", "FYI", "LOL", "LMAO", "ETA", "OMW", "BRB", "ID", "TV", "PC", "UK", "US", "USA")
        private val CONTRACTIONS = listOf("n't" to "nt", "'ll" to "l", "'re" to "ɹ", "'ve" to "v", "'d" to "d", "'m" to "m")
        private val VERB_CONTEXT = setOf(
            "to", "will", "would", "can", "could", "should", "shall", "must", "might", "may", "i", "you", "we", "they",
            "he", "she", "it", "who",
            "don't", "didn't", "doesn't", "won't", "can't", "let's", "please", "gonna", "wanna",
        )
        private val NOUN_CONTEXT = setOf(
            "the", "a", "an", "my", "your", "his", "her", "our", "their", "this", "that", "these", "those", "its", "some", "any", "no",
        )

        /** misaki `apply_stress`. */
        fun applyStress(ps: String, stress: Double?): String {
            if (stress == null) return ps
            val hasP = PRIMARY in ps
            val hasS = SECONDARY in ps
            return when {
                stress < -1 -> ps.replace(PRIMARY.toString(), "").replace(SECONDARY.toString(), "")
                stress == -1.0 || (stress in listOf(0.0, -0.5) && hasP) ->
                    ps.replace(SECONDARY.toString(), "").replace(PRIMARY, SECONDARY)
                stress in listOf(0.0, 0.5, 1.0) && !hasP && !hasS ->
                    if (ps.none { it in VOWELS }) ps else restress(SECONDARY + ps)
                stress >= 1 && !hasP && hasS -> ps.replace(SECONDARY, PRIMARY)
                stress > 1 && !hasP && !hasS -> if (ps.none { it in VOWELS }) ps else restress(PRIMARY + ps)
                else -> ps
            }
        }

        /** Moves each stress mark to just before the next vowel. */
        private fun restress(ps: String): String {
            val chars = ps.toMutableList()
            val positions = ArrayList<Pair<Double, Char>>()
            chars.forEachIndexed { i, c ->
                if (c == PRIMARY || c == SECONDARY) {
                    val j = (i until chars.size).firstOrNull { chars[it] in VOWELS } ?: i
                    positions += (j - 0.5) to c
                } else positions += i.toDouble() to c
            }
            return positions.sortedBy { it.first }.map { it.second }.joinToString("")
        }
    }
}
