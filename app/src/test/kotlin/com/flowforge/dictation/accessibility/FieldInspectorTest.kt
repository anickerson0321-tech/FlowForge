package com.flowforge.dictation.accessibility

import android.text.InputType
import android.view.accessibility.AccessibilityNodeInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Runs against the real Android 16 framework classes (pure-Java parts) on the JVM. */
class FieldInspectorTest {
    private fun node(
        text: CharSequence? = null,
        selStart: Int = -1,
        selEnd: Int = -1,
        inputType: Int = InputType.TYPE_CLASS_TEXT,
        password: Boolean = false,
        hint: CharSequence? = null,
        showingHint: Boolean = false,
        viewId: String? = null,
        actions: List<AccessibilityNodeInfo.AccessibilityAction> = listOf(
            AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_TEXT,
            AccessibilityNodeInfo.AccessibilityAction.ACTION_PASTE,
            AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_SELECTION,
        ),
    ) = AccessibilityNodeInfo().apply {
        packageName = "com.example.chat"
        className = "android.widget.EditText"
        isEditable = true
        isPassword = password
        this.inputType = inputType
        this.text = text
        if (selStart >= 0) setTextSelection(selStart, selEnd)
        hintText = hint
        isShowingHintText = showingHint
        viewIdResourceName = viewId
        actions.forEach { addAction(it) }
    }

    @Test
    fun `plain field is read with its selection`() {
        val n = node("Hello world", 6, 11)
        val s = FieldInspector.state(n)!!
        assertEquals("Hello world", s.text)
        assertEquals(6 until 11, s.normalizedSelection)
        val t = FieldInspector.target(n)
        assertTrue(t.supportsSetText && t.supportsPaste && t.supportsSetSelection && t.textReliable)
        assertFalse(t.isPassword || t.isSensitive)
    }

    @Test
    fun `hint text is treated as an empty field`() {
        val n = node("Type a message", 0, 0, hint = "Type a message", showingHint = true)
        assertEquals("", FieldInspector.state(n)!!.text)
    }

    @Test
    fun `unknown cursor or missing text is unreliable so insertion pastes instead of rewriting`() {
        assertNull(FieldInspector.state(node("Existing text")))
        assertNull(FieldInspector.state(node(null, 0, 0)))
        assertFalse(FieldInspector.target(node("Existing text")).textReliable)
    }

    @Test
    fun `password fields are detected by flag and by input type`() {
        assertTrue(FieldInspector.target(node("x", 1, 1, password = true)).isPassword)
        val variations = listOf(
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD,
        )
        variations.forEach { assertTrue("inputType=$it", FieldInspector.target(node("", 0, 0, inputType = it)).isPassword) }
        assertFalse(FieldInspector.isPasswordInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS))
        assertFalse(FieldInspector.isPasswordInputType(InputType.TYPE_CLASS_NUMBER))
    }

    @Test
    fun `sensitive fields are detected from hints and view ids`() {
        assertTrue(FieldInspector.target(node("", 0, 0, hint = "Card number")).isSensitive)
        assertTrue(FieldInspector.target(node("", 0, 0, hint = "Enter the verification code")).isSensitive)
        assertTrue(FieldInspector.target(node("", 0, 0, viewId = "com.bank:id/pin_entry")).isSensitive)
        assertTrue(FieldInspector.target(node("", 0, 0, hint = "CVV")).isSensitive)
        // Words that merely contain "pin" are not sensitive.
        assertFalse(FieldInspector.target(node("", 0, 0, hint = "Shipping address")).isSensitive)
        assertFalse(FieldInspector.target(node("", 0, 0, viewId = "com.app:id/spinner_note")).isSensitive)
        assertFalse(FieldInspector.target(node("", 0, 0, hint = "What's your opinion?")).isSensitive)
    }

    @Test
    fun `missing actions are reported`() {
        val t = FieldInspector.target(node("x", 1, 1, actions = emptyList()))
        assertFalse(t.supportsSetText || t.supportsPaste)
    }

    @Test
    fun `very long text is not rewritten`() {
        val long = "a".repeat(25_000)
        assertNull(FieldInspector.state(node(long, 5, 5)))
    }
}
