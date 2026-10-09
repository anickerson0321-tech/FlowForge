package com.flowforge.core.text

/** Semantic classes used to decide whether two tokens are interchangeable in a self-correction. */
enum class WordClass { WEEKDAY, MONTH, RELATIVE_DAY, NUMBER, TIME, PROPER_NAME, OTHER }

object Lexicon {
    val WEEKDAYS = setOf("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday")
    val MONTHS = setOf(
        "january", "february", "march", "april", "may", "june", "july", "august",
        "september", "october", "november", "december",
    )
    val RELATIVE_DAYS = setOf("today", "tomorrow", "yesterday", "tonight", "weekend")
    val NUMBER_WORDS = setOf(
        "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
        "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen",
        "nineteen", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety",
        "hundred", "thousand", "million", "billion", "trillion", "half", "quarter", "dozen",
    )
    val ORDINAL_WORDS = setOf(
        "first", "second", "third", "fourth", "fifth", "sixth", "seventh", "eighth", "ninth", "tenth",
    )
    val TIME_WORDS = setOf("noon", "midnight", "am", "pm", "a.m.", "p.m.", "o'clock")

    /**
     * Words that negate or reverse meaning. The safety guard requires every one of these to survive
     * cleanup (contractions are matched by suffix in [isNegation]).
     */
    val NEGATIONS = setOf(
        "not", "no", "never", "none", "nobody", "nothing", "nowhere", "neither", "nor", "cannot",
        "without", "dunno", "nope", "nah", "don't", "doesn't", "didn't", "won't", "wouldn't", "can't",
        "couldn't", "shouldn't", "isn't", "aren't", "wasn't", "weren't", "haven't", "hasn't", "hadn't",
        "mustn't", "needn't", "ain't", "shan't", "mightn't",
    )

    /** Pure hesitation sounds; removing them never changes meaning. */
    val FILLERS = setOf("um", "umm", "ummm", "uh", "uhh", "uhm", "er", "erm", "ah", "ahh", "hmm", "hm", "mm", "mmm", "eh")

    /** Words whose immediate repetition is usually intentional and must not be collapsed. */
    val INTENTIONAL_REPEATS = setOf(
        "had", "that", "is", "very", "really", "so", "much", "many", "more", "bye", "ha", "haha", "no",
        "knock", "chop", "night", "now", "there", "well", "pretty", "far", "long", "again", "over",
        "round", "go", "yeah", "yes", "please", "hip", "tsk", "blah", "boo", "choo", "cha", "la",
    )

    val SUBJECT_PRONOUNS = setOf(
        "i", "we", "you", "he", "she", "they", "it", "there", "i'm", "we're", "you're", "they're",
        "it's", "he's", "she's", "there's", "i'll", "we'll", "you'll", "they'll", "he'll", "she'll",
        "i've", "we've", "you've", "they've", "i'd", "we'd", "you'd", "they'd", "let's",
    )

    val QUESTION_STARTERS = setOf(
        "who", "what", "when", "where", "why", "how", "which", "whose", "is", "are", "am", "was", "were",
        "do", "does", "did", "can", "could", "would", "will", "should", "shall", "may", "might", "have",
        "has", "isn't", "aren't", "don't", "doesn't", "didn't", "can't", "won't", "wouldn't", "shouldn't",
    )

    val GREETINGS = setOf("hey", "hi", "hello", "dear", "morning", "evening", "afternoon", "thanks", "yo")

    /** Sentence-initial words that are normally followed by a comma. */
    val INTRODUCTORY = setOf(
        "yes", "yeah", "no", "okay", "ok", "well", "however", "unfortunately", "fortunately", "honestly",
        "anyway", "besides", "meanwhile", "also", "finally", "additionally", "otherwise", "instead",
        "therefore", "sorry", "thanks", "please",
    )

    /** Function words that may be safely lowercased when inserted mid-sentence. */
    val LOWERCASABLE_STARTERS = setOf(
        "and", "but", "or", "so", "then", "the", "a", "an", "to", "of", "in", "on", "at", "for", "with",
        "we", "it", "this", "that", "these", "those", "they", "you", "he", "she", "because", "if", "when",
        "which", "who", "also", "just", "maybe", "not", "is", "are", "was", "were", "will", "would", "can",
        "could", "should", "my", "our", "your", "their", "his", "her", "its", "some", "all", "as", "from",
    )

    fun isNegation(lower: String): Boolean = lower in NEGATIONS || lower.endsWith("n't")

    fun isNumberLike(t: Token): Boolean =
        t.kind == TokenKind.NUMBER || t.lower in NUMBER_WORDS

    fun classify(t: Token, sentenceInitial: Boolean): WordClass {
        val w = t.lower
        return when {
            w in WEEKDAYS || w.removeSuffix("s") in WEEKDAYS -> WordClass.WEEKDAY
            w in MONTHS && w != "may" && w != "march" -> WordClass.MONTH
            (w == "may" || w == "march") && t.text[0].isUpperCase() && !sentenceInitial -> WordClass.MONTH
            w in RELATIVE_DAYS -> WordClass.RELATIVE_DAY
            TIME_PATTERN.matches(w) -> WordClass.TIME
            isNumberLike(t) -> WordClass.NUMBER
            t.kind == TokenKind.WORD && t.text[0].isUpperCase() && !sentenceInitial && w != "i" &&
                !w.startsWith("i'") -> WordClass.PROPER_NAME
            else -> WordClass.OTHER
        }
    }

    private val TIME_PATTERN = Regex("""\d{1,2}:\d{2}(am|pm|a\.m\.|p\.m\.)?|\d{1,2}(am|pm|a\.m\.|p\.m\.)""")

    /** NUMBER and TIME items can correct each other ("at 3, I mean 4:30"). */
    fun compatible(a: WordClass, b: WordClass): Boolean =
        a == b || (a == WordClass.NUMBER && b == WordClass.TIME) || (a == WordClass.TIME && b == WordClass.NUMBER)
}
