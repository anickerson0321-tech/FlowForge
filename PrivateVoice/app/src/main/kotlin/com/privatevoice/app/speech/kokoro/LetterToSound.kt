package com.privatevoice.app.speech.kokoro

/**
 * Rule-based English letter-to-sound fallback for words missing from the
 * lexicon (unusual names, new slang). Produces misaki-alphabet phonemes with
 * primary stress on the first syllable. It is an approximation by design:
 * dictionary pronunciations are always preferred.
 */
object LetterToSound {
    private const val VOWEL_LETTERS = "aeiouy"
    private val PH_VOWELS = "AIOWYaiuæɑɔəɛɜɪʊʌᵻ".toSet()

    fun convert(wordIn: String): String {
        val w = wordIn.lowercase().filter { it in 'a'..'z' }
        if (w.isEmpty()) return ""
        if (w.none { it in VOWEL_LETTERS }) return spell(w)
        val out = StringBuilder()
        var i = 0
        var syllable = 0
        fun at(k: Int) = if (k in w.indices) w[k] else ' '
        fun isV(c: Char) = c in VOWEL_LETTERS
        fun isC(c: Char) = c in 'a'..'z' && !isV(c)
        fun rest() = w.substring(i)
        fun starts(s: String) = w.startsWith(s, i)
        fun atEnd(len: Int) = i + len == w.length

        while (i < w.length) {
            val c = w[i]
            // ---- endings
            when {
                atEnd(4) && (starts("tion") || starts("sion")) -> { out.append(if (starts("tion") || isC(at(i - 1))) "ʃən" else "ʒən"); i += 4; continue }
                atEnd(5) && (starts("cious") || starts("tious")) -> { out.append("ʃəs"); i += 5; continue }
                atEnd(4) && starts("ture") -> { out.append("ʧəɹ"); i += 4; continue }
                atEnd(3) && starts("ous") -> { out.append("əs"); i += 3; continue }
                atEnd(2) && starts("le") && i > 0 && isC(at(i - 1)) -> { out.append("ᵊl"); i += 2; continue }
                atEnd(2) && starts("ey") -> { out.append("i"); i += 2; continue }
                atEnd(2) && starts("ie") && w.length > 3 -> { out.append("i"); i += 2; continue }
                atEnd(2) && starts("es") && i > 1 && at(i - 1) in "sxz" -> { out.append("ᵻz"); i += 2; continue }
                atEnd(1) && c == 'e' && i > 0 && w.count { isV(it) } > 1 -> { i += 1; continue }
                atEnd(1) && c == 'y' && i > 0 -> { out.append(if (w.dropLast(1).any { isV(it) }) "i" else "I"); i += 1; continue }
            }
            if (isV(c) || (c == 'y' && i > 0 && !isV(at(i + 1)))) {
                val first = syllable == 0
                val (ph, len) = vowel(w, i, first)
                out.append(ph)
                i += len
                syllable++
                continue
            }
            // ---- consonants
            val (ph, len) = when {
                starts("tch") -> "ʧ" to 3
                starts("sch") -> "sk" to 3
                starts("ch") -> "ʧ" to 2
                starts("sh") -> "ʃ" to 2
                starts("th") -> "θ" to 2
                starts("ph") -> "f" to 2
                starts("wh") -> "w" to 2
                starts("ck") -> "k" to 2
                starts("ng") -> "ŋ" to 2
                starts("nk") -> "ŋk" to 2
                starts("qu") -> "kw" to 2
                starts("dg") -> "ʤ" to 2
                starts("gh") -> (if (i == 0) "ɡ" else "") to 2
                i == 0 && starts("kn") -> "n" to 2
                i == 0 && starts("wr") -> "ɹ" to 2
                i == 0 && starts("gn") -> "n" to 2
                i == 0 && starts("ps") -> "s" to 2
                c == 'x' -> (if (i == 0) "z" else "ks") to 1
                c == 'c' -> (if (at(i + 1) in "eiy") "s" else "k") to if (at(i + 1) == 'c' && at(i + 2) !in "eiy") 2 else 1
                c == 'g' -> (if (at(i + 1) in "eiy" && i > 0) "ʤ" else if (at(i + 1) in "eiy" && at(i + 1) != 'e') "ʤ" else "ɡ") to if (at(i + 1) == 'g') 2 else 1
                c == 'j' -> "ʤ" to 1
                c == 'y' -> "j" to 1
                c == 'r' -> "ɹ" to if (at(i + 1) == 'r') 2 else 1
                c == 's' && i > 0 && isV(at(i - 1)) && isV(at(i + 1)) -> "z" to 1
                c == 'q' -> "k" to 1
                c == 'w' -> "w" to 1
                c == 'h' -> (if (isV(at(i + 1))) "h" else "") to 1
                else -> c.toString() to if (at(i + 1) == c) 2 else 1
            }
            out.append(ph)
            i += len
        }
        return stressFirst(out.toString())
    }

    /** Returns phonemes for the vowel group starting at [i] and the number of letters consumed. */
    private fun vowel(w: String, i: Int, first: Boolean): Pair<String, Int> {
        fun s(x: String) = w.startsWith(x, i)
        fun at(k: Int) = if (k in w.indices) w[k] else ' '
        fun isV(c: Char) = c in VOWEL_LETTERS
        val stressedEr = if (first) "ɜɹ" else "əɹ"
        // r-colored groups
        when {
            s("eigh") -> return "A" to 4
            s("igh") -> return "I" to 3
            s("augh") -> return "ɔ" to 4
            s("air") -> return "ɛɹ" to 3
            s("ear") -> return "ɪɹ" to 3
            s("eer") -> return "ɪɹ" to 3
            s("our") -> return "Wɹ" to 3
            s("oor") -> return "ɔɹ" to 3
        }
        val c = w[i]
        if (at(i + 1) == 'r' && !isV(at(i + 2)) && at(i + 2) != 'r') {
            return when (c) {
                'a' -> "ɑɹ"; 'o' -> "ɔɹ"; else -> stressedEr
            } to 2
        }
        if (at(i + 1) == 'r' && (isV(at(i + 2)) || at(i + 2) == 'r')) {
            return when (c) {
                'a' -> "ɛɹ"; 'e' -> "ɛɹ"; 'i', 'y' -> "ɪɹ"; 'o' -> "ɔɹ"; else -> "ʊɹ"
            } to if (at(i + 2) == 'r') 3 else 2
        }
        when {
            s("ai") || s("ay") -> return "A" to 2
            s("ei") || s("ey") -> return "A" to 2
            s("ee") || s("ea") -> return "i" to 2
            s("ie") -> return "i" to 2
            s("oa") || s("oe") -> return "O" to 2
            s("oo") -> return "u" to 2
            s("ou") -> return "W" to 2
            s("ow") -> return (if (i + 2 == w.length) "O" else "W") to 2
            s("oi") || s("oy") -> return "Y" to 2
            s("au") || s("aw") -> return "ɔ" to 2
            s("ew") || s("ue") || s("ui") || s("eu") -> return "u" to 2
            s("ia") -> return "iə" to 2
            s("io") -> return "iO" to 2
            s("ya") && i > 0 -> return "jə" to 2
        }
        val end = i + 1 == w.length
        if (end) return when (c) { 'a' -> "ə"; 'i' -> "i"; 'o' -> "O"; 'u' -> "u"; 'y' -> "i"; else -> "ə" } to 1
        // open syllable: V C V, or magic e: V C e#
        val c1 = at(i + 1)
        val magicE = !isV(c1) && at(i + 2) == 'e' && i + 3 == w.length
        val open = !isV(c1) && isV(at(i + 2)) && c1 != 'x'
        val long = magicE || (first && open)
        val ph = if (long) {
            when (c) { 'a' -> "A"; 'e' -> "i"; 'i', 'y' -> "I"; 'o' -> "O"; else -> "u" }
        } else if (first) {
            when (c) { 'a' -> "æ"; 'e' -> "ɛ"; 'i', 'y' -> "ɪ"; 'o' -> "ɑ"; else -> "ʌ" }
        } else {
            when (c) { 'i', 'y' -> "ɪ"; 'e' -> "ɛ"; else -> "ə" }
        }
        return ph to 1
    }

    private fun stressFirst(ps: String): String {
        val idx = ps.indexOfFirst { it in PH_VOWELS }
        if (idx < 0) return ps
        return ps.substring(0, idx) + "ˈ" + ps.substring(idx)
    }

    private val LETTERS = mapOf(
        'a' to "ˈA", 'b' to "bˈi", 'c' to "sˈi", 'd' to "dˈi", 'e' to "ˈi", 'f' to "ˈɛf", 'g' to "ʤˈi", 'h' to "ˈAʧ",
        'i' to "ˈI", 'j' to "ʤˈA", 'k' to "kˈA", 'l' to "ˈɛl", 'm' to "ˈɛm", 'n' to "ˈɛn", 'o' to "ˈO", 'p' to "pˈi",
        'q' to "kjˈu", 'r' to "ˈɑɹ", 's' to "ˈɛs", 't' to "tˈi", 'u' to "jˈu", 'v' to "vˈi", 'w' to "dˈʌbᵊlju",
        'x' to "ˈɛks", 'y' to "wˈI", 'z' to "zˈi",
    )

    /** Spells a word letter by letter (used for acronyms and vowel-less tokens). */
    fun spell(word: String): String = word.lowercase().filter { it in 'a'..'z' }.map { LETTERS.getValue(it) }.joinToString(" ")
}
