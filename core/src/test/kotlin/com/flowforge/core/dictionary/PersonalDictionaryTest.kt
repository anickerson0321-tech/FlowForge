package com.flowforge.core.dictionary

import com.flowforge.core.text.Renderer
import com.flowforge.core.text.Tokenizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonalDictionaryTest {
    private val dict = PersonalDictionary(
        listOf(
            DictionaryEntry("a", "Siobhan", listOf("shiv on", "chevonne"), EntryCategory.NAME),
            DictionaryEntry("b", "Kubernetes", listOf("cooper net ease", "kuber netties"), EntryCategory.TERM),
            DictionaryEntry("c", "CAT", emptyList(), EntryCategory.ACRONYM),
            DictionaryEntry("d", "New York City", listOf("new york"), EntryCategory.PLACE),
        ),
    )

    private fun apply(s: String) = Renderer.render(dict.apply(Tokenizer.tokenize(s)).tokens)

    @Test
    fun `spoken forms and re-casing`() {
        assertEquals("ask Siobhan about Kubernetes", apply("ask shiv on about cooper net ease"))
        assertEquals("ask Siobhan about Kubernetes", apply("ask Chevonne about kubernetes"))
    }

    @Test
    fun `only whole words match`() {
        assertEquals("category catalog CAT", apply("category catalog cat"))
        assertEquals("shivon", apply("shivon"))
    }

    @Test
    fun `longest match wins and punctuation is kept`() {
        assertEquals("I love New York City.", apply("I love new york."))
        assertEquals("Siobhan's laptop", apply("chevonne's laptop"))
    }

    @Test
    fun `matches never cross punctuation`() {
        assertEquals("shiv, on the list", apply("shiv, on the list"))
    }

    @Test
    fun `replacements are recorded`() {
        val r = dict.apply(Tokenizer.tokenize("shiv on"))
        assertEquals(listOf(Replacement("shiv on", "Siobhan", "a")), r.replacements)
        assertTrue(r.tokens.single().locked)
    }

    @Test
    fun `empty dictionary changes nothing`() {
        val tokens = Tokenizer.tokenize("hello there")
        assertEquals(tokens, PersonalDictionary.EMPTY.apply(tokens).tokens)
    }

    @Test
    fun `common word warning`() {
        assertTrue(PersonalDictionary.warnsAsCommonWord("rock"))
        assertFalse(PersonalDictionary.warnsAsCommonWord("rock and roll"))
        assertFalse(PersonalDictionary.warnsAsCommonWord("Siobhan"))
    }
}
