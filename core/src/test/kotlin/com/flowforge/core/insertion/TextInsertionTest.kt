package com.flowforge.core.insertion

import com.flowforge.core.settings.InsertionPreference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextInsertionTest {

    @Test
    fun `inserts at the cursor and keeps surrounding text`() {
        val field = FieldState("Hello world", 5, 5)
        val plan = TextInsertionPlanner.plan(field, "there")
        assertEquals("Hello there world", plan.newText)
        assertEquals("Hello there".length, plan.newCursor) // leading space added, existing space reused
        assertEquals("", plan.replacedText)
    }

    @Test
    fun `cursor at end of text`() {
        val plan = TextInsertionPlanner.plan(FieldState("Dear team,", 10, 10), "Thanks for today.")
        assertEquals("Dear team, Thanks for today.", plan.newText)
        assertEquals(plan.newText.length, plan.newCursor)
    }

    @Test
    fun `empty field`() {
        val plan = TextInsertionPlanner.plan(FieldState("", 0, 0), "Hi")
        assertEquals("Hi", plan.newText)
        assertEquals(2, plan.newCursor)
    }

    @Test
    fun `selection is replaced and nothing else changes`() {
        val field = FieldState("Meet on Thursday at noon", 8, 16)
        val plan = TextInsertionPlanner.plan(field, "Friday")
        assertEquals("Meet on Friday at noon", plan.newText)
        assertEquals("Thursday", plan.replacedText)
        assertEquals(14, plan.newCursor)
    }

    @Test
    fun `backwards selection is normalized`() {
        val plan = TextInsertionPlanner.plan(FieldState("Meet on Thursday at noon", 16, 8), "Friday")
        assertEquals("Meet on Friday at noon", plan.newText)
    }

    @Test
    fun `unknown or out of range selection appends at end`() {
        assertEquals("abc def", TextInsertionPlanner.plan(FieldState("abc", -1, -1), "def").newText)
        assertEquals("abc def", TextInsertionPlanner.plan(FieldState("abc", 99, 120), "def").newText)
    }

    @Test
    fun `smart spacing does not add spaces before punctuation or after openers`() {
        assertEquals("Hello, world", TextInsertionPlanner.plan(FieldState("Hello world", 5, 5), ",").newText)
        assertEquals("(note)", TextInsertionPlanner.plan(FieldState("()", 1, 1), "note").newText)
        assertEquals("@sam", TextInsertionPlanner.plan(FieldState("@", 1, 1), "sam").newText)
        assertEquals("ab", TextInsertionPlanner.plan(FieldState("a", 1, 1), "b", smartSpacing = false).newText)
    }

    @Test
    fun `multi-line dictation keeps paragraphs`() {
        val plan = TextInsertionPlanner.plan(FieldState("", 0, 0), "Intro.\n\nNext part.")
        assertEquals("Intro.\n\nNext part.", plan.newText)
    }

    @Test
    fun `context around selection is limited`() {
        val long = "x".repeat(500)
        val ctx = FieldState(long + "|" + long, 501, 501).contextAroundSelection()
        assertTrue(ctx.before.length <= 64 && ctx.after.length <= 64)
    }

    // --- Undo ---------------------------------------------------------------------------------

    @Test
    fun `undo restores the original text and selection`() {
        val before = FieldState("Meet on Thursday at noon", 8, 16)
        val plan = TextInsertionPlanner.plan(before, "Friday")
        val record = UndoRecord(plan, "app:field", 0)
        assertTrue(record.canUndo(plan.after, "app:field"))
        assertEquals(FieldState("Meet on Thursday at noon", 8, 16), record.restoredState())
    }

    @Test
    fun `undo is refused after the user edits the field`() {
        val plan = TextInsertionPlanner.plan(FieldState("Hi", 2, 2), "there")
        val record = UndoRecord(plan, "app:field", 0)
        assertFalse(record.canUndo(FieldState(plan.newText + "!", 9, 9), "app:field"))
        assertFalse(record.canUndo(plan.after, "other:field"))
    }

    @Test
    fun `cursor relative undo for keyboards`() {
        val plan = TextInsertionPlanner.plan(FieldState("Meet on Thursday", 8, 16), "Friday")
        val record = UndoRecord(plan, "k", 0)
        assertEquals(6 to "Thursday", record.cursorRelativeUndo("Meet on Friday"))
        assertNull(record.cursorRelativeUndo("Meet on Fridays"))
    }

    @Test
    fun `undo history is bounded and most recent first`() {
        val h = UndoHistory(capacity = 2)
        val p = TextInsertionPlanner.plan(FieldState("", 0, 0), "a")
        h.push(UndoRecord(p, "1", 1))
        h.push(UndoRecord(p, "2", 2))
        h.push(UndoRecord(p, "3", 3))
        assertEquals(2, h.size)
        assertEquals("3", h.pop()!!.targetKey)
        assertEquals("2", h.pop()!!.targetKey)
        assertNull(h.pop())
    }

    // --- Policy ---------------------------------------------------------------------------------

    private fun field(
        cls: String = "android.widget.EditText",
        password: Boolean = false,
        sensitive: Boolean = false,
        setText: Boolean = true,
        paste: Boolean = true,
        reliable: Boolean = true,
        pkg: String = "com.example.chat",
        editable: Boolean = true,
    ) = TargetField(pkg, cls, editable, password, sensitive, setText, paste, true, reliable)

    private fun decide(f: TargetField?, pref: InsertionPreference = InsertionPreference.AUTOMATIC) =
        InsertionPolicy.decide(f, pref, ownPackage = "com.flowforge.dictation", blockedPackages = setOf("com.bank.app"))

    @Test
    fun `password and sensitive fields are always refused`() {
        assertEquals(InsertionDecision.Refuse(RefusalReason.PASSWORD), decide(field(password = true)))
        assertEquals(InsertionDecision.Refuse(RefusalReason.PASSWORD), decide(field(password = true), InsertionPreference.PASTE))
        assertEquals(InsertionDecision.Refuse(RefusalReason.SENSITIVE), decide(field(sensitive = true)))
    }

    @Test
    fun `no field, read-only field, own app and blocked apps are refused`() {
        assertEquals(InsertionDecision.Refuse(RefusalReason.NO_FIELD), decide(null))
        assertEquals(InsertionDecision.Refuse(RefusalReason.NOT_EDITABLE), decide(field(editable = false)))
        assertEquals(InsertionDecision.Refuse(RefusalReason.OWN_APP), decide(field(pkg = "com.flowforge.dictation")))
        assertEquals(InsertionDecision.Refuse(RefusalReason.BLOCKED_APP), decide(field(pkg = "com.bank.app")))
    }

    @Test
    fun `plain edit texts use set text, rich editors paste`() {
        assertEquals(InsertionDecision.Insert(InsertionMethod.SET_TEXT), decide(field()))
        assertEquals(InsertionDecision.Insert(InsertionMethod.PASTE), decide(field(cls = "android.webkit.WebView")))
        assertEquals(InsertionDecision.Insert(InsertionMethod.PASTE), decide(field(reliable = false)))
        assertEquals(InsertionDecision.Insert(InsertionMethod.SET_TEXT), decide(field(cls = "android.view.View", paste = false)))
        assertEquals(InsertionDecision.Insert(InsertionMethod.MANUAL), decide(field(setText = false, paste = false)))
    }

    @Test
    fun `user preferences are honored`() {
        assertEquals(InsertionDecision.Insert(InsertionMethod.PASTE), decide(field(), InsertionPreference.PASTE))
        assertEquals(InsertionDecision.Insert(InsertionMethod.MANUAL), decide(field(paste = false), InsertionPreference.PASTE))
        assertEquals(InsertionDecision.Insert(InsertionMethod.MANUAL), decide(field(), InsertionPreference.PREVIEW))
    }

    @Test
    fun `switching apps mid dictation requires confirmation`() {
        assertTrue(InsertionPolicy.requiresConfirmation("com.chat", "com.mail"))
        assertFalse(InsertionPolicy.requiresConfirmation("com.chat", "com.chat"))
        assertFalse(InsertionPolicy.requiresConfirmation(null, "com.chat"))
        assertFalse(InsertionPolicy.requiresConfirmation("com.chat", null))
    }
}
