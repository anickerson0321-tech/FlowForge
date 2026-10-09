package com.flowforge.core.cleanup

import com.flowforge.core.text.Tokenizer
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression corpus: sentences with no fillers, stutters or corrections, but full of words that the
 * cleanup rules look at ("like", "actually", "so", "well", "no", "wait", "sorry", "rather", "first",
 * "make that", "scratch"). Light cleanup may only change punctuation and capitalization here.
 */
class WordPreservationTest {
    private val cleaner = TextCleaner()

    private val corpus = listOf(
        "I like how you handled the client call",
        "I actually liked the first draft better",
        "so far the results look good",
        "well that went better than expected",
        "no one told me the meeting moved",
        "please wait for my signal before you start",
        "I'm sorry for the delay on the report",
        "I would rather stay home on Friday",
        "make that call before noon",
        "can you make that change to the slide",
        "we need to scratch that off the list",
        "I mean it when I say thank you",
        "first come first served",
        "the second option costs 40 dollars more",
        "it is not Monday it is Tuesday",
        "she said no to the second offer",
        "there is no way we finish by 5",
        "never send passwords over email",
        "the period after the trial ends is 30 days",
        "remind me to call mom at 6 30 tonight",
        "the meeting is on March 14 at 2 pm",
        "send 3 copies to the Boston office",
        "they don't want to move the deadline",
        "I can't make it on Thursday or Friday",
        "the actual number was 1,250 not 1,520",
        "rather than wait we should start now",
        "sorry seems to be the hardest word",
        "what I mean is we need more time",
        "so what do you think about the plan",
        "like I said the budget is fixed",
        "it was so good that we ordered more",
        "the dot on the map shows our office",
        "I wonder whether they will agree",
        "we finished first in the regional league",
        "number one priority is the safety audit",
        "this is the first and last warning",
        "you can say that again",
        "it is what it is",
        "not now but maybe later",
        "the colon in the time 3:30 is fine",
    )

    private fun words(s: String) = Tokenizer.tokenize(s).filter { it.isWordLike }.map { it.lower }

    @Test
    fun `light cleanup keeps every word`() {
        for (input in corpus) {
            val out = cleaner.clean(input, CleanupOptions(CleanupMode.LIGHT)).text
            assertEquals("input: $input\noutput: $out", words(input), words(out))
        }
    }

    @Test
    fun `professional cleanup keeps every word`() {
        for (input in corpus) {
            val out = cleaner.clean(input, CleanupOptions(CleanupMode.PROFESSIONAL)).text
            assertEquals("input: $input\noutput: $out", words(input), words(out))
        }
    }

    @Test
    fun `verbatim cleanup returns the input unchanged`() {
        for (input in corpus) {
            assertEquals(input, cleaner.clean(input, CleanupOptions(CleanupMode.VERBATIM)).text)
        }
    }
}
