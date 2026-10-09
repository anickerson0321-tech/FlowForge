package com.flowforge.core.text

/** Coarse token categories used by the cleanup pipeline. Whitespace is implicit. */
enum class TokenKind { WORD, NUMBER, PUNCT, NEWLINE }

/**
 * A unit of dictated text. Words keep their internal punctuation ("don't", "follow-up",
 * "3:30", "$1,200", "e.g."); sentence punctuation is split into its own [TokenKind.PUNCT] token.
 */
data class Token(
    val text: String,
    val kind: TokenKind,
    /** Set by the personal dictionary: later formatting rules must not alter or remove this token. */
    val locked: Boolean = false,
    /** Formatting the cleaner generated (e.g. "1." list numbers); not part of the spoken content. */
    val synthetic: Boolean = false,
) {
    val lower: String get() = text.lowercase()
    val isWordLike: Boolean get() = kind == TokenKind.WORD || kind == TokenKind.NUMBER
    val isSentenceEnd: Boolean get() = kind == TokenKind.PUNCT && text in SENTENCE_END
    val isBoundary: Boolean get() = isSentenceEnd || kind == TokenKind.NEWLINE

    companion object {
        val SENTENCE_END = setOf(".", "?", "!", "…", "...")
        fun word(text: String) = Token(text, if (text.any { it.isDigit() }) TokenKind.NUMBER else TokenKind.WORD)
        fun punct(text: String) = Token(text, TokenKind.PUNCT)
        fun newline(count: Int = 1) = Token("\n".repeat(count), TokenKind.NEWLINE)
    }
}

object Tokenizer {
    private val ABBREVIATIONS = setOf(
        "e.g.", "i.e.", "etc.", "vs.", "mr.", "mrs.", "ms.", "dr.", "st.", "jr.", "sr.", "u.s.", "u.k.",
        "a.m.", "p.m.", "no.", "approx.", "dept.", "inc.", "ltd.", "co.",
    )
    private const val LEADING_PUNCT = "(\"“[¿¡"
    private const val TRAILING_PUNCT = ",.;:?!)\"”]…"

    fun tokenize(input: String): List<Token> {
        val out = ArrayList<Token>()
        val normalized = input.replace("\r\n", "\n").replace('\r', '\n')
        var i = 0
        while (i < normalized.length) {
            val c = normalized[i]
            when {
                c == '\n' -> {
                    var n = 0
                    while (i < normalized.length && (normalized[i] == '\n' || normalized[i] == ' ' || normalized[i] == '\t')) {
                        if (normalized[i] == '\n') n++
                        i++
                    }
                    out += Token.newline(minOf(n, 2))
                }
                c.isWhitespace() -> i++
                else -> {
                    val start = i
                    while (i < normalized.length && !normalized[i].isWhitespace()) i++
                    splitChunk(normalized.substring(start, i), out)
                }
            }
        }
        return out
    }

    private fun splitChunk(chunk: String, out: MutableList<Token>) {
        if (chunk.lowercase() in ABBREVIATIONS) {
            out += Token(chunk, TokenKind.WORD)
            return
        }
        var start = 0
        var end = chunk.length
        val leading = ArrayList<Token>()
        while (start < end && chunk[start] in LEADING_PUNCT) {
            leading += Token.punct(chunk[start].toString())
            start++
        }
        val trailing = ArrayList<Token>()
        while (end > start) {
            val last = chunk[end - 1]
            if (last !in TRAILING_PUNCT) break
            // Keep the decimal/thousands separators inside numbers ("3.5", "1,000") — only trailing ones split.
            if (chunk.regionMatches(maxOf(start, end - 3), "...", 0, 3) && end - start > 3) {
                trailing.add(0, Token.punct("..."))
                end -= 3
                continue
            }
            // Words ending in an abbreviation-like period ("a.m.") stay intact.
            val core = chunk.substring(start, end)
            if (last == '.' && core.lowercase() in ABBREVIATIONS) break
            trailing.add(0, Token.punct(last.toString()))
            end--
        }
        out += leading
        if (end > start) out += Token.word(chunk.substring(start, end))
        out += trailing
    }
}

object Renderer {
    private val NO_SPACE_BEFORE = setOf(",", ".", ";", ":", "?", "!", ")", "]", "”", "…", "...", "%")
    private val NO_SPACE_AFTER = setOf("(", "[", "“", "¿", "¡", "$")

    fun render(tokens: List<Token>): String {
        val sb = StringBuilder()
        var quoteOpen = false
        var prev: Token? = null
        for (t in tokens) {
            if (t.kind == TokenKind.NEWLINE) {
                // Drop trailing spaces before a line break.
                while (sb.isNotEmpty() && sb.last() == ' ') sb.setLength(sb.length - 1)
                sb.append(t.text)
                prev = t
                continue
            }
            val isQuote = t.text == "\""
            val attachLeft = when {
                isQuote -> quoteOpen // closing quote attaches to previous word
                else -> t.text in NO_SPACE_BEFORE
            }
            // A straight quote followed by an open quote state was an opening quote: no space after it.
            val prevBlocksSpace = prev == null || prev.kind == TokenKind.NEWLINE ||
                prev.text in NO_SPACE_AFTER || (prev.text == "\"" && quoteOpen)
            if (sb.isNotEmpty() && !attachLeft && !prevBlocksSpace) sb.append(' ')
            sb.append(t.text)
            if (isQuote) quoteOpen = !quoteOpen
            prev = t
        }
        return sb.toString().trim(' ')
    }
}
