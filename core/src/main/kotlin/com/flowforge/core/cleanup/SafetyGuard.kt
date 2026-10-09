package com.flowforge.core.cleanup

import com.flowforge.core.text.Lexicon
import com.flowforge.core.text.Token
import com.flowforge.core.text.TokenKind
import com.flowforge.core.text.Tokenizer

/**
 * Detects meaning-bearing content that a transformation removed or invented.
 *
 * Protected content:
 *  - numbers (digits or number words), weekdays, months, relative days ("tomorrow")
 *  - negations ("not", "never", "don't", ...)
 *  - names: capitalized words that are not ordinary sentence starters, and dictionary terms
 *
 * Numbers, dates and negations must be neither removed nor added. Names must not be removed
 * (capitalizing a word is allowed). A step may *declare* removals it made on purpose, such as the
 * "Thursday" replaced in "Thursday, I mean Friday"; anything else counts as a violation.
 */
object SafetyGuard {
    enum class Category { NUMBER, DATE, NEGATION, NAME }

    data class Item(val category: Category, val value: String)

    data class Violation(val item: Item, val removed: Boolean) {
        override fun toString(): String =
            (if (removed) "would remove " else "would add ") + "${item.category.name.lowercase()} \"${item.value}\""
    }

    fun protectedItems(tokens: List<Token>): Map<Item, Int> {
        val bag = HashMap<Item, Int>()
        for (t in tokens) {
            if (t.synthetic || !t.isWordLike) continue
            val category = categorize(t) ?: continue
            val item = Item(category, normalizeValue(t, category))
            bag[item] = (bag[item] ?: 0) + 1
        }
        return bag
    }

    fun protectedItems(text: String): Map<Item, Int> = protectedItems(Tokenizer.tokenize(text))

    /**
     * Negations are compared by count (polarity), so "dunno" -> "don't know" or "don't" -> "do not"
     * pass while dropping a negation fails. Simple number words compare equal to their digits.
     */
    private fun normalizeValue(t: Token, category: Category): String = when (category) {
        Category.NEGATION -> "negation"
        Category.NUMBER -> NUMBER_VALUES[t.lower] ?: t.lower.trimEnd('.').replace(",", "")
        else -> t.lower.trim('\'', '’')
    }

    private val NUMBER_VALUES: Map<String, String> = listOf(
        "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven",
        "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen", "twenty",
    ).withIndex().associate { (i, w) -> w to i.toString() } +
        mapOf("thirty" to "30", "forty" to "40", "fifty" to "50", "sixty" to "60", "seventy" to "70", "eighty" to "80", "ninety" to "90")

    private fun categorize(t: Token): Category? {
        val w = t.lower
        return when {
            // Dictionary terms are user-chosen spellings; protect them as a unit even if they contain digits.
            t.locked -> Category.NAME
            Lexicon.isNegation(w) -> Category.NEGATION
            t.kind == TokenKind.NUMBER || w in Lexicon.NUMBER_WORDS -> Category.NUMBER
            w in Lexicon.WEEKDAYS || w in Lexicon.MONTHS || w in Lexicon.RELATIVE_DAYS -> Category.DATE
            t.text.first().isUpperCase() && w != "i" && !w.startsWith("i'") && !isCommonStarter(w) -> Category.NAME
            else -> null
        }
    }

    private fun isCommonStarter(w: String): Boolean =
        w in Lexicon.LOWERCASABLE_STARTERS || w in Lexicon.INTRODUCTORY || w in Lexicon.FILLERS ||
            w in Lexicon.QUESTION_STARTERS || w in Lexicon.GREETINGS || w in Lexicon.SUBJECT_PRONOUNS ||
            w in COMMON_SENTENCE_STARTERS

    private val COMMON_SENTENCE_STARTERS = setOf(
        "actually", "basically", "like", "let's", "let", "make", "please", "thank", "here", "there", "now",
        "first", "second", "third", "next", "last", "lastly", "what's", "that's", "it's", "good", "great",
        "sure", "alright", "right", "hey", "oh", "wait", "scratch", "change", "send", "call", "check",
        "remember", "remind", "tell", "ask", "get", "go", "take", "bring", "need", "maybe", "just", "only",
        "even", "still", "since", "after", "before", "while", "until", "though", "although", "unless",
        "once", "every", "each", "any", "both", "either", "most", "many", "few", "several", "such", "sounds",
        "looks", "seems", "agenda", "number", "bullet", "item", "review", "hire", "ship", "buy", "update",
        "follow", "schedule", "book", "cancel", "confirm", "don't", "do", "does", "did", "dear",
    )

    /**
     * Compares protected content between [before] and [after]. Counts in [declaredRemovals] are
     * subtracted from "removed" findings.
     */
    fun violations(
        before: Map<Item, Int>,
        after: Map<Item, Int>,
        declaredRemovals: Map<Item, Int> = emptyMap(),
    ): List<Violation> {
        val out = ArrayList<Violation>()
        for ((item, count) in before) {
            val missing = count - (after[item] ?: 0) - (declaredRemovals[item] ?: 0)
            if (missing > 0) out += Violation(item, removed = true)
        }
        for ((item, count) in after) {
            if (item.category == Category.NAME) continue // capitalizing a word is not invention
            val extra = count - (before[item] ?: 0)
            if (extra > 0) out += Violation(item, removed = false)
        }
        return out
    }

    fun bagOf(tokens: List<Token>): Map<Item, Int> = protectedItems(tokens)
}
