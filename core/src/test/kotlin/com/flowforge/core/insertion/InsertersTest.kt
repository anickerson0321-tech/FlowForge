package com.flowforge.core.insertion

import com.flowforge.core.settings.InsertionPreference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Insertion and undo against simulations of the two Android contracts FlowForge relies on:
 *  - an accessibility node backed by an EditText (ACTION_SET_TEXT / SET_SELECTION / PASTE), and
 *  - an InputConnection (commitText replaces the selection, cursor ends after the new text).
 * The simulations stand in for *other apps*; FlowForge's own logic runs unmodified.
 */
class InsertersTest {

    /** Simulated EditText reached through accessibility. */
    private class SimField(
        var text: String,
        var selStart: Int,
        var selEnd: Int = selStart,
        val cls: String = "android.widget.EditText",
        val password: Boolean = false,
        val acceptsSetText: Boolean = true,
        val acceptsPaste: Boolean = true,
        val textReadable: Boolean = true,
        override val maxTextLength: Int = -1,
        /** Some apps rewrite what they receive (e.g. emoji shortcodes, input filters). */
        val transform: (String) -> String = { it },
        val pkg: String = "com.example.chat",
    ) : AccessibleField {
        var clipboard = ""
        override val target get() = TargetField(pkg, cls, true, password, false, acceptsSetText, acceptsPaste, true, textReadable && read() != null)
        override val key = "$pkg|1|message"
        override fun read(): FieldState? = if (textReadable) FieldState(text, selStart, selEnd) else null
        override fun setText(text: String): Boolean {
            if (!acceptsSetText) return false
            this.text = transform(text)
            selStart = this.text.length
            selEnd = selStart
            return true
        }
        override fun setSelection(start: Int, end: Int): Boolean {
            selStart = start.coerceIn(0, text.length)
            selEnd = end.coerceIn(0, text.length)
            return true
        }
        override fun paste(): Boolean {
            if (!acceptsPaste) return false
            val a = minOf(selStart, selEnd)
            val b = maxOf(selStart, selEnd)
            text = text.substring(0, a) + clipboard + text.substring(b)
            selStart = a + clipboard.length
            selEnd = selStart
            return true
        }
        override fun refreshedText(): String? = if (textReadable) text else null
    }

    private class SimClipboard(val field: SimField) : ClipboardPort {
        var cleared = false
        override fun put(text: String) { field.clipboard = text }
        override fun clearSoon() { cleared = true }
    }

    private fun insert(
        f: SimField?,
        text: String,
        inserter: AccessibilityInserter = AccessibilityInserter(),
        pref: InsertionPreference = InsertionPreference.AUTOMATIC,
        clip: SimClipboard? = f?.let { SimClipboard(it) },
    ) = inserter.insert(f, text, pref, "com.flowforge.dictation", emptySet(), smartSpacing = true, clipboard = clip ?: SimClipboard(SimField("", 0)), clearClipboardAfterPaste = true)

    @Test
    fun `set text inserts at the cursor and keeps the rest of the field`() {
        val f = SimField("Hi team. See you", 8)
        val r = insert(f, "Meeting moved to Friday.")
        assertEquals("Hi team. Meeting moved to Friday. See you", f.text)
        assertEquals(InsertResult.Inserted(InsertionMethod.SET_TEXT, verified = true, undoable = true, app = "com.example.chat"), r)
        assertEquals("Hi team. Meeting moved to Friday.".length, f.selStart) // cursor right after the insertion
    }

    @Test
    fun `selection is replaced and nothing else changes`() {
        val f = SimField("Let's meet Thursday at noon", 11, 19)
        insert(f, "Friday")
        assertEquals("Let's meet Friday at noon", f.text)
        assertEquals(17, f.selStart)
    }

    @Test
    fun `rich editors use paste which preserves surrounding content`() {
        val f = SimField("<b>Hello</b> world", 12, cls = "android.webkit.WebView")
        val clip = SimClipboard(f)
        val r = insert(f, "there", clip = clip)
        assertTrue(r is InsertResult.Inserted && r.method == InsertionMethod.PASTE)
        assertEquals("<b>Hello</b> there world", f.text)
        assertTrue("dictation must not linger on the clipboard", clip.cleared)
        // Undo would have to rewrite the whole rich field as plain text, so it is not offered.
        assertFalse((r as InsertResult.Inserted).undoable)
    }

    @Test
    fun `unreadable text is never rewritten with set text`() {
        val f = SimField("Existing draft", 14, textReadable = false)
        val r = insert(f, "more")
        assertTrue(r is InsertResult.Inserted && r.method == InsertionMethod.PASTE)
        assertTrue(f.text.startsWith("Existing draft"))
    }

    @Test
    fun `refused set text falls back to paste`() {
        val f = SimField("Hello", 5, acceptsSetText = false)
        val r = insert(f, "world")
        assertTrue(r is InsertResult.Inserted && (r as InsertResult.Inserted).method == InsertionMethod.PASTE)
        assertEquals("Hello world", f.text)
    }

    @Test
    fun `field that accepts neither shows the text for manual copy`() {
        val f = SimField("Hello", 5, acceptsSetText = false, acceptsPaste = false)
        assertTrue(insert(f, "world") is InsertResult.Manual)
        assertEquals("Hello", f.text)
    }

    @Test
    fun `password fields are refused and untouched`() {
        val f = SimField("hunter2", 7, password = true)
        assertEquals(InsertResult.Refused(RefusalReason.PASSWORD), insert(f, "secret"))
        assertEquals("hunter2", f.text)
        assertEquals(InsertResult.Refused(RefusalReason.NO_FIELD), insert(null, "x"))
    }

    @Test
    fun `max length falls back to paste instead of truncating via set text`() {
        val f = SimField("1234", 4, maxTextLength = 6)
        val r = insert(f, "5678")
        assertTrue(r is InsertResult.Inserted && (r as InsertResult.Inserted).method == InsertionMethod.PASTE)
    }

    @Test
    fun `app that rewrites text is reported unverified and not undoable`() {
        val f = SimField("Hi", 2, transform = { it.replace(":)", "🙂") })
        val r = insert(f, "great :)") as InsertResult.Inserted
        assertFalse(r.verified)
        assertFalse(r.undoable)
    }

    @Test
    fun `undo restores the field and selection exactly`() {
        val inserter = AccessibilityInserter()
        val f = SimField("Let's meet Thursday at noon", 11, 19)
        insert(f, "Friday", inserter)
        assertTrue(inserter.canUndo)
        val u = inserter.undoLast(f)
        assertTrue(u.success)
        assertEquals("Let's meet Thursday at noon", f.text)
        assertEquals(11, f.selStart)
        assertEquals(19, f.selEnd)
        assertFalse(inserter.canUndo)
    }

    @Test
    fun `undo is refused after the user edits`() {
        val inserter = AccessibilityInserter()
        val f = SimField("Hi", 2)
        insert(f, "there", inserter)
        f.text += "!"
        val u = inserter.undoLast(f)
        assertFalse(u.success)
        assertEquals("Hi there!", f.text)
    }

    @Test
    fun `preview preference never touches the field`() {
        val f = SimField("Hi", 2)
        assertTrue(insert(f, "there", pref = InsertionPreference.PREVIEW) is InsertResult.Manual)
        assertEquals("Hi", f.text)
    }

    @Test
    fun `repeated insertions stack and undo in reverse order`() {
        val inserter = AccessibilityInserter()
        val f = SimField("", 0)
        insert(f, "One.", inserter)
        insert(f, "Two.", inserter)
        assertEquals("One. Two.", f.text)
        inserter.undoLast(f)
        assertEquals("One.", f.text)
        inserter.undoLast(f)
        assertEquals("", f.text)
    }

    // --- InputConnection --------------------------------------------------------------------------

    /** Simulated InputConnection over an editor buffer. */
    private class SimEditor(var text: String, var selStart: Int, var selEnd: Int = selStart) : CursorEditor {
        var batchDepth = 0
        override fun textBeforeCursor(n: Int) = text.substring(maxOf(0, minOf(selStart, selEnd) - n), minOf(selStart, selEnd))
        override fun textAfterCursor(n: Int) = text.substring(maxOf(selStart, selEnd), minOf(text.length, maxOf(selStart, selEnd) + n))
        override fun selectedText(): CharSequence? = if (selStart == selEnd) null else text.substring(minOf(selStart, selEnd), maxOf(selStart, selEnd))
        override fun commit(text: String): Boolean {
            val a = minOf(selStart, selEnd)
            val b = maxOf(selStart, selEnd)
            this.text = this.text.substring(0, a) + text + this.text.substring(b)
            selStart = a + text.length
            selEnd = selStart
            return true
        }
        override fun deleteBeforeCursor(n: Int): Boolean {
            val a = minOf(selStart, selEnd)
            val from = maxOf(0, a - n)
            text = text.substring(0, from) + text.substring(a)
            selStart = from
            selEnd = from
            return true
        }
        override fun beginBatch() { batchDepth++ }
        override fun endBatch() { batchDepth-- }
    }

    @Test
    fun `keyboard insertion with smart spacing and cursor after text`() {
        val e = SimEditor("Dear team,Thanks", 10)
        val ins = CursorInserter()
        assertTrue(ins.insert(e, "see notes below.", "k", smartSpacing = true))
        assertEquals("Dear team, see notes below. Thanks", e.text)
        assertEquals("Dear team, see notes below. ".length, e.selStart)
        assertEquals(0, e.batchDepth)
    }

    @Test
    fun `keyboard insertion replaces the selection`() {
        val e = SimEditor("Meet on Thursday at noon", 8, 16)
        CursorInserter().insert(e, "Friday", "k", smartSpacing = true)
        assertEquals("Meet on Friday at noon", e.text)
    }

    @Test
    fun `keyboard undo restores the replaced selection text`() {
        val e = SimEditor("Meet on Thursday at noon", 8, 16)
        val ins = CursorInserter()
        ins.insert(e, "Friday", "k", smartSpacing = true)
        assertTrue(ins.undoLast(e, "k").success)
        assertEquals("Meet on Thursday at noon", e.text)
    }

    @Test
    fun `keyboard undo refuses after typing and in another field`() {
        val e = SimEditor("Hi", 2)
        val ins = CursorInserter()
        ins.insert(e, "there", "k", smartSpacing = true)
        e.commit("!")
        assertFalse(ins.undoLast(e, "k").success)
        assertEquals("Hi there!", e.text)
        ins.insert(e, "Bye", "k", smartSpacing = true)
        assertFalse(ins.undoLast(e, "other-field").success)
    }

    @Test
    fun `keyboard context reads only near the cursor`() {
        val e = SimEditor("x".repeat(1000) + "end. ", 1005)
        val ctx = CursorInserter().context(e)
        assertTrue(ctx.before.length <= 64)
        assertTrue(ctx.startsSentence)
    }
}
