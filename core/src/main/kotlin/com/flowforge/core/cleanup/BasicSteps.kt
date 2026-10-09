package com.flowforge.core.cleanup

import com.flowforge.core.text.Lexicon
import com.flowforge.core.text.Token
import com.flowforge.core.text.TokenKind

// ---------------------------------------------------------------------------------------------
// Shared helpers
// ---------------------------------------------------------------------------------------------

internal fun Token.isWord(vararg lower: String) = isWordLike && this.lower in lower

internal fun List<Token>.wordAt(i: Int): Token? = getOrNull(i)?.takeIf { it.isWordLike }

/** True when index [i] is the first word-like token of its sentence (synthetic list numbers ignored). */
internal fun List<Token>.isSentenceInitial(i: Int): Boolean {
    var j = i - 1
    while (j >= 0) {
        val t = this[j]
        if (t.isBoundary) return true
        if (t.synthetic) {
            j--
            continue
        }
        if (t.kind == TokenKind.PUNCT && t.text in OPENERS) {
            j--
            continue
        }
        return false
    }
    return true
}

internal val OPENERS = setOf("(", "[", "\"", "“")

/** Index of the first token of the sentence containing [i]. */
internal fun List<Token>.sentenceStart(i: Int): Int {
    var j = i - 1
    while (j >= 0 && !this[j].isBoundary) j--
    return j + 1
}

/** Index one past the boundary token that ends the sentence containing [i] (or size). */
internal fun List<Token>.sentenceEndExclusive(i: Int): Int {
    var j = i
    while (j < size && !this[j].isBoundary) j++
    return if (j < size) j + 1 else size
}

internal fun capitalizeFirst(text: String): String =
    if (text.isEmpty() || text[0].isUpperCase()) text else text[0].uppercaseChar() + text.substring(1)

internal fun Token.capitalized(): Token =
    if (locked || synthetic) this else copy(text = capitalizeFirst(text))

/** Removes punctuation left dangling after words were deleted. */
internal fun tidy(tokens: List<Token>): List<Token> {
    val out = ArrayList<Token>(tokens.size)
    for (t in tokens) {
        if (t.kind == TokenKind.PUNCT && t.text in SOFT_PUNCT) {
            val prev = out.lastOrNull()
            // Leading comma/semicolon/colon at the start of a sentence or line.
            if (prev == null || prev.isBoundary) continue
            // ",," or ", ," -> ","; ".," -> "."
            if (prev.kind == TokenKind.PUNCT && (prev.text in SOFT_PUNCT || prev.isSentenceEnd)) continue
        }
        if (t.isSentenceEnd) {
            val prev = out.lastOrNull()
            // ", ." -> "."
            if (prev != null && prev.kind == TokenKind.PUNCT && prev.text in SOFT_PUNCT) out.removeAt(out.lastIndex)
            val prev2 = out.lastOrNull()
            // ". ." or "? ." -> keep the first terminal mark ("?!" is left alone).
            if (prev2 != null && prev2.isSentenceEnd && t.text != "..." && prev2.text != "..." &&
                (t.text == "." || t.text == prev2.text)
            ) continue
            // Sentence end with nothing before it.
            if (prev2 == null || prev2.kind == TokenKind.NEWLINE) continue
        }
        out += t
    }
    // Trailing/leading newlines inside one dictation are kept; leading ones are dropped.
    while (out.isNotEmpty() && out.first().kind == TokenKind.NEWLINE) out.removeAt(0)
    return out
}

internal val SOFT_PUNCT = setOf(",", ";", ":")

// ---------------------------------------------------------------------------------------------
// Spoken punctuation ("comma", "new paragraph", ...)
// ---------------------------------------------------------------------------------------------

internal object SpokenPunctuationStep : CleanupStep {
    override val name = "spoken punctuation"

    private sealed class Emit {
        data class Punct(val text: String) : Emit()
        data class Newline(val count: Int) : Emit()
    }

    private val ALWAYS: List<Pair<List<String>, Emit>> = listOf(
        listOf("new", "paragraph") to Emit.Newline(2),
        listOf("next", "paragraph") to Emit.Newline(2),
        listOf("new", "line") to Emit.Newline(1),
        listOf("newline") to Emit.Newline(1),
        listOf("next", "line") to Emit.Newline(1),
        listOf("question", "mark") to Emit.Punct("?"),
        listOf("exclamation", "point") to Emit.Punct("!"),
        listOf("exclamation", "mark") to Emit.Punct("!"),
        listOf("full", "stop") to Emit.Punct("."),
        listOf("comma") to Emit.Punct(","),
        listOf("semicolon") to Emit.Punct(";"),
        listOf("semi", "colon") to Emit.Punct(";"),
        listOf("open", "parenthesis") to Emit.Punct("("),
        listOf("open", "paren") to Emit.Punct("("),
        listOf("close", "parenthesis") to Emit.Punct(")"),
        listOf("close", "paren") to Emit.Punct(")"),
        listOf("open", "quote") to Emit.Punct("\""),
        listOf("begin", "quote") to Emit.Punct("\""),
        listOf("close", "quote") to Emit.Punct("\""),
        listOf("end", "quote") to Emit.Punct("\""),
        listOf("unquote") to Emit.Punct("\""),
        listOf("ellipsis") to Emit.Punct("..."),
        listOf("dot", "dot", "dot") to Emit.Punct("..."),
    ).sortedByDescending { it.first.size }

    /** Ambiguous words: converted only at the end of the dictation or right before a line break. */
    private val RESTRICTED: List<Pair<List<String>, Emit>> = listOf(
        listOf("period") to Emit.Punct("."),
        listOf("colon") to Emit.Punct(":"),
        listOf("dot") to Emit.Punct("."),
    )

    /** "the comma", "a period", "grace period": the word is being talked about, not dictated. */
    private val NOUN_CONTEXT = setOf(
        "the", "a", "an", "this", "that", "each", "every", "one", "another", "per", "grace", "trial", "time",
        "waiting", "billing", "reporting", "probation", "cooling", "no", "any", "some", "extra", "missing",
        "oxford", "serial", "inverted", "your", "my", "his", "her", "their", "our", "its", "sigmoid",
    )

    private val DOMAINS = setOf("com", "org", "net", "io", "gov", "edu", "co", "ai", "dev", "app")

    override fun apply(tokens: List<Token>, options: CleanupOptions): StepOutput {
        val out = ArrayList<Token>(tokens.size)
        val edits = ArrayList<AppliedEdit>()
        var i = 0
        while (i < tokens.size) {
            val t = tokens[i]
            if (!t.isWordLike || t.locked) {
                out += t
                i++
                continue
            }
            // "example dot com" -> "example.com"
            if (t.lower == "dot" && out.lastOrNull()?.isWordLike == true && tokens.wordAt(i + 1)?.lower in DOMAINS) {
                val host = out.removeAt(out.lastIndex)
                val tld = tokens[i + 1]
                out += Token.word(host.text + "." + tld.lower)
                edits += AppliedEdit(name, "\"${host.text} dot ${tld.text}\" → \"${host.text}.${tld.lower}\"")
                i += 2
                continue
            }
            // "literal comma" keeps the word.
            if (t.lower == "literal" && tokens.wordAt(i + 1) != null && matchAny(tokens, i + 1, ALWAYS + RESTRICTED) != null) {
                val (words, _) = matchAny(tokens, i + 1, ALWAYS + RESTRICTED)!!
                out.addAll(tokens.subList(i + 1, i + 1 + words.size))
                edits += AppliedEdit(name, "kept literal \"${words.joinToString(" ")}\"")
                i += 1 + words.size
                continue
            }
            val prevWord = out.lastOrNull()?.takeIf { it.isWordLike }?.lower
            val match = matchAny(tokens, i, ALWAYS)
                ?: matchAny(tokens, i, RESTRICTED)?.takeIf { (words, _) -> restrictedAllowed(tokens, i + words.size) }
            if (match == null || prevWord in NOUN_CONTEXT) {
                out += t
                i++
                continue
            }
            val (words, emit) = match
            when (emit) {
                is Emit.Newline -> {
                    // A recognizer-inserted period before "new line" is fine; drop a dangling comma.
                    if (out.lastOrNull()?.text == ",") out.removeAt(out.lastIndex)
                    out += Token.newline(emit.count)
                }
                is Emit.Punct -> {
                    val last = out.lastOrNull()
                    if (last != null && last.kind == TokenKind.PUNCT && last.text == "," && emit.text != "(" && emit.text != "\"") {
                        out.removeAt(out.lastIndex) // "word, period" -> "word."
                    }
                    if (!(out.lastOrNull()?.kind == TokenKind.PUNCT && out.last().text == emit.text && emit.text != "\"")) {
                        out += Token.punct(emit.text)
                    }
                }
            }
            edits += AppliedEdit(name, "\"${words.joinToString(" ")}\" → ${describe(emit)}")
            i += words.size
            // Recognizers often add their own "." or "," after the spoken word; drop that artifact.
            val next = tokens.getOrNull(i)
            if (next != null && next.kind == TokenKind.PUNCT && next.text in setOf(".", ",") &&
                !(emit is Emit.Punct && (emit.text == "(" || emit.text == "\""))
            ) {
                i++
            }
        }
        return StepOutput(out, edits)
    }

    private fun describe(emit: Emit) = when (emit) {
        is Emit.Newline -> if (emit.count == 2) "paragraph break" else "line break"
        is Emit.Punct -> "\"${emit.text}\""
    }

    private fun matchAny(tokens: List<Token>, i: Int, table: List<Pair<List<String>, Emit>>): Pair<List<String>, Emit>? =
        table.firstOrNull { (words, _) ->
            words.indices.all { k -> tokens.wordAt(i + k)?.let { !it.locked && it.lower == words[k] } == true }
        }

    private fun restrictedAllowed(tokens: List<Token>, next: Int): Boolean {
        var j = next
        while (j < tokens.size && tokens[j].kind == TokenKind.PUNCT) j++
        if (j >= tokens.size) return true
        if (tokens[j].kind == TokenKind.NEWLINE) return true
        val w = tokens[j]
        if (!w.isWordLike) return false
        // Followed by another spoken command ("period new line").
        return matchAny(tokens, j, ALWAYS)?.second is Emit.Newline
    }
}

// ---------------------------------------------------------------------------------------------
// Fillers ("um", "uh")
// ---------------------------------------------------------------------------------------------

internal object FillerStep : CleanupStep {
    override val name = "filler removal"

    override fun apply(tokens: List<Token>, options: CleanupOptions): StepOutput {
        val removed = ArrayList<Token>()
        val kept = ArrayList<Token>(tokens.size)
        var skipComma = false
        for ((idx, t) in tokens.withIndex()) {
            if (skipComma) {
                skipComma = false
                if (t.text == ",") continue
            }
            if (t.isWordLike && !t.locked && t.lower in Lexicon.FILLERS) {
                removed += t
                // "I, um, think" -> both commas only framed the filler.
                if (kept.lastOrNull()?.text == "," && tokens.getOrNull(idx + 1)?.text == ",") {
                    kept.removeAt(kept.lastIndex)
                    skipComma = true
                }
                continue
            }
            kept += t
        }
        if (removed.isEmpty()) return StepOutput(tokens)
        return StepOutput(tidy(kept), listOf(AppliedEdit(name, "removed ${removed.joinToString(", ") { "\"${it.text}\"" }}")))
    }
}

// ---------------------------------------------------------------------------------------------
// Stutters and repeated phrases ("the the", "we should we should")
// ---------------------------------------------------------------------------------------------

internal object RepetitionStep : CleanupStep {
    override val name = "repeated words"

    override fun apply(tokens: List<Token>, options: CleanupOptions): StepOutput {
        var current = tokens
        val edits = ArrayList<AppliedEdit>()
        val declared = ArrayList<Token>()
        // Longer phrases first so "we should we should" is handled as a phrase.
        for (n in 4 downTo 1) {
            var i = 0
            val out = ArrayList<Token>(current.size)
            while (i < current.size) {
                val repeatEnd = repeatedRun(current, i, n)
                if (repeatEnd > i + n) {
                    val first = current.subList(i, i + n)
                    out.addAll(first)
                    // Skip every extra copy (and commas between copies).
                    val dropped = current.subList(i + n, repeatEnd).filter { it.isWordLike }
                    declared.addAll(dropped)
                    edits += AppliedEdit(name, "\"${first.joinToString(" ") { it.text }}\" repeated")
                    i = repeatEnd
                } else {
                    out += current[i]
                    i++
                }
            }
            current = out
        }
        if (edits.isEmpty()) return StepOutput(tokens)
        return StepOutput(current, edits, declared)
    }

    /** End index (exclusive) of consecutive copies of the [n]-word phrase at [start], or start + n. */
    private fun repeatedRun(tokens: List<Token>, start: Int, n: Int): Int {
        if (start + n > tokens.size) return start + n
        val phrase = tokens.subList(start, start + n)
        if (!phrase.all { it.isWordLike }) return start + n
        if (!collapsible(phrase)) return start + n
        var end = start + n
        while (true) {
            var j = end
            if (n == 1 && j < tokens.size && tokens[j].text == ",") j++ // "the, the"
            if (j + n > tokens.size) break
            val next = tokens.subList(j, j + n)
            if (next.all { it.isWordLike } && next.map { it.lower } == phrase.map { it.lower }) end = j + n else break
        }
        return end
    }

    private fun collapsible(phrase: List<Token>): Boolean {
        if (phrase.size == 1) {
            val t = phrase[0]
            // Numbers ("two two" may be 22), negations ("not not") and emphatic words are left alone.
            return t.lower !in Lexicon.INTENTIONAL_REPEATS && !Lexicon.isNegation(t.lower) &&
                !Lexicon.isNumberLike(t) && t.lower !in Lexicon.FILLERS
        }
        // Phrases made only of numbers ("one two one two") or emphatic words ("no no") are intentional.
        return !phrase.all { Lexicon.isNumberLike(it) } &&
            !phrase.all { it.lower in Lexicon.INTENTIONAL_REPEATS || Lexicon.isNegation(it.lower) }
    }
}

// ---------------------------------------------------------------------------------------------
// Final punctuation tidy (all modes)
// ---------------------------------------------------------------------------------------------

internal object TidyPunctuationStep : CleanupStep {
    override val name = "punctuation tidy"
    override fun apply(tokens: List<Token>, options: CleanupOptions): StepOutput {
        val out = tidy(tokens)
        return if (out == tokens) StepOutput(tokens) else StepOutput(out, listOf(AppliedEdit(name, "removed stray punctuation")))
    }
}
