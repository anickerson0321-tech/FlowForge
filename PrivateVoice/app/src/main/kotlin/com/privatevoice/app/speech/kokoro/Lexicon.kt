package com.privatevoice.app.speech.kokoro

import java.io.BufferedReader
import java.io.InputStream

/**
 * US English pronunciation lexicon (misaki gold + silver, Apache-2.0).
 *
 * Each value is either a plain phoneme string or a part-of-speech table encoded
 * as `TAG=phonemes|TAG=phonemes` (an empty value means "spell it out").
 * Contains no user data; loaded read-only from APK assets.
 */
class Lexicon private constructor(private val entries: HashMap<String, String>) {

    val size: Int get() = entries.size

    /** Raw entry for the exact key (case-sensitive), or null. */
    fun raw(word: String): String? = entries[word]

    fun contains(word: String): Boolean = entries.containsKey(word)

    /** Exact, then lowercase, then Capitalized key (misaki "grow_dictionary" behaviour, done lazily). */
    fun find(word: String): String? {
        entries[word]?.let { return it }
        if (word.length < 2) return null
        val lower = word.lowercase()
        if (lower != word && word == word.replaceFirstChar { it.uppercase() } ) entries[lower]?.let { return it }
        val cap = lower.replaceFirstChar { it.uppercase() }
        if (cap != word && word == lower) entries[cap]?.let { return it }
        return null
    }

    companion object {
        fun load(input: InputStream): Lexicon {
            val map = HashMap<String, String>(200_000)
            BufferedReader(input.reader(Charsets.UTF_8), 1 shl 16).use { r ->
                while (true) {
                    val line = r.readLine() ?: break
                    val tab = line.indexOf('\t')
                    if (tab <= 0) continue
                    val key = line.substring(0, tab)
                    if (!map.containsKey(key)) map[key] = line.substring(tab + 1)
                }
            }
            return Lexicon(map)
        }

        fun fromMap(map: Map<String, String>) = Lexicon(HashMap(map))

        /** Resolves a POS table to one pronunciation. Returns "" for "spell out", null if the table is not a table. */
        fun choose(raw: String, tag: String?): String {
            if (!raw.contains('=')) return raw
            val table = HashMap<String, String>()
            for (part in raw.split('|')) {
                val eq = part.indexOf('=')
                if (eq > 0) table[part.substring(0, eq)] = part.substring(eq + 1)
            }
            if (tag != null) {
                table[tag]?.let { return it }
                parentTag(tag)?.let { p -> table[p]?.let { return it } }
            }
            return table["DEFAULT"] ?: ""
        }

        private fun parentTag(tag: String): String? = when {
            tag.startsWith("VB") -> "VERB"
            tag.startsWith("NN") -> "NOUN"
            tag.startsWith("ADV") || tag.startsWith("RB") -> "ADV"
            tag.startsWith("ADJ") || tag.startsWith("JJ") -> "ADJ"
            else -> null
        }
    }
}
