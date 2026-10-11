package com.privatevoice.app.speech.kokoro

import java.text.Normalizer

/**
 * Turns raw message text into speakable English words and punctuation.
 *
 * Handles what shows up in text messages: links, e-mail addresses, emoji,
 * currency, times, ordinals, years, phone numbers, percentages, texting
 * shorthand and elongated words ("sooooo"). Output contains only letters,
 * apostrophes, hyphens, spaces and the punctuation Kokoro understands.
 */
class TextNormalizer(
    var emojiMode: EmojiMode = EmojiMode.DESCRIBE,
    var expandShorthand: Boolean = true,
) {
    enum class EmojiMode { DESCRIBE, SKIP }

    fun normalize(input: String): String {
        var s = Normalizer.normalize(input, Normalizer.Form.NFC)
        s = s.replace('\u2019', '\'').replace('\u2018', '\'').replace('\u02BC', '\'')
            .replace('\u00A0', ' ').replace('\u2009', ' ').replace("\u200B", "")
        s = replaceUrls(s)
        s = EMAIL.replace(s) { m ->
            " " + m.groupValues[1].replace(".", " dot ").replace("_", " underscore ") + " at " +
                m.groupValues[2].replace(".", " dot ") + " "
        }
        s = replaceEmoji(s)
        s = HASHTAG.replace(s) { m -> " hashtag " + splitCamel(m.groupValues[1]) + " " }
        s = MENTION.replace(s) { m -> " at " + m.groupValues[1] + " " }
        s = CURRENCY.replace(s) { m -> " " + currency(m.groupValues[1], m.groupValues[2], m.groupValues[3]) + " " }
        s = TIME.replace(s) { m -> " " + time(m.groupValues[1], m.groupValues[2], m.groupValues[3]) + " " }
        s = AMPM.replace(s) { m -> " " + NumberWords.cardinal(m.groupValues[1].toLong()) + " " + ampm(m.groupValues[2]) + " " }
        s = PHONE.replace(s) { m -> " " + m.value.filter { it.isDigit() }.map { NumberWords.digits(it.toString()) }.joinToString(", ") { it } + " " }
        s = PERCENT.replace(s) { m -> " " + number(m.groupValues[1]) + " percent " }
        s = ORDINAL.replace(s) { m -> " " + NumberWords.ordinal(m.groupValues[1].replace(",", "").toLong()) + " " }
        s = RANGE.replace(s) { m -> " " + m.groupValues[1] + " to " + m.groupValues[2] + " " }
        s = DECADE.replace(s) { m ->
            val y = m.groupValues[1].toInt()
            " " + NumberWords.year(y).let { w -> if (w.endsWith("y")) w.dropLast(1) + "ies" else w + "s" } + " "
        }
        s = NUMBER.replace(s) { m -> " " + number(m.value) + " " }
        s = TITLES.replace(s) { m -> TITLE_MAP.getValue(m.groupValues[1].lowercase()) + " " }
        s = DOTTED.replace(s) { m -> m.value.replace(".", "").uppercase() + " " }
        if (expandShorthand) s = expandShorthand(s)
        s = s.replace("&", " and ").replace("+", " plus ").replace("=", " equals ").replace("@", " at ")
            .replace("%", " percent ").replace("/", " ").replace("\\", " ").replace("_", " ").replace("*", " ")
            .replace("~", " ").replace("^", " ").replace("|", " ").replace("<", " ").replace(">", " ")
            .replace("#", " number ").replace("[", " ").replace("]", " ").replace("{", " ").replace("}", " ")
        s = s.replace("...", "…").replace(Regex("…+"), "…")
        s = s.replace(Regex("[!]{2,}"), "!").replace(Regex("[?]{2,}"), "?").replace(Regex("[?!]{2,}"), "?")
        s = s.replace(Regex("\\s+-+\\s+|--+|–"), " — ")
        s = s.replace(Regex("[\"“”]"), " ")
        s = ELONGATED.replace(s) { m -> m.groupValues[1].repeat(2) }
        // Keep only what the phonemizer can use.
        val sb = StringBuilder(s.length)
        for (ch in s) {
            when {
                ch.isLetter() || ch == '\'' || ch == '-' -> sb.append(ch)
                ch in KEEP_PUNCT -> sb.append(ch)
                ch.isDigit() -> sb.append(' ').append(NumberWords.digits(ch.toString())).append(' ')
                else -> sb.append(' ')
            }
        }
        return sb.toString().replace(Regex("\\s+"), " ").replace(Regex(" ([,.!?;:…])"), "$1").trim()
    }

    private fun replaceUrls(s: String): String = URL.replace(s) { m ->
        val host = m.groupValues[2].removePrefix("www.").substringBefore('/').substringBefore(':')
        if (host.isBlank()) " a link " else " a link to " + host.replace(".", " dot ") + " "
    }

    private fun replaceEmoji(s: String): String {
        val sb = StringBuilder()
        var lastWord: String? = null
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            val n = Character.charCount(cp)
            if (isEmojiCodePoint(cp)) {
                // Consume a full emoji sequence: modifiers, variation selectors, ZWJ joins.
                var j = i + n
                var prevZwj = false
                while (j < s.length) {
                    val c2 = s.codePointAt(j)
                    val joins = c2 == 0x200D || c2 == 0xFE0F || c2 in 0x1F3FB..0x1F3FF || c2 == 0x20E3 ||
                        c2 in 0xE0020..0xE007F || (prevZwj && isEmojiCodePoint(c2))
                    if (!joins) break
                    prevZwj = c2 == 0x200D
                    j += Character.charCount(c2)
                }
                val word = if (emojiMode == EmojiMode.DESCRIBE) describeEmoji(cp) else null
                if (word != null && word != lastWord) sb.append(" (").append(word).append(") ")
                lastWord = word
                i = j
                continue
            }
            if (!Character.isWhitespace(cp)) lastWord = null
            sb.appendCodePoint(cp)
            i += n
        }
        return sb.toString().replace(Regex("\\(([^()]+)\\)"), ", $1,")
    }

    private fun expandShorthand(s: String): String =
        SHORTHAND_RE.replace(s) { m ->
            val key = m.value.lowercase()
            SHORTHAND[key] ?: m.value
        }

    private fun number(raw: String): String {
        val clean = raw.replace(",", "")
        if (clean.contains('.')) return NumberWords.decimal(clean)
        val neg = clean.startsWith("-")
        val digits = clean.removePrefix("-")
        val words = when {
            digits.length > 1 && digits.startsWith("0") -> NumberWords.digits(digits)
            !raw.contains(',') && digits.length == 4 && digits.toInt() in 1100..2099 -> NumberWords.year(digits.toInt())
            digits.length > 12 -> NumberWords.digits(digits)
            else -> NumberWords.cardinal(digits.toLong())
        }
        return if (neg) "minus $words" else words
    }

    private fun currency(symbol: String, whole: String, cents: String): String {
        val (unit, units, sub, subs) = when (symbol) {
            "£" -> listOf("pound", "pounds", "penny", "pence")
            "€" -> listOf("euro", "euros", "cent", "cents")
            else -> listOf("dollar", "dollars", "cent", "cents")
        }
        val w = whole.replace(",", "").toLongOrNull() ?: 0
        val c = cents.toLongOrNull() ?: 0
        val parts = ArrayList<String>()
        if (w > 0 || c == 0L) parts += NumberWords.cardinal(w) + " " + if (w == 1L) unit else units
        if (c > 0) parts += NumberWords.cardinal(c) + " " + if (c == 1L) sub else subs
        return parts.joinToString(" and ")
    }

    private fun time(h: String, m: String, suffix: String): String {
        val hour = h.toLong()
        val min = m.toLong()
        val head = NumberWords.cardinal(hour)
        val mid = when {
            min == 0L && suffix.isEmpty() -> " o'clock"
            min == 0L -> ""
            min < 10 -> " oh " + NumberWords.cardinal(min)
            else -> " " + NumberWords.cardinal(min)
        }
        return head + mid + if (suffix.isNotEmpty()) " " + ampm(suffix) else ""
    }

    private fun ampm(s: String) = if (s.lowercase().startsWith("a")) "AM" else "PM"

    private fun splitCamel(s: String) = s.replace(Regex("([a-z])([A-Z])"), "$1 $2")

    companion object {
        private const val KEEP_PUNCT = ".,!?;:…—()"
        private val URL = Regex("(?i)\\b(https?://|www\\.)([^\\s<>\"]+)")
        private val EMAIL = Regex("\\b([A-Za-z0-9._%+-]+)@([A-Za-z0-9.-]+\\.[A-Za-z]{2,})\\b")
        private val HASHTAG = Regex("(?<![\\w&])#([A-Za-z][A-Za-z0-9_]*)")
        private val MENTION = Regex("(?<![\\w.])@([A-Za-z][A-Za-z0-9_]*)")
        private val CURRENCY = Regex("([$£€])\\s?(\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.(\\d{1,2}))?\\b")
        private val TIME = Regex("\\b(\\d{1,2}):(\\d{2})\\s*((?i:[ap]\\.?m\\.?))?(?![\\w])")
        private val AMPM = Regex("\\b(\\d{1,2})\\s*((?i:[ap]\\.?m\\.?))(?![\\w])")
        private val PHONE = Regex("(?<![\\d])(?:\\+?1[ .-]?)?\\(?\\d{3}\\)?[ .-]\\d{3}[ .-]\\d{4}(?![\\d])")
        private val PERCENT = Regex("(-?\\d+(?:\\.\\d+)?)\\s?%")
        private val ORDINAL = Regex("\\b(\\d{1,3}(?:,\\d{3})*|\\d+)(?:st|nd|rd|th)\\b", RegexOption.IGNORE_CASE)
        private val RANGE = Regex("\\b(\\d+)\\s?-\\s?(\\d+)\\b")
        private val DECADE = Regex("\\b(1[0-9]{2}0|20[0-9]0)'?s\\b")
        /** Dotted abbreviations like "U.S." or "a.m." become one acronym token. */
        private val DOTTED = Regex("\\b(?:[A-Za-z]\\.){2,}(?![A-Za-z])")
        private val NUMBER = Regex("-?(?:\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.\\d+)?|\\.\\d+")
        private val TITLES = Regex("\\b(Dr|Mr|Mrs|Ms|St|Jr|Sr|Mt|Prof|vs|etc|approx|appt|dept)\\.", RegexOption.IGNORE_CASE)
        private val TITLE_MAP = mapOf(
            "dr" to "Doctor", "mr" to "Mister", "mrs" to "Missus", "ms" to "Miz", "st" to "Saint", "jr" to "Junior",
            "sr" to "Senior", "mt" to "Mount", "prof" to "Professor", "vs" to "versus", "etc" to "et cetera",
            "approx" to "approximately", "appt" to "appointment", "dept" to "department",
        )
        private val ELONGATED = Regex("([A-Za-z])\\1{2,}")

        private val SHORTHAND = mapOf(
            "omg" to "oh my gosh", "brb" to "be right back", "btw" to "by the way", "idk" to "I don't know",
            "imo" to "in my opinion", "imho" to "in my humble opinion", "tbh" to "to be honest",
            "ttyl" to "talk to you later", "np" to "no problem", "ty" to "thank you", "thx" to "thanks",
            "tysm" to "thank you so much", "pls" to "please", "plz" to "please", "u" to "you", "r" to "are", "ur" to "your",
            "ppl" to "people", "rn" to "right now", "nvm" to "never mind", "gtg" to "got to go", "g2g" to "got to go",
            "omw" to "on my way", "hbu" to "how about you", "wyd" to "what are you doing", "wbu" to "what about you",
            "jk" to "just kidding", "irl" to "in real life", "fyi" to "FYI", "asap" to "ASAP", "lol" to "LOL",
            "lmao" to "LMAO", "rofl" to "rolling on the floor laughing", "smh" to "shaking my head",
            "bc" to "because", "cuz" to "because", "tmrw" to "tomorrow", "tmr" to "tomorrow", "tn" to "tonight",
            "bday" to "birthday", "msg" to "message", "txt" to "text", "k" to "okay", "kk" to "okay", "ok" to "okay",
            "w/" to "with", "w/o" to "without", "luv" to "love", "ily" to "I love you", "xoxo" to "hugs and kisses",
            "b4" to "before", "gr8" to "great", "l8r" to "later", "2day" to "today", "2nite" to "tonight",
            "hmu" to "hit me up", "ikr" to "I know, right", "atm" to "at the moment", "eta" to "ETA",
        )
        private val SHORTHAND_RE = Regex(
            "(?<![\\w'])(" + SHORTHAND.keys.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) } + ")(?![\\w'])",
            RegexOption.IGNORE_CASE,
        )

        fun isEmojiCodePoint(cp: Int): Boolean =
            cp in 0x1F000..0x1FAFF || cp in 0x2600..0x27BF || cp in 0x2B00..0x2BFF || cp in 0x1F900..0x1F9FF ||
                cp == 0x2764 || cp == 0x2763 || cp in 0x2300..0x23FF ||
                cp == 0x00A9 || cp == 0x00AE || cp == 0x2122 || cp in 0x3030..0x303D || cp in 0x1F1E6..0x1F1FF

        private val EMOJI_WORDS: Map<Int, String> = mapOf(
            0x1F602 to "laughing", 0x1F923 to "laughing", 0x1F606 to "laughing", 0x1F605 to "nervous laugh",
            0x1F600 to "smiling", 0x1F603 to "smiling", 0x1F604 to "smiling", 0x1F601 to "grinning", 0x1F642 to "smiling",
            0x1F60A to "smiling", 0x263A to "smiling", 0x1F609 to "winking", 0x1F60D to "heart eyes", 0x1F970 to "loving",
            0x1F618 to "kiss", 0x1F617 to "kiss", 0x1F48B to "kiss", 0x1F60E to "cool", 0x1F914 to "thinking",
            0x1F644 to "eye roll", 0x1F62D to "crying", 0x1F622 to "sad", 0x1F61E to "sad", 0x2639 to "sad",
            0x1F641 to "sad", 0x1F62C to "grimacing", 0x1F621 to "angry", 0x1F620 to "angry", 0x1F92C to "angry",
            0x1F631 to "shocked", 0x1F62E to "surprised", 0x1F633 to "embarrassed", 0x1F974 to "woozy",
            0x1F634 to "sleepy", 0x1F92A to "silly", 0x1F61C to "silly", 0x1F61B to "tongue out", 0x1F643 to "upside down smile",
            0x1F97A to "pleading", 0x1F60F to "smirking", 0x1F937 to "shrug", 0x1F926 to "face palm",
            0x1F44D to "thumbs up", 0x1F44E to "thumbs down", 0x1F44F to "clapping", 0x1F64F to "thank you",
            0x1F44B to "waving", 0x1F44C to "okay", 0x270C to "peace", 0x1F91E to "fingers crossed", 0x1F4AA to "strong",
            0x1F64C to "celebrating", 0x1F389 to "party", 0x1F973 to "party", 0x1F382 to "birthday cake", 0x1F381 to "gift",
            0x2764 to "heart", 0x1F495 to "hearts", 0x1F496 to "heart", 0x1F497 to "heart", 0x1F499 to "heart",
            0x1F49A to "heart", 0x1F49B to "heart", 0x1F49C to "heart", 0x1F5A4 to "heart", 0x1F90D to "heart",
            0x1F9E1 to "heart", 0x1F498 to "heart", 0x1F494 to "broken heart", 0x1F525 to "fire", 0x2728 to "sparkles",
            0x2B50 to "star", 0x1F31F to "star", 0x1F4AF to "one hundred", 0x2705 to "check", 0x2714 to "check",
            0x274C to "x", 0x26A0 to "warning", 0x1F440 to "eyes", 0x1F480 to "dead", 0x1F4A9 to "poop",
            0x1F37B to "cheers", 0x1F37A to "beer", 0x1F377 to "wine", 0x2615 to "coffee", 0x1F355 to "pizza",
            0x1F697 to "car", 0x1F3E0 to "home", 0x1F4F1 to "phone", 0x1F4F8 to "camera", 0x1F436 to "dog",
            0x1F431 to "cat", 0x1F319 to "moon", 0x2600 to "sun", 0x1F308 to "rainbow", 0x1F334 to "palm tree",
            0x1F92D to "giggling", 0x1F917 to "hug", 0x1FAF6 to "heart hands", 0x1F918 to "rock on",
        )

        fun describeEmoji(cp: Int): String? = EMOJI_WORDS[cp]
    }
}
