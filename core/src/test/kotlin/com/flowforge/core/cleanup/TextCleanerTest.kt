package com.flowforge.core.cleanup

import com.flowforge.core.dictionary.DictionaryEntry
import com.flowforge.core.dictionary.EntryCategory
import com.flowforge.core.dictionary.PersonalDictionary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextCleanerTest {
    private val cleaner = TextCleaner()

    private fun light(s: String, ctx: InsertionContext = InsertionContext.EMPTY) =
        cleaner.clean(s, CleanupOptions(CleanupMode.LIGHT, context = ctx)).text

    private fun pro(s: String, ctx: InsertionContext = InsertionContext.EMPTY) =
        cleaner.clean(s, CleanupOptions(CleanupMode.PROFESSIONAL, context = ctx)).text

    private fun verbatim(s: String) = cleaner.clean(s, CleanupOptions(CleanupMode.VERBATIM)).text

    // --- The specification's reference example -------------------------------------------------

    @Test
    fun `reference example - light cleanup`() {
        assertEquals(
            "Hey Jason, I checked the bathrooms, and we should do it Friday, not Thursday.",
            light("Hey Jason um I checked the bathrooms and actually we should do it Friday not Thursday."),
        )
    }

    @Test
    fun `reference example - professional matches light`() {
        assertEquals(
            "Hey Jason, I checked the bathrooms, and we should do it Friday, not Thursday.",
            pro("Hey Jason um I checked the bathrooms and actually we should do it Friday not Thursday."),
        )
    }

    @Test
    fun `reference example - verbatim keeps every word`() {
        val input = "Hey Jason um I checked the bathrooms and actually we should do it Friday not Thursday."
        assertEquals(input, verbatim(input))
    }

    // --- Spec test phrases -----------------------------------------------------------------------

    @Test
    fun `actually make that Friday replaces the earlier day`() {
        assertEquals("Let's meet Friday.", light("Let's meet Thursday. Actually, make that Friday."))
    }

    @Test
    fun `make that works without actually`() {
        assertEquals("The call is at 4 pm.", light("The call is at 3 pm make that 4 pm"))
    }

    @Test
    fun `I mean Thursday corrects the day`() {
        assertEquals("Let's do it Thursday.", light("Let's do it Wednesday I mean Thursday"))
    }

    @Test
    fun `I mean keeps words between the antecedent and the marker`() {
        assertEquals("Meet on Friday at noon.", light("meet on Thursday at noon I mean Friday"))
    }

    @Test
    fun `I mean as an aside is left alone in light mode`() {
        assertEquals("I mean, it's fine.", light("I mean, it's fine"))
    }

    @Test
    fun `scratch that removes the sentence so far`() {
        assertEquals("I'll be there at 6.", light("I'll be there at 5 scratch that I'll be there at 6"))
    }

    @Test
    fun `scratch that at a sentence start removes the previous sentence`() {
        assertEquals("Call me tomorrow.", light("Send the report today. Scratch that. Call me tomorrow."))
    }

    @Test
    fun `scratch that alone asks to undo the previous insertion`() {
        val r = cleaner.clean("Scratch that.", CleanupOptions(CleanupMode.LIGHT))
        assertEquals(CleanupCommand.UNDO_PREVIOUS_INSERTION, r.command)
        assertEquals("", r.text)
    }

    @Test
    fun `scratch that itch is not a command`() {
        assertEquals("I need to scratch that itch.", light("I need to scratch that itch"))
    }

    @Test
    fun `no dont send that keeps the negation`() {
        assertEquals("No, don't send that.", light("No, don't send that."))
        assertEquals("No, don't send that.", light("no don't send that"))
        assertEquals("No, don't send that.", pro("no don't send that"))
    }

    @Test
    fun `no between two items of the same kind is a correction`() {
        assertEquals("I want three apples.", light("I want two, no, three apples"))
        assertEquals("See you Friday.", light("see you Thursday no Friday"))
    }

    @Test
    fun `no without a matching item is kept`() {
        assertEquals("There are no Mondays off.", light("there are no Mondays off"))
        assertEquals("No one came.", light("no one came"))
        assertEquals("No problem at all.", light("no problem at all"))
    }

    @Test
    fun `repeated words and false starts`() {
        assertEquals("I think we should go.", light("I I I think we should go"))
        assertEquals("We should go to the store.", light("we should we should go to the the store"))
        assertEquals("Can you send the file?", light("can you can you send the file"))
    }

    @Test
    fun `intentional repetition survives`() {
        assertEquals("It was very very good.", light("it was very very good"))
        assertEquals("What it is is a plan.", light("what it is is a plan"))
        assertEquals("He had had enough.", light("he had had enough"))
        assertEquals("No no no.", light("no no no"))
        assertEquals("Bye bye.", light("bye bye"))
    }

    @Test
    fun `numbers are never collapsed or dropped`() {
        assertEquals("The code is 2 2 7 9.", light("the code is 2 2 7 9"))
        assertEquals("Order two two-by-fours.", light("order two two-by-fours"))
        assertEquals("Call 555 555 1234.", light("call 555 555 1234"))
    }

    @Test
    fun `fillers are removed in light mode only`() {
        assertEquals("I think it's ready.", light("um I think uh it's ready"))
        assertEquals("um I think uh it's ready", verbatim("um I think uh it's ready"))
        assertEquals("I think it's ready.", light("I, um, think it's ready"))
    }

    @Test
    fun `meaningful words are not removed`() {
        // "like", "actually" (not after a conjunction), "so" and "well" carry meaning here.
        assertEquals("I like the new design.", light("I like the new design"))
        assertEquals("I actually finished it.", light("I actually finished it"))
        assertEquals("Well done everyone.", light("well done everyone"))
        assertEquals("So far so good.", pro("so far so good"))
        assertEquals("Like I said, it's done.", pro("like I said, it's done"))
    }

    // --- Punctuation and capitalization ----------------------------------------------------------

    @Test
    fun `questions get question marks`() {
        assertEquals("Can you send it today?", light("can you send it today"))
        assertEquals("What time is it?", light("what time is it"))
        assertEquals("Where are we meeting?", light("where are we meeting"))
        assertEquals("Do it Friday.", light("do it Friday"))
        assertEquals("Do the dishes.", light("do the dishes"))
        assertEquals("Do you have a minute?", light("do you have a minute"))
        assertEquals("Did it work?", light("did it work"))
        assertEquals("When we get there we'll call.", light("when we get there we'll call"))
    }

    @Test
    fun `standalone i is capitalized`() {
        assertEquals("I think I'm ready, and I'll go.", light("i think i'm ready and i'll go"))
    }

    @Test
    fun `mid-sentence insertion keeps lowercase and no final period`() {
        val ctx = InsertionContext(before = "We could meet ", after = " if that works")
        assertEquals("and talk tomorrow", light("And talk tomorrow", ctx))
    }

    @Test
    fun `insertion after a finished sentence capitalizes`() {
        val ctx = InsertionContext(before = "Done. ", after = "")
        assertEquals("Thanks for waiting.", light("thanks for waiting", ctx))
    }

    @Test
    fun `names keep their capitals when inserted mid sentence`() {
        val ctx = InsertionContext(before = "Please tell ", after = "")
        assertEquals("Jason about it.", light("Jason about it", ctx))
    }

    // --- Spoken punctuation ----------------------------------------------------------------------

    @Test
    fun `spoken punctuation commands`() {
        assertEquals("Hello, world.", light("hello comma world period"))
        assertEquals("Are you coming?", light("are you coming question mark"))
        assertEquals("First line\nSecond line.", light("first line new line second line"))
        assertEquals("Intro.\n\nNext part.", light("intro period new paragraph next part"))
    }

    @Test
    fun `spoken punctuation also works in verbatim`() {
        assertEquals("hello, world", verbatim("hello comma world"))
    }

    @Test
    fun `punctuation words used as nouns are kept`() {
        assertEquals("The grace period ends Monday.", light("the grace period ends Monday"))
        assertEquals("Add a comma here.", light("add a comma here"))
        assertEquals("Insert the word comma.", light("insert the word literal comma"))
        assertEquals("The period of time was long.", light("the period of time was long"))
    }

    @Test
    fun `recognizer punctuation after a spoken command is not doubled`() {
        assertEquals("Hello, world.", light("Hello comma. world."))
        assertEquals("Really?", light("really question mark."))
    }

    @Test
    fun `web addresses`() {
        assertEquals("Go to example.com.", light("go to example dot com"))
    }

    // --- Professional ---------------------------------------------------------------------------

    @Test
    fun `professional formalizes casual words`() {
        assertEquals("I'm going to call them.", pro("I'm gonna call them"))
        assertEquals("I don't know.", pro("I dunno"))
        assertEquals("We want to ship the API update.", pro("so we wanna ship the api update"))
        assertEquals("I've got to go.", pro("I've gotta go"))
    }

    @Test
    fun `professional drops asides`() {
        assertEquals("It's hard to explain.", pro("it's hard, you know, to explain"))
        assertEquals("We should start now.", pro("well we should start now"))
        assertEquals("You know what I mean.", pro("you know what I mean"))
    }

    @Test
    fun `spoken lists become numbered lists in professional mode`() {
        assertEquals(
            "Agenda for today:\n1. Review the budget\n2. Hire a designer\n3. Ship the app",
            pro("agenda for today first review the budget second hire a designer and third ship the app"),
        )
    }

    @Test
    fun `number markers form lists`() {
        assertEquals(
            "Shopping:\n1. Eggs\n2. Milk",
            pro("shopping number one eggs number two milk"),
        )
    }

    @Test
    fun `ordinals that are not list markers stay`() {
        assertEquals("The first time was better than the second time.", pro("the first time was better than the second time"))
    }

    @Test
    fun `list followed by more text`() {
        assertEquals(
            "Plan:\n1. Draft the memo\n2. Send it\n\nThanks everyone.",
            pro("plan first draft the memo second send it. Thanks everyone"),
        )
    }

    // --- Dictionary ---------------------------------------------------------------------------------

    private val dict = PersonalDictionary(
        listOf(
            DictionaryEntry("1", "Siobhan", listOf("shiv on", "chevonne"), EntryCategory.NAME),
            DictionaryEntry("2", "Kubernetes", listOf("cooper net ease"), EntryCategory.TERM),
            DictionaryEntry("3", "B2B", listOf("b to b"), EntryCategory.ACRONYM),
        ),
    )

    @Test
    fun `dictionary corrections apply in every mode`() {
        val c = TextCleaner(dict)
        assertEquals("ask Siobhan about Kubernetes", c.clean("ask shiv on about cooper net ease", CleanupOptions(CleanupMode.VERBATIM)).text)
        assertEquals("Ask Siobhan about Kubernetes.", c.clean("ask chevonne about kubernetes", CleanupOptions(CleanupMode.LIGHT)).text)
        assertEquals("Our B2B plan.", c.clean("our b to b plan", CleanupOptions(CleanupMode.PROFESSIONAL)).text)
    }

    @Test
    fun `dictionary names can be corrected with I mean`() {
        val c = TextCleaner(dict)
        assertEquals("Send it to Siobhan.", c.clean("send it to John I mean shiv on", CleanupOptions(CleanupMode.LIGHT)).text)
    }

    // --- Safety -----------------------------------------------------------------------------------

    @Test
    fun `cleanup never removes negations numbers or dates`() {
        val inputs = listOf(
            "don't send the 3 invoices until Friday",
            "we can't do Monday or Tuesday",
            "never pay more than 200 dollars",
            "it is not due on March 3rd",
            "nobody should approve the 1,500 dollar refund tomorrow",
            "um I won't be there at 9 30",
        )
        for (input in inputs) {
            for (mode in CleanupMode.values()) {
                val r = cleaner.clean(input, CleanupOptions(mode))
                val before = SafetyGuard.protectedItems(input).filterKeys { it.category != SafetyGuard.Category.NAME }
                val after = SafetyGuard.protectedItems(r.text).filterKeys { it.category != SafetyGuard.Category.NAME }
                assertEquals("mode=$mode input=$input output=${r.text}", before, after)
            }
        }
    }

    @Test
    fun `original transcript is always recoverable`() {
        val input = "um so like we gonna ship it Thursday I mean Friday"
        val r = cleaner.clean(input, CleanupOptions(CleanupMode.PROFESSIONAL))
        assertEquals(input, r.original)
        assertTrue(r.changed)
        assertTrue(r.edits.isNotEmpty())
    }

    @Test
    fun `unchanged text is returned exactly`() {
        val input = "Ready when you are."
        assertEquals(input, light(input))
        assertEquals(input, pro(input))
    }

    @Test
    fun `empty and whitespace input`() {
        assertEquals("", light(""))
        assertEquals("", pro("   "))
    }
}
