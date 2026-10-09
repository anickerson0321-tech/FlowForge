package com.flowforge.core.insertion

import com.flowforge.core.cleanup.InsertionContext
import com.flowforge.core.settings.InsertionPreference

// =================================================================================================
// Accessibility-style insertion (another app's field, reached through an accessibility node)
// =================================================================================================

/** The focused field in another app, as seen through accessibility. Implemented by the Android layer. */
interface AccessibleField {
    val target: TargetField
    /** Identifies the field across calls (package, window, view id). */
    val key: String
    /** Maximum text length the field accepts, or <= 0 if unknown. */
    val maxTextLength: Int

    /** Text and selection, or null when they can't be trusted. */
    fun read(): FieldState?

    /** ACTION_SET_TEXT. */
    fun setText(text: String): Boolean

    /** ACTION_SET_SELECTION. */
    fun setSelection(start: Int, end: Int): Boolean

    /** ACTION_PASTE (pastes the current clipboard at the cursor, replacing any selection). */
    fun paste(): Boolean

    /** Re-reads the field's text after an action (null if unavailable). */
    fun refreshedText(): String?
}

interface ClipboardPort {
    fun put(text: String)
    /** Removes the dictation from the clipboard a little later (best effort). */
    fun clearSoon()
}

sealed class InsertResult {
    data class Inserted(val method: InsertionMethod, val verified: Boolean, val undoable: Boolean, val app: String) : InsertResult()
    /** Show the text for manual copy/insert (preference, unsupported field, ...). */
    data class Manual(val reason: String?) : InsertResult()
    data class Refused(val reason: RefusalReason) : InsertResult()
}

data class UndoOutcome(val success: Boolean, val message: String)

/**
 * Inserts dictation into an [AccessibleField] without disturbing anything outside the cursor or
 * selection, verifies the result, and keeps an undo record that is only honored while the field
 * still contains exactly what was inserted.
 */
class AccessibilityInserter(private val undo: UndoHistory = UndoHistory(5), private val clock: () -> Long = { 0L }) {

    val canUndo: Boolean get() = undo.peek() != null

    fun insert(
        field: AccessibleField?,
        text: String,
        preference: InsertionPreference,
        ownPackage: String,
        blockedPackages: Set<String>,
        smartSpacing: Boolean,
        clipboard: ClipboardPort,
        clearClipboardAfterPaste: Boolean,
        forceMethod: InsertionMethod? = null,
    ): InsertResult {
        val decision = InsertionPolicy.decide(field?.target, preference, ownPackage, blockedPackages)
        if (decision is InsertionDecision.Refuse) return InsertResult.Refused(decision.reason)
        field!!
        var method = forceMethod ?: (decision as InsertionDecision.Insert).method
        if (method == InsertionMethod.SET_TEXT && !field.target.supportsSetText) method = InsertionMethod.PASTE
        if (method == InsertionMethod.PASTE && !field.target.supportsPaste) method = InsertionMethod.MANUAL
        return when (method) {
            InsertionMethod.SET_TEXT -> setText(field, text, smartSpacing)
                ?: if (field.target.supportsPaste) paste(field, text, smartSpacing, clipboard, clearClipboardAfterPaste) else InsertResult.Manual(null)
            InsertionMethod.PASTE -> paste(field, text, smartSpacing, clipboard, clearClipboardAfterPaste)
            else -> InsertResult.Manual(null)
        }
    }

    /** Returns null if the field refused or ignored ACTION_SET_TEXT (caller falls back to paste). */
    private fun setText(field: AccessibleField, text: String, smartSpacing: Boolean): InsertResult? {
        val before = field.read() ?: return null
        val plan = TextInsertionPlanner.plan(before, text, smartSpacing)
        if (field.maxTextLength in 1 until plan.newText.length) return null
        if (!field.setText(plan.newText)) return null
        field.setSelection(plan.newCursor, plan.newCursor)
        val after = field.refreshedText()
        val verified = after == plan.newText
        if (!verified && after == before.text) return null // the app ignored the action
        if (verified) undo.push(UndoRecord(plan, field.key, clock()))
        return InsertResult.Inserted(InsertionMethod.SET_TEXT, verified, undoable = verified, app = field.target.packageName)
    }

    private fun paste(
        field: AccessibleField,
        text: String,
        smartSpacing: Boolean,
        clipboard: ClipboardPort,
        clearAfter: Boolean,
    ): InsertResult {
        val before = field.read()
        val plan = before?.let { TextInsertionPlanner.plan(it, text, smartSpacing) }
        clipboard.put(plan?.insertedText ?: text)
        if (!field.paste()) return InsertResult.Manual("This app didn't accept a paste. The text is on your clipboard.")
        if (clearAfter) clipboard.clearSoon()
        val verified = plan != null && field.refreshedText() == plan.newText
        if (verified) undo.push(UndoRecord(plan!!, field.key, clock()))
        return InsertResult.Inserted(
            InsertionMethod.PASTE, verified, undoable = verified && field.target.supportsSetText, app = field.target.packageName,
        )
    }

    fun undoLast(field: AccessibleField?): UndoOutcome {
        val record = undo.peek() ?: return UndoOutcome(false, "Nothing to undo.")
        field ?: return UndoOutcome(false, "Tap into the text field first.")
        val current = field.read()
        if (current == null || !record.canUndo(current, field.key)) {
            undo.pop()
            return UndoOutcome(false, "The text changed after dictation, so undo was skipped to protect your edits.")
        }
        val restored = record.restoredState()
        if (!field.setText(restored.text)) return UndoOutcome(false, "This app doesn't allow FlowForge to undo here.")
        field.setSelection(restored.selectionStart, restored.selectionEnd)
        undo.pop()
        return UndoOutcome(true, "Dictation undone.")
    }
}

// =================================================================================================
// Keyboard-style insertion (InputConnection)
// =================================================================================================

/** The subset of android.view.inputmethod.InputConnection that FlowForge uses. */
interface CursorEditor {
    fun textBeforeCursor(n: Int): CharSequence?
    fun textAfterCursor(n: Int): CharSequence?
    fun selectedText(): CharSequence?
    /** commitText(text, 1): replaces the selection (or inserts at the cursor); cursor ends after it. */
    fun commit(text: String): Boolean
    /** deleteSurroundingText(n, 0). */
    fun deleteBeforeCursor(n: Int): Boolean
    fun beginBatch()
    fun endBatch()
}

/** Keyboard insertion with smart spacing and cursor-relative undo. */
class CursorInserter(private val undo: UndoHistory = UndoHistory(10), private val clock: () -> Long = { 0L }) {
    val canUndo: Boolean get() = undo.peek() != null

    fun context(editor: CursorEditor): InsertionContext = InsertionContext.of(
        editor.textBeforeCursor(InsertionContext.MAX_CONTEXT_CHARS) ?: "",
        editor.textAfterCursor(InsertionContext.MAX_CONTEXT_CHARS) ?: "",
    )

    /** Inserts at the cursor, replacing any selection; only a small window around the cursor is read. */
    fun insert(editor: CursorEditor, text: String, fieldKey: String, smartSpacing: Boolean): Boolean {
        val before = editor.textBeforeCursor(InsertionContext.MAX_CONTEXT_CHARS)?.toString() ?: ""
        val selected = editor.selectedText()?.toString() ?: ""
        val after = editor.textAfterCursor(InsertionContext.MAX_CONTEXT_CHARS)?.toString() ?: ""
        val window = FieldState(before + selected + after, before.length, before.length + selected.length)
        val plan = TextInsertionPlanner.plan(window, text, smartSpacing)
        editor.beginBatch()
        val ok = try {
            editor.commit(plan.insertedText)
        } finally {
            editor.endBatch()
        }
        if (ok) undo.push(UndoRecord(plan, fieldKey, clock()))
        return ok
    }

    /** Removes the last dictation if the text before the cursor still ends with it; restores a replaced selection. */
    fun undoLast(editor: CursorEditor, fieldKey: String): UndoOutcome {
        val record = undo.peek() ?: return UndoOutcome(false, "Nothing to undo.")
        if (record.targetKey != fieldKey) {
            undo.pop()
            return UndoOutcome(false, "That dictation was in another field.")
        }
        val before = editor.textBeforeCursor(record.plan.insertedText.length) ?: ""
        val step = record.cursorRelativeUndo(before)
        if (step == null) {
            undo.pop()
            return UndoOutcome(false, "The text changed after dictation, so undo was skipped to protect your edits.")
        }
        editor.beginBatch()
        try {
            editor.deleteBeforeCursor(step.first)
            if (step.second.isNotEmpty()) editor.commit(step.second)
        } finally {
            editor.endBatch()
        }
        undo.pop()
        return UndoOutcome(true, "Dictation undone.")
    }

    fun clear() = undo.clear()
}
