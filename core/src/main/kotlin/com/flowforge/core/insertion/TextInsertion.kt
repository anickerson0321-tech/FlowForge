package com.flowforge.core.insertion

import com.flowforge.core.cleanup.InsertionContext

/**
 * Snapshot of an editable field. Selection indices follow Android's convention: start may be
 * greater than end for a backwards selection, and -1 means "unknown".
 */
data class FieldState(val text: String, val selectionStart: Int, val selectionEnd: Int) {
    /** Selection clamped to the text and ordered; unknown selection means "end of text". */
    val normalizedSelection: IntRange
        get() {
            val len = text.length
            if (selectionStart < 0 || selectionEnd < 0) return len until len
            val a = selectionStart.coerceIn(0, len)
            val b = selectionEnd.coerceIn(0, len)
            return minOf(a, b) until maxOf(a, b)
        }

    val hasSelection: Boolean get() = !normalizedSelection.isEmpty()

    fun contextAroundSelection(): InsertionContext {
        val sel = normalizedSelection
        return InsertionContext.of(text.substring(0, sel.first), text.substring(sel.last + 1))
    }
}

data class InsertionPlan(
    val before: FieldState,
    /** Text actually inserted, including any smart spacing added around [dictated]. */
    val insertedText: String,
    val dictated: String,
    /** Characters [start, end) of the original text that the insertion replaces (the selection). */
    val replaceStart: Int,
    val replaceEnd: Int,
) {
    val replacedText: String get() = before.text.substring(replaceStart, replaceEnd)
    val newText: String get() = before.text.substring(0, replaceStart) + insertedText + before.text.substring(replaceEnd)
    /** Cursor position after the insertion (collapsed selection). */
    val newCursor: Int get() = replaceStart + insertedText.length
    val after: FieldState get() = FieldState(newText, newCursor, newCursor)
}

object TextInsertionPlanner {
    private const val OPENING = "([{\"“'‘/@#$"
    private const val CLOSING_PUNCT = ",.;:!?)]}\"”'’…%"

    /**
     * Plans inserting [dictated] at the field's cursor, replacing the selection if there is one.
     * Text outside the selection is never modified. With [smartSpacing], a single space is added
     * where the dictated text would otherwise touch an adjacent word.
     */
    fun plan(field: FieldState, dictated: String, smartSpacing: Boolean = true): InsertionPlan {
        val sel = field.normalizedSelection
        val start = sel.first
        val end = sel.last + 1
        var insert = dictated
        if (smartSpacing && insert.isNotEmpty()) {
            val prev = field.text.getOrNull(start - 1)
            val next = field.text.getOrNull(end)
            val first = insert.first()
            val last = insert.last()
            if (prev != null && !prev.isWhitespace() && prev !in OPENING && first !in CLOSING_PUNCT && !first.isWhitespace()) {
                insert = " $insert"
            }
            if (next != null && !next.isWhitespace() && next !in CLOSING_PUNCT && !last.isWhitespace() && last !in OPENING) {
                insert = "$insert "
            }
        }
        return InsertionPlan(field, insert, dictated, start, end)
    }
}

/**
 * Remembers one insertion so it can be undone, but only while the field still contains exactly
 * what FlowForge produced. If the user typed or another app changed the field, undo is refused
 * rather than overwriting their edits.
 */
data class UndoRecord(val plan: InsertionPlan, val targetKey: String, val createdAtMillis: Long) {
    fun canUndo(current: FieldState, key: String): Boolean = key == targetKey && current.text == plan.newText

    /** The state to restore: original text with the original selection. */
    fun restoredState(): FieldState = plan.before.let {
        val sel = it.normalizedSelection
        FieldState(it.text, sel.first, sel.last + 1)
    }
}

/** Small bounded stack of undo records (most recent first). */
class UndoHistory(private val capacity: Int = 10) {
    private val records = ArrayDeque<UndoRecord>()

    fun push(record: UndoRecord) {
        records.addFirst(record)
        while (records.size > capacity) records.removeLast()
    }

    fun peek(): UndoRecord? = records.firstOrNull()
    fun pop(): UndoRecord? = records.removeFirstOrNull()
    fun clear() = records.clear()
    val size: Int get() = records.size
}
