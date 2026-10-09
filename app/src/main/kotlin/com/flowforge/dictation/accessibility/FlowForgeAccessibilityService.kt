package com.flowforge.dictation.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.flowforge.core.cleanup.InsertionContext
import com.flowforge.core.insertion.InsertionDecision
import com.flowforge.core.insertion.InsertionMethod
import com.flowforge.core.insertion.InsertionPolicy
import com.flowforge.core.insertion.RefusalReason
import com.flowforge.core.insertion.TextInsertionPlanner
import com.flowforge.core.insertion.UndoHistory
import com.flowforge.core.insertion.UndoRecord
import com.flowforge.dictation.graph

sealed class InsertResult {
    data class Inserted(val method: InsertionMethod, val verified: Boolean, val undoable: Boolean, val app: String) : InsertResult()
    /** Show the text for manual copy/insert (preference, unsupported field, or app switch). */
    data class Manual(val reason: String?) : InsertResult()
    data class Refused(val reason: RefusalReason) : InsertResult()
}

/**
 * Inserts dictated text into the focused field of another app. It acts only when the user
 * finishes a dictation (or taps Insert/Undo); it ignores all accessibility events otherwise and
 * keeps no content.
 */
class FlowForgeAccessibilityService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private val undo = UndoHistory(capacity = 5)

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Intentionally empty: FlowForge never collects other apps' content from events.
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun focusedNode(): AccessibilityNodeInfo? =
        findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable || it.isFocused }

    /** Package of the app whose text field is focused, if any. */
    fun focusedPackage(): String? = focusedNode()?.packageName?.toString()

    /** Short text around the cursor for capitalization/spacing decisions; empty for protected fields. */
    fun insertionContext(): InsertionContext {
        val node = focusedNode() ?: return InsertionContext.EMPTY
        val target = FieldInspector.target(node)
        if (target.isPassword || target.isSensitive) return InsertionContext.EMPTY
        return FieldInspector.state(node)?.contextAroundSelection() ?: InsertionContext.EMPTY
    }

    fun insert(text: String, smartSpacing: Boolean, forceMethod: InsertionMethod? = null): InsertResult {
        val settings = graph.settings
        val node = focusedNode()
        val target = node?.let(FieldInspector::target)
        val decision = InsertionPolicy.decide(target, settings.dictation.insertion, packageName, settings.blockedPackages)
        if (decision is InsertionDecision.Refuse) return InsertResult.Refused(decision.reason)
        node!!
        target!!
        var method = forceMethod ?: (decision as InsertionDecision.Insert).method
        if (method == InsertionMethod.SET_TEXT && !target.supportsSetText) method = InsertionMethod.PASTE
        if (method == InsertionMethod.PASTE && !target.supportsPaste) method = InsertionMethod.MANUAL
        return when (method) {
            InsertionMethod.SET_TEXT -> setTextInsert(node, text, smartSpacing) ?: pasteInsert(node, text, smartSpacing)
            InsertionMethod.PASTE -> pasteInsert(node, text, smartSpacing)
            else -> InsertResult.Manual(null)
        }
    }

    /** Returns null if the field refused ACTION_SET_TEXT (caller falls back to paste). */
    private fun setTextInsert(node: AccessibilityNodeInfo, text: String, smartSpacing: Boolean): InsertResult? {
        val before = FieldInspector.state(node) ?: return null
        val plan = TextInsertionPlanner.plan(before, text, smartSpacing)
        if (node.maxTextLength in 1 until plan.newText.length) return null
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, plan.newText)
        }
        if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return null
        setSelection(node, plan.newCursor, plan.newCursor)
        node.refresh()
        val after = node.text?.toString()
        val verified = after == plan.newText
        if (!verified && after == before.text) return null // nothing happened
        if (verified) undo.push(UndoRecord(plan, FieldInspector.key(node), SystemClock.elapsedRealtime()))
        return InsertResult.Inserted(InsertionMethod.SET_TEXT, verified, undoable = verified, app = node.packageName?.toString().orEmpty())
    }

    private fun pasteInsert(node: AccessibilityNodeInfo, text: String, smartSpacing: Boolean): InsertResult {
        val before = FieldInspector.state(node)
        val plan = before?.let { TextInsertionPlanner.plan(it, text, smartSpacing) }
        val payload = plan?.insertedText ?: text
        val clipboard = getSystemService(ClipboardManager::class.java)
        val clip = ClipData.newPlainText(CLIP_LABEL, payload)
        clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, false) }
        clipboard.setPrimaryClip(clip)
        if (!node.performAction(AccessibilityNodeInfo.ACTION_PASTE)) return InsertResult.Manual("This app didn't accept a paste. The text is on your clipboard.")
        if (graph.settings.clearClipboardAfterPaste) {
            // Don't leave dictated text on the clipboard. Best effort: Android may already have
            // replaced it if something else was copied in the meantime.
            handler.postDelayed({ runCatching { clipboard.clearPrimaryClip() } }, CLEAR_CLIP_DELAY_MS)
        }
        node.refresh()
        val verified = plan != null && node.text?.toString() == plan.newText
        if (verified) undo.push(UndoRecord(plan!!, FieldInspector.key(node), SystemClock.elapsedRealtime()))
        return InsertResult.Inserted(InsertionMethod.PASTE, verified, undoable = verified && FieldInspector.target(node).supportsSetText, app = node.packageName?.toString().orEmpty())
    }

    /**
     * Restores the field to its state before the last FlowForge insertion, only if it still holds
     * exactly what FlowForge produced. Returns a user-facing message.
     */
    fun undoLast(): UndoOutcome {
        val record = undo.peek() ?: return UndoOutcome(false, "Nothing to undo.")
        val node = focusedNode() ?: return UndoOutcome(false, "Tap into the text field first.")
        val current = FieldInspector.state(node)
        if (current == null || !record.canUndo(current, FieldInspector.key(node))) {
            undo.pop()
            return UndoOutcome(false, "The text changed after dictation, so undo was skipped to protect your edits.")
        }
        val restored = record.restoredState()
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, restored.text)
        }
        if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
            return UndoOutcome(false, "This app doesn't allow FlowForge to undo here.")
        }
        setSelection(node, restored.selectionStart, restored.selectionEnd)
        undo.pop()
        return UndoOutcome(true, "Dictation undone.")
    }

    val canUndo: Boolean get() = undo.peek() != null

    private fun setSelection(node: AccessibilityNodeInfo, start: Int, end: Int) {
        val args = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, start)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, end)
        }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, args)
    }

    data class UndoOutcome(val success: Boolean, val message: String)

    companion object {
        private const val CLIP_LABEL = "FlowForge dictation"
        private const val CLEAR_CLIP_DELAY_MS = 1500L

        @Volatile
        var instance: FlowForgeAccessibilityService? = null
            private set

        val isRunning: Boolean get() = instance != null
    }
}
