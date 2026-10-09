package com.flowforge.core.cleanup

import com.flowforge.core.text.Lexicon
import com.flowforge.core.text.Token
import com.flowforge.core.text.TokenKind
import com.flowforge.core.text.WordClass

/**
 * Handles spoken self-corrections:
 *
 *  - "scratch that" / "strike that" / "delete that": removes the sentence so far (or the previous
 *    sentence when said at the start of one). With nothing before it in this dictation it becomes
 *    [CleanupCommand.UNDO_PREVIOUS_INSERTION].
 *  - "make that X" / "change that to X": replaces the most recent item of the same kind as X
 *    (weekday, month, number/time, relative day, name), looking back up to two sentences.
 *  - inline corrections "A, I mean B", "A, no, B", "A or rather B", "A, sorry, B", "A actually B":
 *    replace A with B when both are the same kind of item and A is close to the marker.
 *
 * Anything that does not match these shapes is left exactly as spoken. "No, don't send that." is
 * never treated as a correction because nothing of the same kind precedes "no".
 */
internal object SelfCorrectionStep : CleanupStep {
    override val name = "self-correction"

    private const val MAX_CORRECTIONS = 8
    private val SCRATCH_VERBS = setOf("scratch", "strike", "delete", "erase")
    private val LEAD_IN = setOf("oh", "no", "actually", "wait", "sorry", "okay", "ok", "hmm", "um", "uh", "so")
    private val RESTART_WORDS = setOf("let's", "the", "my", "our", "please", "actually", "make", "and", "but", "so")
    private val UNITS = setOf(
        "am", "pm", "a.m.", "p.m.", "o'clock", "percent", "%", "dollars", "bucks", "euros", "pounds", "minutes",
        "minute", "hours", "hour", "days", "day", "weeks", "week", "months", "month", "years", "year", "people",
        "items", "times", "k", "grand",
    )

    private class Inline(val words: List<String>, val maxBack: Int)

    private val INLINE = listOf(
        Inline(listOf("i", "mean"), 6),
        Inline(listOf("or", "rather"), 3),
        Inline(listOf("rather"), 2),
        Inline(listOf("sorry"), 4),
        Inline(listOf("correction"), 4),
        Inline(listOf("wait"), 3),
        Inline(listOf("oops"), 3),
        Inline(listOf("actually"), 3),
        Inline(listOf("no"), 1),
    )

    private val MAKE_THAT = listOf(listOf("make", "that"), listOf("change", "that", "to"))

    override fun apply(tokens: List<Token>, options: CleanupOptions): StepOutput {
        var current = tokens
        val edits = ArrayList<AppliedEdit>()
        val declared = ArrayList<Token>()
        var command = CleanupCommand.NONE
        for (pass in 0 until MAX_CORRECTIONS) {
            val r = applyOnce(current) ?: break
            current = r.tokens
            edits += r.edits
            declared += r.declaredRemovals
            if (r.command != CleanupCommand.NONE) command = r.command
        }
        if (edits.isEmpty()) return StepOutput(tokens)
        return StepOutput(current, edits, declared, command)
    }

    private fun applyOnce(t: List<Token>): StepOutput? {
        for (i in t.indices) {
            if (!t[i].isWordLike || t[i].locked) continue
            tryScratch(t, i)?.let { return it }
            tryMakeThat(t, i)?.let { return it }
            tryInline(t, i)?.let { return it }
        }
        return null
    }

    // -- "scratch that" --------------------------------------------------------------------------

    private fun tryScratch(t: List<Token>, i: Int): StepOutput? {
        if (t[i].lower !in SCRATCH_VERBS || t.wordAt(i + 1)?.lower != "that") return null
        var after = i + 2
        while (after < t.size && t[after].kind == TokenKind.PUNCT && t[after].text in setOf(",", ".", "!", ";", ":")) after++
        val next = t.getOrNull(after)
        val freshStart = next == null || next.kind == TokenKind.NEWLINE ||
            (next.isWordLike && (next.lower in Lexicon.SUBJECT_PRONOUNS || next.lower in RESTART_WORDS || next.text[0].isUpperCase()))
        if (!freshStart) return null // "scratch that itch"

        val markerStart = extendLeadIn(t, i)
        val sStart = t.sentenceStart(markerStart)
        val wordsBefore = (sStart until markerStart).any { t[it].isWordLike }
        var command = CleanupCommand.NONE
        val deleteFrom = when {
            wordsBefore -> sStart
            sStart == 0 -> {
                command = CleanupCommand.UNDO_PREVIOUS_INSERTION
                markerStart
            }
            else -> t.sentenceStart(sStart - 1)
        }
        val removed = t.subList(deleteFrom, after)
        val result = tidy(t.subList(0, deleteFrom) + t.subList(after, t.size))
        val detail = if (command == CleanupCommand.UNDO_PREVIOUS_INSERTION) {
            "\"${t[i].text} that\" → undo previous dictation"
        } else {
            "\"${t[i].text} that\" removed \"${removed.filter { it.isWordLike }.joinToString(" ") { it.text }}\""
        }
        return StepOutput(result, listOf(AppliedEdit(name, detail)), removed.filter { it.isWordLike }, command)
    }

    // -- "make that X" -----------------------------------------------------------------------------

    private fun tryMakeThat(t: List<Token>, i: Int): StepOutput? {
        val marker = MAKE_THAT.firstOrNull { words -> words.indices.all { t.wordAt(i + it)?.lower == words[it] } } ?: return null
        var xStart = i + marker.size
        while (xStart < t.size && t[xStart].text == ",") xStart++
        val x = itemAt(t, xStart, sentenceInitial = false) ?: return null
        val markerStart = extendLeadIn(t, i)
        val antecedent = findAntecedent(t, markerStart - 1, x.cls, maxWords = 40, sentencesBack = 2) ?: return null

        // Remove "actually, make that X" (plus whatever lead-in is left of that sentence).
        var removeEnd = x.range.last + 1
        while (removeEnd < t.size && t[removeEnd].kind == TokenKind.PUNCT && t[removeEnd].text in setOf(",", ".", "!")) removeEnd++
        var removeStart = markerStart
        val sStart = t.sentenceStart(markerStart)
        if ((sStart until markerStart).all { !t[it].isWordLike || t[it].lower in LEAD_IN || t[it].lower == "let's" }) {
            removeStart = sStart
        }
        return replaceAndRemove(t, antecedent, x.range, removeStart until removeEnd, "\"${marker.joinToString(" ")}\"")
    }

    // -- "A, I mean B" ---------------------------------------------------------------------------

    private fun tryInline(t: List<Token>, i: Int): StepOutput? {
        val marker = INLINE.firstOrNull { m -> m.words.indices.all { t.wordAt(i + it)?.lower == m.words[it] } } ?: return null
        // The marker needs something before it in the same sentence.
        if (t.isSentenceInitial(i)) return null
        var xStart = i + marker.words.size
        while (xStart < t.size && t[xStart].text == ",") xStart++
        val x = itemAt(t, xStart, sentenceInitial = false) ?: return null
        var before = i - 1
        while (before >= 0 && t[before].text == ",") before--
        if (before < 0 || t[before].isBoundary) return null
        val antecedent = findAntecedent(t, before, x.cls, maxWords = marker.maxBack, sentencesBack = 0) ?: return null
        // For tight markers ("no"), the antecedent must end right before the marker.
        if (marker.maxBack == 1 && antecedent.last != before) return null

        var removeStart = i
        while (removeStart - 1 > antecedent.last && t[removeStart - 1].text == ",") removeStart--
        var removeEnd = x.range.last + 1
        if (removeEnd < t.size && t[removeEnd].text == "," && removeStart <= antecedent.last + 1) removeEnd++
        return replaceAndRemove(t, antecedent, x.range, removeStart until removeEnd, "\"${marker.words.joinToString(" ")}\"")
    }

    // -- helpers -----------------------------------------------------------------------------------

    private class Item(val range: IntRange, val cls: WordClass)

    private fun extendLeadIn(t: List<Token>, i: Int): Int {
        var start = i
        while (start - 1 >= 0) {
            val p = t[start - 1]
            if (p.text == "," || (p.isWordLike && !p.locked && p.lower in LEAD_IN)) start-- else break
        }
        // Do not swallow a comma that belongs to the previous clause unless a lead-in word follows it.
        while (start < i && t[start].text == ",") start++
        return start
    }

    /** The correctable item starting at [start] ("Friday", "3 pm", "twenty five", "Jason Lee"). */
    private fun itemAt(t: List<Token>, start: Int, sentenceInitial: Boolean): Item? {
        val head = t.wordAt(start) ?: return null
        val cls = Lexicon.classify(head, sentenceInitial)
        if (cls == WordClass.OTHER) return null
        var end = start
        when (cls) {
            WordClass.NUMBER, WordClass.TIME -> {
                while (t.wordAt(end + 1)?.let { Lexicon.isNumberLike(it) } == true) end++
                if (t.wordAt(end + 1)?.lower in UNITS) end++
            }
            WordClass.PROPER_NAME -> {
                while (t.wordAt(end + 1)?.let { Lexicon.classify(it, false) == WordClass.PROPER_NAME } == true) end++
            }
            else -> {}
        }
        return Item(start..end, cls)
    }

    /**
     * Searches backwards from [from] for the nearest item compatible with [cls], crossing at most
     * [sentencesBack] sentence boundaries and [maxWords] words.
     */
    private fun findAntecedent(t: List<Token>, from: Int, cls: WordClass, maxWords: Int, sentencesBack: Int): IntRange? {
        var j = from
        var words = 0
        var boundaries = 0
        while (j >= 0) {
            val tok = t[j]
            if (tok.isBoundary) {
                boundaries++
                if (boundaries > sentencesBack) return null
                j--
                continue
            }
            if (tok.isWordLike) {
                words++
                if (words > maxWords) return null
                // Step from a unit ("pm") to its number.
                val headIdx = if (tok.lower in UNITS && j > 0 && t.wordAt(j - 1)?.let { Lexicon.isNumberLike(it) } == true) j - 1 else j
                val head = t[headIdx]
                val headCls = Lexicon.classify(head, t.isSentenceInitial(headIdx))
                if (headCls != WordClass.OTHER && Lexicon.compatible(headCls, cls)) {
                    var start = headIdx
                    if (headCls == WordClass.NUMBER || headCls == WordClass.TIME) {
                        while (t.wordAt(start - 1)?.let { Lexicon.isNumberLike(it) } == true) start--
                    } else if (headCls == WordClass.PROPER_NAME) {
                        while (start - 1 >= 0 && t.wordAt(start - 1)?.let { !t.isSentenceInitial(start - 1) && Lexicon.classify(it, false) == WordClass.PROPER_NAME } == true) start--
                    }
                    return start..j
                }
            }
            j--
        }
        return null
    }

    private fun replaceAndRemove(
        t: List<Token>,
        antecedent: IntRange,
        replacement: IntRange,
        remove: IntRange,
        markerLabel: String,
    ): StepOutput {
        require(antecedent.last < remove.first) { "antecedent must precede the correction" }
        val replacementTokens = t.subList(replacement.first, replacement.last + 1)
        val out = ArrayList<Token>(t.size)
        out.addAll(t.subList(0, antecedent.first))
        out.addAll(replacementTokens)
        out.addAll(t.subList(antecedent.last + 1, remove.first))
        out.addAll(t.subList(remove.last + 1, t.size))
        val old = t.subList(antecedent.first, antecedent.last + 1)
        // Removed: the old item plus the marker words (the replacement survives in its new place).
        val removedWords = old + t.subList(remove.first, remove.last + 1).filterIndexed { k, tok ->
            tok.isWordLike && (remove.first + k) !in replacement
        }
        val detail = "$markerLabel: \"${old.joinToString(" ") { it.text }}\" → \"${replacementTokens.joinToString(" ") { it.text }}\""
        return StepOutput(tidy(out), listOf(AppliedEdit(name, detail)), removedWords)
    }
}

/**
 * "and actually we should..." → "and we should..." (Light+); a sentence-initial "Actually, we..."
 * is also dropped in Professional mode. "I actually did it" is never touched.
 */
internal object DiscourseActuallyStep : CleanupStep {
    override val name = "filler \"actually\""
    private val CONJUNCTIONS = setOf("and", "but", "so", "or")
    private val CLAUSE_STARTERS = Lexicon.SUBJECT_PRONOUNS + setOf("the", "this", "that")

    override fun apply(tokens: List<Token>, options: CleanupOptions): StepOutput {
        val out = ArrayList<Token>(tokens.size)
        var changed = false
        var i = 0
        while (i < tokens.size) {
            val t = tokens[i]
            if (t.isWordLike && !t.locked && t.lower == "actually") {
                var next = i + 1
                while (next < tokens.size && tokens[next].text == ",") next++
                val nextWord = tokens.wordAt(next)
                val prevIdx = out.indexOfLast { it.text != "," }
                val prev = out.getOrNull(prevIdx)
                val afterConjunction = prev != null && prev.isWordLike && prev.lower in CONJUNCTIONS
                val sentenceInitial = options.mode == CleanupMode.PROFESSIONAL && tokens.isSentenceInitial(i)
                if (nextWord != null && nextWord.lower in CLAUSE_STARTERS && (afterConjunction || sentenceInitial)) {
                    // Drop commas between the conjunction and "actually" as well.
                    while (out.isNotEmpty() && out.last().text == "," && afterConjunction) out.removeAt(out.lastIndex)
                    i = next
                    changed = true
                    continue
                }
            }
            out += t
            i++
        }
        if (!changed) return StepOutput(tokens)
        return StepOutput(tidy(out), listOf(AppliedEdit(name, "removed discourse \"actually\"")))
    }
}
