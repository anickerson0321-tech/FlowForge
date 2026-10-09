package com.flowforge.core.dictionary

import com.flowforge.core.text.Token
import com.flowforge.core.text.TokenKind
import com.flowforge.core.text.Tokenizer

enum class EntryCategory { NAME, PLACE, TERM, ACRONYM, OTHER }

/**
 * A user vocabulary item.
 *
 * @property term the exact written form to produce ("Kubernetes", "Siobhan", "API").
 * @property spokenForms how the recognizer tends to transcribe it ("cooper net ease", "shiv on").
 *   The term itself is always matched too, so "kubernetes" is re-cased to "Kubernetes".
 */
data class DictionaryEntry(
    val id: String,
    val term: String,
    val spokenForms: List<String> = emptyList(),
    val category: EntryCategory = EntryCategory.TERM,
) {
    init {
        require(term.isNotBlank()) { "term must not be blank" }
    }
}

data class Replacement(val from: String, val to: String, val entryId: String)

data class DictionaryApplication(val tokens: List<Token>, val replacements: List<Replacement>)

/**
 * Applies user vocabulary to tokenized text.
 *
 * Safety properties (covered by tests):
 *  - Only whole tokens/phrases match; "cat" never rewrites "category".
 *  - Matching ignores case but never crosses punctuation or line breaks.
 *  - Longest spoken form wins when several overlap.
 *  - Replaced tokens are [Token.locked] so later formatting cannot alter them.
 */
class PersonalDictionary(entries: List<DictionaryEntry>) {
    private class Pattern(val words: List<String>, val entry: DictionaryEntry)

    val entries: List<DictionaryEntry> = entries.toList()

    private val patternsByFirstWord: Map<String, List<Pattern>> = entries
        .flatMap { e -> (e.spokenForms + e.term).mapNotNull { form -> normalize(form)?.let { Pattern(it, e) } } }
        .distinctBy { it.words to it.entry.id }
        .groupBy { it.words.first() }
        .mapValues { (_, list) -> list.sortedByDescending { it.words.size } }

    fun apply(tokens: List<Token>): DictionaryApplication {
        if (patternsByFirstWord.isEmpty()) return DictionaryApplication(tokens, emptyList())
        val out = ArrayList<Token>(tokens.size)
        val replacements = ArrayList<Replacement>()
        var i = 0
        while (i < tokens.size) {
            val t = tokens[i]
            if (!t.isWordLike || t.locked) {
                out += t
                i++
                continue
            }
            val (key, possessive) = splitPossessive(t.lower)
            val candidates = patternsByFirstWord[key] ?: patternsByFirstWord[t.lower]
            val match = candidates?.firstOrNull { matches(tokens, i, it.words) }
            if (match == null) {
                out += t
                i++
                continue
            }
            val n = match.words.size
            val last = tokens[i + n - 1]
            val suffix = if (n == 1) possessive else splitPossessive(last.lower).second
            val written = match.entry.term + suffix
            val spoken = tokens.subList(i, i + n).joinToString(" ") { it.text }
            if (spoken != written) replacements += Replacement(spoken, written, match.entry.id)
            out += Token(written, if (written.any { it.isDigit() }) TokenKind.NUMBER else TokenKind.WORD, locked = true)
            i += n
        }
        return DictionaryApplication(out, replacements)
    }

    private fun matches(tokens: List<Token>, start: Int, words: List<String>): Boolean {
        if (start + words.size > tokens.size) return false
        for (k in words.indices) {
            val t = tokens[start + k]
            if (!t.isWordLike || t.locked) return false
            val lower = t.lower
            val isLast = k == words.lastIndex
            val ok = lower == words[k] || (isLast && splitPossessive(lower).first == words[k])
            if (!ok) return false
        }
        return true
    }

    companion object {
        val EMPTY = PersonalDictionary(emptyList())

        /** Lowercased word list for a written/spoken form, or null if it contains no words. */
        fun normalize(form: String): List<String>? {
            val words = Tokenizer.tokenize(form).filter { it.isWordLike }.map { it.lower }
            return words.takeIf { it.isNotEmpty() }
        }

        private fun splitPossessive(lower: String): Pair<String, String> = when {
            lower.endsWith("'s") && lower.length > 2 -> lower.dropLast(2) to "'s"
            lower.endsWith("’s") && lower.length > 2 -> lower.dropLast(2) to "’s"
            else -> lower to ""
        }

        /**
         * Common English words; a spoken form equal to one of these would rewrite ordinary speech,
         * so the UI warns before saving it.
         */
        val COMMON_WORDS: Set<String> = setOf(
            "a", "about", "after", "all", "also", "an", "and", "any", "are", "as", "at", "back", "be", "because",
            "been", "before", "being", "but", "by", "can", "come", "could", "day", "did", "do", "down", "even",
            "first", "for", "from", "get", "give", "go", "good", "great", "had", "has", "have", "he", "her",
            "here", "him", "his", "how", "i", "if", "in", "into", "is", "it", "its", "just", "know", "like",
            "look", "make", "man", "many", "me", "more", "most", "my", "new", "no", "not", "now", "of", "on",
            "one", "only", "or", "other", "our", "out", "over", "people", "please", "right", "said", "say",
            "see", "she", "so", "some", "take", "than", "thank", "that", "the", "their", "them", "then",
            "there", "these", "they", "thing", "think", "this", "time", "to", "two", "up", "us", "use", "very",
            "want", "was", "way", "we", "well", "were", "what", "when", "which", "who", "will", "with", "work",
            "would", "year", "yes", "you", "your", "rock", "mark", "bill", "will", "may", "june", "art", "rose",
            "jack", "bob", "pat", "sky", "dawn", "grace", "hope", "joy", "summer", "april", "august", "victor",
        )

        fun warnsAsCommonWord(spokenForm: String): Boolean {
            val words = normalize(spokenForm) ?: return false
            return words.size == 1 && words[0] in COMMON_WORDS
        }
    }
}
