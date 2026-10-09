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
import com.flowforge.core.insertion.AccessibilityInserter
import com.flowforge.core.insertion.AccessibleField
import com.flowforge.core.insertion.ClipboardPort
import com.flowforge.core.insertion.FieldState
import com.flowforge.core.insertion.InsertResult
import com.flowforge.core.insertion.InsertionMethod
import com.flowforge.core.insertion.TargetField
import com.flowforge.core.insertion.UndoOutcome
import com.flowforge.dictation.graph

/**
 * Inserts dictated text into the focused field of another app. It acts only when the user
 * finishes a dictation (or taps Insert/Undo); it ignores all accessibility events otherwise and
 * keeps no content. The insertion/undo rules live in core ([AccessibilityInserter]) and are unit
 * tested; this class only adapts the accessibility node and clipboard.
 */
class FlowForgeAccessibilityService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private val inserter = AccessibilityInserter(clock = { SystemClock.elapsedRealtime() })

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
        return inserter.insert(
            field = focusedNode()?.let(::NodeField),
            text = text,
            preference = settings.dictation.insertion,
            ownPackage = packageName,
            blockedPackages = settings.blockedPackages,
            smartSpacing = smartSpacing,
            clipboard = clipboardPort,
            clearClipboardAfterPaste = settings.clearClipboardAfterPaste,
            forceMethod = forceMethod,
        )
    }

    fun undoLast(): UndoOutcome = inserter.undoLast(focusedNode()?.let(::NodeField))

    val canUndo: Boolean get() = inserter.canUndo

    /** Adapts a live accessibility node to the core [AccessibleField] contract. */
    private class NodeField(private val node: AccessibilityNodeInfo) : AccessibleField {
        override val target: TargetField = FieldInspector.target(node)
        override val key: String = FieldInspector.key(node)
        override val maxTextLength: Int = node.maxTextLength

        override fun read(): FieldState? = FieldInspector.state(node)

        override fun setText(text: String): Boolean {
            val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
            return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        }

        override fun setSelection(start: Int, end: Int): Boolean {
            val args = Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, start)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, end)
            }
            return node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, args)
        }

        override fun paste(): Boolean = node.performAction(AccessibilityNodeInfo.ACTION_PASTE)

        override fun refreshedText(): String? {
            node.refresh()
            return node.text?.toString()
        }
    }

    private val clipboardPort = object : ClipboardPort {
        private val clipboard get() = getSystemService(ClipboardManager::class.java)

        override fun put(text: String) {
            val clip = ClipData.newPlainText(CLIP_LABEL, text)
            clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, false) }
            clipboard.setPrimaryClip(clip)
        }

        override fun clearSoon() {
            // Best effort: if something else was copied meanwhile, Android may already hold it.
            handler.postDelayed({ runCatching { clipboard.clearPrimaryClip() } }, CLEAR_CLIP_DELAY_MS)
        }
    }

    companion object {
        private const val CLIP_LABEL = "FlowForge dictation"
        private const val CLEAR_CLIP_DELAY_MS = 1500L

        @Volatile
        var instance: FlowForgeAccessibilityService? = null
            private set

        val isRunning: Boolean get() = instance != null
    }
}
