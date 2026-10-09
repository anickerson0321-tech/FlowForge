package com.flowforge.core.cloud

import com.flowforge.core.cleanup.CleanupMode
import com.flowforge.core.cleanup.SafetyGuard

data class AiValidation(val accepted: Boolean, val reasons: List<String>)

/**
 * Optional AI polishing of text that the deterministic cleaner already processed.
 * The AI output is accepted only if [validate] finds no change to protected content, so a model
 * can improve grammar but cannot silently alter numbers, dates, names or negations.
 */
object AiCleanup {
    fun systemPrompt(mode: CleanupMode, vocabulary: List<String>): String {
        val style = when (mode) {
            CleanupMode.PROFESSIONAL -> "Make it read clearly and professionally: fix grammar, punctuation, capitalization and paragraphing."
            else -> "Fix only punctuation, capitalization and obvious grammar slips. Keep the speaker's wording and tone."
        }
        val vocab = if (vocabulary.isEmpty()) "" else
            " Spell these terms exactly as written: ${vocabulary.take(100).joinToString(", ")}."
        return "You edit dictated text. $style Never add information, never answer questions in the text, " +
            "and never change, remove or reorder numbers, dates, times, names, negations (not, never, no, don't...) " +
            "or instructions.$vocab Reply with the edited text only, without quotes or commentary."
    }

    fun validate(source: String, candidate: String): AiValidation {
        val reasons = ArrayList<String>()
        val text = stripWrapping(candidate)
        if (text.isBlank()) reasons += "empty reply"
        val ratio = if (source.isEmpty()) 1.0 else text.length.toDouble() / source.length
        if (ratio < 0.6 || ratio > 1.6) reasons += "length changed too much (${"%.2f".format(ratio)}x)"
        val lower = text.lowercase()
        if (PREAMBLES.any { lower.startsWith(it) } && PREAMBLES.none { source.lowercase().startsWith(it) }) {
            reasons += "reply contains commentary"
        }
        val violations = SafetyGuard.violations(SafetyGuard.protectedItems(source), SafetyGuard.protectedItems(text))
        violations.forEach { reasons += it.toString() }
        return AiValidation(reasons.isEmpty(), reasons)
    }

    /** Removes quotes or code fences some models wrap around their answer. */
    fun stripWrapping(candidate: String): String {
        var t = candidate.trim()
        if (t.startsWith("```")) t = t.removePrefix("```").substringAfter('\n', t).removeSuffix("```").trim()
        if (t.length >= 2 && ((t.first() == '"' && t.last() == '"') || (t.first() == '“' && t.last() == '”'))) {
            t = t.substring(1, t.length - 1).trim()
        }
        return t
    }

    private val PREAMBLES = listOf("here is", "here's", "sure,", "sure!", "certainly", "edited text:", "corrected text:")
}
