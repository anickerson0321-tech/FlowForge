package com.flowforge.core.cleanup

import com.flowforge.core.text.Lexicon
import com.flowforge.core.text.Token
import com.flowforge.core.text.TokenKind
import com.flowforge.core.text.WordClass

// ---------------------------------------------------------------------------------------------
// Professional wording
// ---------------------------------------------------------------------------------------------

/**
 * Formalizes casual contractions ("gonna" → "going to"), drops sentence-initial discourse markers
 * ("So we should" → "We should"), removes ", you know," asides, and fixes the casing of common
 * technical terms. Meaning-bearing words are never removed.
 */
internal object ProfessionalWordingStep : CleanupStep {
    override val name = "professional wording"

    private val INFORMAL = mapOf(
        "gonna" to "going to", "wanna" to "want to", "gotta" to "have to", "kinda" to "kind of",
        "sorta" to "sort of", "lemme" to "let me", "gimme" to "give me", "dunno" to "don't know",
        "cuz" to "because", "'cause" to "because", "outta" to "out of", "lotta" to "lot of",
        "y'know" to "you know", "tryna" to "trying to", "hafta" to "have to", "oughta" to "ought to",
        "shoulda" to "should have", "coulda" to "could have", "woulda" to "would have",
    )
    private val HAVE_FORMS = setOf("i've", "we've", "you've", "they've", "have", "has", "he's", "she's", "it's")

    private val DISCOURSE_STARTERS = listOf(
        listOf("okay", "so"), listOf("ok", "so"), listOf("alright", "so"), listOf("so"), listOf("well"),
        listOf("basically"), listOf("anyway", "so"),
    )

    val TECH_TERMS = mapOf(
        "javascript" to "JavaScript", "typescript" to "TypeScript", "github" to "GitHub", "gitlab" to "GitLab",
        "iphone" to "iPhone", "ipad" to "iPad", "macos" to "macOS", "ios" to "iOS", "youtube" to "YouTube",
        "linkedin" to "LinkedIn", "powerpoint" to "PowerPoint", "pdf" to "PDF", "pdfs" to "PDFs", "url" to "URL",
        "urls" to "URLs", "api" to "API", "apis" to "APIs", "sql" to "SQL", "html" to "HTML", "css" to "CSS",
        "json" to "JSON", "usb" to "USB", "wifi" to "Wi-Fi", "wi-fi" to "Wi-Fi", "bluetooth" to "Bluetooth",
        "gmail" to "Gmail", "whatsapp" to "WhatsApp", "chatgpt" to "ChatGPT", "faq" to "FAQ", "ceo" to "CEO",
        "cto" to "CTO", "cfo" to "CFO", "eta" to "ETA", "asap" to "ASAP", "fyi" to "FYI", "kpi" to "KPI",
        "kpis" to "KPIs", "roi" to "ROI", "sms" to "SMS", "gps" to "GPS", "tv" to "TV", "pc" to "PC",
        "android" to "Android", "kotlin" to "Kotlin", "python" to "Python", "excel" to "Excel",
    )

    override fun apply(tokens: List<Token>, options: CleanupOptions): StepOutput {
        val edits = ArrayList<AppliedEdit>()
        val out = ArrayList<Token>(tokens.size + 8)
        var i = 0
        while (i < tokens.size) {
            val t = tokens[i]
            if (!t.isWordLike || t.locked) {
                out += t
                i++
                continue
            }
            // Sentence-initial discourse markers followed by a subject: "So we should" -> "We should".
            if (tokens.isSentenceInitial(i)) {
                val starter = DISCOURSE_STARTERS.firstOrNull { words -> words.indices.all { tokens.wordAt(i + it)?.lower == words[it] } }
                if (starter != null) {
                    var next = i + starter.size
                    while (next < tokens.size && tokens[next].text == ",") next++
                    val nextWord = tokens.wordAt(next)
                    if (nextWord != null && nextWord.lower in Lexicon.SUBJECT_PRONOUNS) {
                        edits += AppliedEdit(name, "dropped \"${tokens.subList(i, i + starter.size).joinToString(" ") { it.text }}\"")
                        i = next
                        continue
                    }
                }
                // "I mean, ..." at the start of a sentence is an aside, not a correction.
                if (t.lower == "i" && tokens.wordAt(i + 1)?.lower == "mean" && tokens.getOrNull(i + 2)?.text == ",") {
                    edits += AppliedEdit(name, "dropped \"I mean,\"")
                    i += 3
                    continue
                }
            }
            // ", you know," asides.
            if (t.lower == "you" && tokens.wordAt(i + 1)?.lower == "know" && tokens.getOrNull(i + 2)?.text == "," &&
                (tokens.isSentenceInitial(i) || out.lastOrNull()?.text == ",")
            ) {
                if (out.lastOrNull()?.text == ",") out.removeAt(out.lastIndex)
                edits += AppliedEdit(name, "dropped \"you know\"")
                i += 3
                continue
            }
            val informal = INFORMAL[t.lower]
            if (informal != null) {
                val prev = out.lastOrNull { it.isWordLike }?.lower
                val replacement = if (t.lower == "gotta" && prev in HAVE_FORMS) "got to" else informal
                val words = replacement.split(' ')
                words.forEachIndexed { k, w ->
                    val text = if (k == 0 && t.text[0].isUpperCase()) capitalizeFirst(w) else w
                    out += Token.word(text)
                }
                edits += AppliedEdit(name, "\"${t.text}\" → \"$replacement\"")
                i++
                continue
            }
            val tech = TECH_TERMS[t.lower]
            if (tech != null && t.text == t.lower && tech != t.text) {
                out += t.copy(text = tech)
                edits += AppliedEdit(name, "\"${t.text}\" → \"$tech\"")
                i++
                continue
            }
            out += t
            i++
        }
        if (edits.isEmpty()) return StepOutput(tokens)
        return StepOutput(tidy(out), edits)
    }
}

// ---------------------------------------------------------------------------------------------
// Spoken lists ("first ... second ... third ...")
// ---------------------------------------------------------------------------------------------

internal object ListFormattingStep : CleanupStep {
    override val name = "list formatting"

    private val ORDINALS = mapOf(
        "first" to 1, "firstly" to 1, "second" to 2, "secondly" to 2, "third" to 3, "thirdly" to 3,
        "fourth" to 4, "fourthly" to 4, "fifth" to 5, "sixth" to 6, "seventh" to 7, "eighth" to 8,
        "ninth" to 9, "tenth" to 10,
    )
    private val CARDINALS = mapOf(
        "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7,
        "eight" to 8, "nine" to 9, "ten" to 10, "1" to 1, "2" to 2, "3" to 3, "4" to 4, "5" to 5, "6" to 6,
        "7" to 7, "8" to 8, "9" to 9, "10" to 10,
    )
    private val LAST = setOf("finally", "lastly")
    private val NOT_A_MARKER_AFTER = setOf(
        "the", "a", "an", "my", "your", "our", "his", "her", "their", "its", "this", "that", "at", "for", "one",
        "each", "every", "in", "of", "very", "come", "came", "his", "whose", "which",
    )
    private val TRAILING_JOINERS = setOf("and", "then", "also", "next")

    private class Marker(val index: Int, val length: Int, val ordinal: Int)

    override fun apply(tokens: List<Token>, options: CleanupOptions): StepOutput {
        val markers = findMarkers(tokens) ?: return StepOutput(tokens)
        val first = markers.first()
        // The list ends at the end of the paragraph; the last item ends at its first sentence end.
        val paraEnd = (first.index until tokens.size).firstOrNull { tokens[it].kind == TokenKind.NEWLINE && tokens[it].text.length >= 2 }
            ?: tokens.size
        if (markers.any { it.index >= paraEnd }) return StepOutput(tokens)
        val paraStart = (first.index - 1 downTo 0).firstOrNull { tokens[it].kind == TokenKind.NEWLINE && tokens[it].text.length >= 2 }
            ?.plus(1) ?: 0

        val out = ArrayList<Token>(tokens.size + markers.size * 2)
        out.addAll(tokens.subList(0, paraStart))
        val intro = trimItem(tokens.subList(paraStart, first.index), keepCase = true)
        if (intro.isNotEmpty()) {
            out.addAll(intro)
            out += Token(":", TokenKind.PUNCT, synthetic = true)
            out += Token.newline(1)
        }
        val declared = ArrayList<Token>()
        var remainder: List<Token> = emptyList()
        markers.forEachIndexed { n, m ->
            declared.addAll(tokens.subList(m.index, m.index + m.length))
            val itemStart = m.index + m.length
            var itemEnd = if (n + 1 < markers.size) markers[n + 1].index else paraEnd
            if (n == markers.lastIndex) {
                val sentenceEnd = (itemStart until paraEnd).firstOrNull { tokens[it].isSentenceEnd }
                if (sentenceEnd != null && sentenceEnd + 1 < paraEnd) {
                    remainder = tokens.subList(sentenceEnd + 1, paraEnd)
                    itemEnd = sentenceEnd
                }
            }
            val raw = tokens.subList(itemStart, itemEnd)
            val item = trimItem(raw, keepCase = false)
            declared.addAll(raw.filter { it.isWordLike && it.lower in TRAILING_JOINERS && it !in item })
            if (n > 0) out += Token.newline(1)
            out += Token("${n + 1}.", TokenKind.NUMBER, synthetic = true)
            out.addAll(item)
        }
        if (remainder.any { it.isWordLike }) {
            out += Token.newline(2)
            out.addAll(trimItem(remainder, keepCase = true))
        }
        out.addAll(tokens.subList(paraEnd, tokens.size))
        return StepOutput(out, listOf(AppliedEdit(name, "formatted ${markers.size} list items")), declared)
    }

    private fun findMarkers(t: List<Token>): List<Marker>? {
        val found = ArrayList<Marker>()
        var i = 0
        while (i < t.size) {
            val m = markerAt(t, i, expected = found.size + 1, allowLast = found.size >= 2)
            if (m != null) {
                found += m
                i += m.length
            } else {
                i++
            }
        }
        return found.takeIf { it.size >= 2 && it[0].ordinal == 1 && it[1].ordinal == 2 }
    }

    private fun markerAt(t: List<Token>, i: Int, expected: Int, allowLast: Boolean): Marker? {
        val w = t.wordAt(i) ?: return null
        if (w.locked) return null
        val prevWord = t.getOrNull(i - 1)?.takeIf { it.isWordLike }?.lower
        if (prevWord in NOT_A_MARKER_AFTER) return null
        var length = 1
        val ordinal = when {
            ORDINALS[w.lower] == expected -> expected
            w.lower == "number" && CARDINALS[t.wordAt(i + 1)?.lower] == expected -> {
                length = 2
                expected
            }
            allowLast && w.lower in LAST -> expected
            else -> return null
        }
        var next = i + length
        while (next < t.size && t[next].text == ",") {
            next++
            length++
        }
        // A marker must introduce words ("first, review ..."), not end the sentence ("came first.").
        if (t.wordAt(next) == null) return null
        return Marker(i, length, ordinal)
    }

    private fun trimItem(raw: List<Token>, keepCase: Boolean): List<Token> {
        var start = 0
        var end = raw.size
        while (start < end && (raw[start].kind == TokenKind.PUNCT && raw[start].text in SOFT_PUNCT)) start++
        while (end > start) {
            val last = raw[end - 1]
            val drop = (last.kind == TokenKind.PUNCT && (last.text in SOFT_PUNCT || last.isSentenceEnd)) ||
                last.kind == TokenKind.NEWLINE || (last.isWordLike && !last.locked && last.lower in TRAILING_JOINERS)
            if (drop) end-- else break
        }
        val item = raw.subList(start, end).toMutableList()
        if (!keepCase && item.isNotEmpty() && item[0].isWordLike) item[0] = item[0].capitalized()
        return item
    }
}

// ---------------------------------------------------------------------------------------------
// Commas
// ---------------------------------------------------------------------------------------------

internal object CommaStep : CleanupStep {
    override val name = "commas"

    private val GREETING_TARGETS = setOf("there", "everyone", "everybody", "all", "team", "guys", "folks", "y'all", "both")
    private val CLAUSE_CONJ = mapOf("and" to 3, "so" to 3, "but" to 2, "yet" to 2)
    private val COMPOUND_SUBJECT = setOf("i", "you", "he", "she", "we", "they", "me", "him", "her", "us", "them", "it")
    private val SENTENCE_ADVERBS = setOf(
        "however", "unfortunately", "fortunately", "honestly", "meanwhile", "additionally", "therefore",
        "anyway", "besides", "otherwise", "instead",
    )
    private val ANSWER_WORDS = setOf("yes", "yeah", "no", "okay", "ok", "well", "sorry", "finally", "also")
    private val ANSWER_FOLLOWERS = Lexicon.SUBJECT_PRONOUNS + setOf(
        "that's", "this", "that", "let's", "don't", "do", "please", "thanks", "thank", "sounds", "not", "it's",
    )

    override fun apply(tokens: List<Token>, options: CleanupOptions): StepOutput {
        val commaAfter = HashSet<Int>()
        val edits = ArrayList<AppliedEdit>()
        for (i in tokens.indices) {
            val t = tokens[i]
            if (!t.isWordLike || t.synthetic) continue
            val next = tokens.getOrNull(i + 1)
            val nextIsWord = next != null && next.isWordLike

            if (tokens.isSentenceInitial(i)) {
                // "Hey Jason I checked" -> "Hey Jason, I checked"
                val greetingEnd = greetingEnd(tokens, i)
                if (greetingEnd != null) {
                    val target = tokens.wordAt(greetingEnd + 1)
                    val isTarget = target != null && (target.locked || target.lower in GREETING_TARGETS ||
                        Lexicon.classify(target, false) == WordClass.PROPER_NAME)
                    if (isTarget && tokens.wordAt(greetingEnd + 2) != null) {
                        commaAfter += greetingEnd + 1
                        edits += AppliedEdit(name, "comma after greeting")
                    }
                }
                if (nextIsWord && t.lower in SENTENCE_ADVERBS) {
                    commaAfter += i
                    edits += AppliedEdit(name, "comma after \"${t.text}\"")
                } else if (nextIsWord && t.lower in ANSWER_WORDS && next!!.lower in ANSWER_FOLLOWERS &&
                    tokens.wordAt(i + 2) != null
                ) {
                    commaAfter += i
                    edits += AppliedEdit(name, "comma after \"${t.text}\"")
                }
            }

            // "... the bathrooms and we should ..." -> "..., and we should ..."
            val minWords = CLAUSE_CONJ[t.lower]
            if (minWords != null && i > 0 && tokens[i - 1].isWordLike && nextIsWord &&
                next!!.lower in Lexicon.SUBJECT_PRONOUNS && tokens[i - 1].lower !in COMPOUND_SUBJECT &&
                wordsSinceBreak(tokens, i) >= minWords
            ) {
                commaAfter += i - 1
                edits += AppliedEdit(name, "comma before \"${t.text}\"")
            }

            // "Friday not Thursday" -> "Friday, not Thursday"
            if (t.lower == "not" && i > 0 && tokens[i - 1].isWordLike && nextIsWord) {
                val a = Lexicon.classify(tokens[i - 1], tokens.isSentenceInitial(i - 1))
                val b = Lexicon.classify(next!!, false)
                if (a != WordClass.OTHER && Lexicon.compatible(a, b)) {
                    commaAfter += i - 1
                    edits += AppliedEdit(name, "comma before contrast \"not\"")
                }
            }
        }
        if (commaAfter.isEmpty()) return StepOutput(tokens)
        val out = ArrayList<Token>(tokens.size + commaAfter.size)
        tokens.forEachIndexed { i, t ->
            out += t
            val next = tokens.getOrNull(i + 1)
            if (i in commaAfter && (next == null || next.kind != TokenKind.PUNCT)) out += Token.punct(",")
        }
        return StepOutput(out, edits.distinct())
    }

    /** Index of the last word of a greeting at [i] ("hey", "good morning"), or null. */
    private fun greetingEnd(t: List<Token>, i: Int): Int? {
        val w = t[i].lower
        if (w == "good" && t.wordAt(i + 1)?.lower in setOf("morning", "afternoon", "evening")) return i + 1
        return if (w in Lexicon.GREETINGS && w != "thanks" && w !in setOf("morning", "evening", "afternoon")) i else null
    }

    private fun wordsSinceBreak(t: List<Token>, i: Int): Int {
        var n = 0
        var j = i - 1
        while (j >= 0 && !t[j].isBoundary && t[j].text != "," && t[j].text != ";" && t[j].text != ":") {
            if (t[j].isWordLike) n++
            j--
        }
        return n
    }
}

// ---------------------------------------------------------------------------------------------
// Capitalization
// ---------------------------------------------------------------------------------------------

internal object CapitalizationStep : CleanupStep {
    override val name = "capitalization"
    private val I_FORMS = mapOf("i" to "I", "i'm" to "I'm", "i've" to "I've", "i'll" to "I'll", "i'd" to "I'd")

    override fun apply(tokens: List<Token>, options: CleanupOptions): StepOutput {
        val out = ArrayList<Token>(tokens.size)
        var firstWordSeen = false
        var boundarySinceStart = false
        for ((i, t) in tokens.withIndex()) {
            if (t.isBoundary) boundarySinceStart = true
            if (!t.isWordLike || t.synthetic) {
                out += t
                continue
            }
            var tok = t
            I_FORMS[t.lower]?.let { if (!t.locked) tok = t.copy(text = it) }
            if (tokens.isSentenceInitial(i)) {
                val utteranceStart = !firstWordSeen && !boundarySinceStart
                tok = when {
                    !utteranceStart || options.context.startsSentence -> tok.capitalized()
                    // Inserted mid-sentence: "…and" rather than "…And".
                    !tok.locked && tok.lower in Lexicon.LOWERCASABLE_STARTERS && tok.text.length > 1 &&
                        tok.text[0].isUpperCase() && tok.text.substring(1) == tok.text.substring(1).lowercase() ->
                        tok.copy(text = tok.text[0].lowercaseChar() + tok.text.substring(1))
                    else -> tok
                }
            }
            firstWordSeen = true
            out += tok
        }
        return if (out == tokens) StepOutput(tokens) else StepOutput(out, listOf(AppliedEdit(name, "sentence capitals")))
    }
}

// ---------------------------------------------------------------------------------------------
// Final punctuation
// ---------------------------------------------------------------------------------------------

internal object EndPunctuationStep : CleanupStep {
    override val name = "end punctuation"
    private val AUX = setOf(
        "is", "are", "am", "was", "were", "do", "does", "did", "can", "could", "would", "will", "should",
        "shall", "may", "might", "have", "has", "had", "won't", "wouldn't", "can't", "couldn't", "don't",
        "doesn't", "didn't", "isn't", "aren't", "wasn't", "weren't", "shouldn't", "haven't", "hasn't",
    )
    private val WH = setOf("who", "what", "when", "where", "why", "how", "which", "whose", "whom")
    private val WH_CONTRACTIONS = setOf("what's", "where's", "who's", "how's", "when's", "why's", "how'd", "what'd", "where'd")
    private val SUBJECTS = Lexicon.SUBJECT_PRONOUNS + setOf(
        "the", "this", "that", "these", "those", "anyone", "anybody", "someone", "somebody", "everyone",
        "everybody", "my", "your", "our", "their", "his", "her", "its", "we", "a", "an",
    )
    private val CLOSED = setOf(".", "?", "!", "…", "...", ":", ";", ",")

    override fun apply(tokens: List<Token>, options: CleanupOptions): StepOutput {
        val out = ArrayList<Token>(tokens.size + 4)
        var segmentStart = 0
        var added = 0
        fun closeSegment(endExclusive: Int, followedByNewline: Boolean, isLast: Boolean) {
            val seg = tokens.subList(segmentStart, endExclusive)
            out.addAll(seg)
            val words = seg.filter { it.isWordLike && !it.synthetic }
            if (words.isEmpty()) return
            if (seg.firstOrNull()?.synthetic == true) return // list item
            val last = seg.last()
            if (last.kind == TokenKind.PUNCT && last.text in CLOSED) return
            if (isLast && options.context.continuesSentence) return
            val mark = when {
                // "Hi Jason" on its own line reads as a salutation: "Hi Jason,"
                followedByNewline && words.size <= 3 && (words[0].lower in Lexicon.GREETINGS || words[0].lower == "dear") -> ","
                isQuestion(words) -> "?"
                else -> "."
            }
            out += Token.punct(mark)
            added++
        }
        for ((i, t) in tokens.withIndex()) {
            if (t.isSentenceEnd) {
                // Already terminated by the speaker or recognizer.
                out.addAll(tokens.subList(segmentStart, i + 1))
                segmentStart = i + 1
            } else if (t.kind == TokenKind.NEWLINE && t.text.length == 1) {
                // A spoken "new line" (addresses, short lines) does not imply a sentence end.
                out.addAll(tokens.subList(segmentStart, i + 1))
                segmentStart = i + 1
            } else if (t.kind == TokenKind.NEWLINE) {
                closeSegment(i, followedByNewline = true, isLast = false)
                out += t
                segmentStart = i + 1
            }
        }
        if (segmentStart < tokens.size) closeSegment(tokens.size, followedByNewline = false, isLast = true)
        if (added == 0) return StepOutput(tokens)
        return StepOutput(out, listOf(AppliedEdit(name, "added $added end mark(s)")))
    }

    fun isQuestion(words: List<Token>): Boolean {
        val w0 = words[0].lower
        val w1 = words.getOrNull(1)?.lower
        val w2 = words.getOrNull(2)?.lower
        if (w0 in WH_CONTRACTIONS) return true
        if (w0 in WH) {
            if (w1 in AUX) return true
            // "What time is it", not "How we do it matters".
            return w1 != null && w1 !in Lexicon.SUBJECT_PRONOUNS && w2 in AUX
        }
        // "Do you…" asks; "Do it now" / "Do the dishes" instructs.
        if (w0 == "do") return w1 in DO_SUBJECTS
        return w0 in AUX && w1 in SUBJECTS
    }

    private val DO_SUBJECTS = setOf("i", "you", "we", "they", "these", "those", "any", "anyone", "anybody", "people", "y'all")
}
