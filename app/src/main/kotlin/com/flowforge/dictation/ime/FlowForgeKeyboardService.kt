package com.flowforge.dictation.ime

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.TextView
import com.flowforge.core.cleanup.CleanupMode
import com.flowforge.core.cleanup.InsertionContext
import com.flowforge.core.insertion.CursorEditor
import com.flowforge.core.insertion.CursorInserter
import com.flowforge.core.insertion.UndoOutcome
import com.flowforge.core.session.Phase
import com.flowforge.core.session.SessionState
import com.flowforge.dictation.accessibility.FieldInspector
import com.flowforge.dictation.dictation.DeliveryReport
import com.flowforge.dictation.dictation.DictationController
import com.flowforge.dictation.dictation.DictationOutput
import com.flowforge.dictation.graph
import com.flowforge.dictation.overlay.BubbleView
import com.flowforge.dictation.ui.MainActivity
import com.flowforge.dictation.ui.kit.Ui
import com.flowforge.dictation.ui.kit.dp

/**
 * FlowForge Voice Keyboard: dictation through Android's input-method API.
 *
 * This is the most reliable and Play-policy-friendly insertion path: text goes through
 * InputConnection.commitText, which inserts at the cursor or replaces the selection exactly like
 * typing, preserves the rest of the field, and needs neither the accessibility service nor the
 * overlay permission. The microphone is disabled in password fields.
 */
class FlowForgeKeyboardService : InputMethodService(), DictationController.Surface, BubbleView.Callbacks {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var controller: DictationController
    private val inserter = CursorInserter(clock = { SystemClock.elapsedRealtime() })
    private var lastOutput: DictationOutput? = null

    private var ui: Ui? = null
    private var micButton: BubbleView? = null
    private var statusView: TextView? = null
    private var modeChip: TextView? = null
    private var enterKey: TextView? = null
    private var undoKey: TextView? = null
    private var originalKey: TextView? = null

    private var passwordField = false
    /** Field the current/last dictation started in. */
    private var sessionFieldKey: String? = null
    private var incognitoField = false
    private var lastPanelKey: Any? = null

    override fun onCreate() {
        super.onCreate()
        controller = DictationController(this, this)
    }

    override fun onDestroy() {
        controller.release()
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // ---------------------------------------------------------------------------------------------
    // Keyboard view
    // ---------------------------------------------------------------------------------------------

    override fun onCreateInputView(): View {
        val ui = Ui(this).also { this.ui = it }
        val p = ui.palette
        val root = ui.column(spacingDp = 8).apply {
            setBackgroundColor(p.surfaceVariant)
            setPadding(dp(10), dp(8), dp(10), dp(10))
        }

        // Row 1: status/transcript + cleanup mode chip
        val status = ui.text("Tap the mic and speak", 15f, p.onSurface, maxLines = 3).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            minHeight = dp(44)
            gravity = Gravity.CENTER_VERTICAL
        }
        statusView = status
        val chip = ui.chip(graph.settings.dictation.cleanupMode.label, selected = true) { cycleMode() }.apply {
            contentDescription = "Cleanup mode. Double-tap to change."
        }
        modeChip = chip
        root.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(status, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(chip)
            },
        )

        // Row 2: switch, backspace, MIC, space, enter
        val mic = BubbleView(this, this)
        micButton = mic
        val row2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        // Always offered: FlowForge has no letter keys, so users need a quick way back to their keyboard.
        row2.addView(key(ui, "🌐", "Switch keyboard") { switchKeyboard() }.apply {
            setOnLongClickListener { showPicker(); true }
        }, weighted())
        row2.addView(repeatingKey(ui, "⌫", "Delete") { sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL) }, weighted())
        row2.addView(mic, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = dp(6)
            rightMargin = dp(6)
        })
        row2.addView(key(ui, "space", "Space") { commit(" ") }, weighted())
        val enter = key(ui, "⏎", "Enter") { pressEnter() }
        enterKey = enter
        row2.addView(enter, weighted())
        root.addView(row2)

        // Row 3: undo, original, punctuation
        val row3 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val undoBtn = key(ui, "Undo", "Undo last dictation") { undoLast() }
        undoKey = undoBtn
        val origBtn = key(ui, "Original", "Replace last dictation with the original transcript") { useOriginal() }
        originalKey = origBtn
        row3.addView(undoBtn, weighted(1.4f))
        row3.addView(origBtn, weighted(1.6f))
        listOf(",", ".", "?", "!").forEach { mark -> row3.addView(key(ui, mark, mark) { commit(mark) }, weighted()) }
        root.addView(row3)
        updateActionKeys()
        render(controller.state)
        return root
    }

    private fun weighted(w: Float = 1f) = LinearLayout.LayoutParams(0, dp(52), w).apply {
        leftMargin = dp(3)
        rightMargin = dp(3)
    }

    private fun key(ui: Ui, label: String, description: String, onClick: () -> Unit): TextView =
        ui.text(label, 16f, ui.palette.onSurface, bold = true).apply {
            gravity = Gravity.CENTER
            background = ui.ripple(ui.rounded(ui.palette.surface, 12))
            contentDescription = description
            isClickable = true
            isFocusable = true
            setOnClickListener {
                performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                onClick()
            }
        }

    /** Key that repeats while held (backspace). */
    private fun repeatingKey(ui: Ui, label: String, description: String, action: () -> Unit): TextView {
        val view = key(ui, label, description, action)
        val repeater = object : Runnable {
            override fun run() {
                action()
                handler.postDelayed(this, 60)
            }
        }
        view.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    action()
                    handler.postDelayed(repeater, 400)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    handler.removeCallbacks(repeater)
                }
            }
            true
        }
        return view
    }

    // ---------------------------------------------------------------------------------------------
    // Field lifecycle
    // ---------------------------------------------------------------------------------------------

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        passwordField = FieldInspector.isPasswordInputType(info.inputType)
        incognitoField = (info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0
        if (!restarting) inserter.clear()
        updateActionKeys()
        modeChip?.text = graph.settings.dictation.cleanupMode.label
        lastPanelKey = null
        render(controller.state)
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        // Never keep recording when the keyboard is no longer visible.
        if (controller.state.isActive) controller.interrupt("the keyboard was closed")
        super.onFinishInputView(finishingInput)
    }

    override fun onWindowHidden() {
        if (controller.state.isActive) controller.interrupt("the keyboard was hidden")
        super.onWindowHidden()
    }

    private fun updateActionKeys() {
        val info = currentInputEditorInfo
        val action = info?.imeOptions?.and(EditorInfo.IME_MASK_ACTION) ?: EditorInfo.IME_ACTION_NONE
        val noEnterAction = info != null && (info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
        enterKey?.text = when {
            noEnterAction -> "⏎"
            action == EditorInfo.IME_ACTION_SEND -> "Send"
            action == EditorInfo.IME_ACTION_SEARCH -> "Search"
            action == EditorInfo.IME_ACTION_GO -> "Go"
            action == EditorInfo.IME_ACTION_NEXT -> "Next"
            action == EditorInfo.IME_ACTION_DONE -> "Done"
            else -> "⏎"
        }
        val hasUndo = inserter.canUndo
        undoKey?.alpha = if (hasUndo) 1f else 0.45f
        originalKey?.alpha = if (hasUndo && lastOutput?.let { it.original.trim() != it.text } == true) 1f else 0.45f
    }

    private fun pressEnter() {
        val info = currentInputEditorInfo
        val ic = currentInputConnection ?: return
        val action = info?.imeOptions?.and(EditorInfo.IME_MASK_ACTION) ?: EditorInfo.IME_ACTION_NONE
        val noEnterAction = info != null && (info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
        if (!noEnterAction && action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
            ic.performEditorAction(action)
        } else {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
        }
    }

    private fun commit(text: String) {
        currentInputConnection?.commitText(text, 1)
    }

    private fun switchKeyboard() {
        if (!switchToPreviousInputMethod()) showPicker()
    }

    private fun showPicker() {
        getSystemService(InputMethodManager::class.java).showInputMethodPicker()
    }

    private fun cycleMode() {
        val modes = CleanupMode.values()
        val next = modes[(graph.settings.dictation.cleanupMode.ordinal + 1) % modes.size]
        graph.settings.updateDictation { it.copy(cleanupMode = next) }
        modeChip?.text = next.label
        statusView?.text = next.summary
    }

    // ---------------------------------------------------------------------------------------------
    // Mic (BubbleView callbacks)
    // ---------------------------------------------------------------------------------------------

    override fun onBubbleTap() {
        if (passwordField) {
            statusView?.text = "Dictation is turned off in password fields."
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            statusView?.text = "Microphone permission needed — opening FlowForge…"
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(MainActivity.EXTRA_SCREEN, "permissions"))
            return
        }
        if (controller.state.phase == Phase.ERROR) controller.dismiss()
        if (!controller.state.isActive) sessionFieldKey = fieldKey()
        controller.toggle()
    }

    /** Long-press cancels recording or a slow cloud/AI processing step. */
    override fun onBubbleLongPress() {
        if (controller.state.isActive) {
            controller.cancel()
            statusView?.text = "Dictation cancelled."
        }
    }

    override fun onDragStart() = Unit
    override fun onDragBy(dx: Int, dy: Int, rawX: Float, rawY: Float) = Unit
    override fun onDragEnd(rawX: Float, rawY: Float) = Unit

    // ---------------------------------------------------------------------------------------------
    // DictationController.Surface
    // ---------------------------------------------------------------------------------------------

    override val surfaceName = "keyboard"

    override fun isIncognito(): Boolean = incognitoField

    override fun onStateChanged(state: SessionState) = render(state)

    private fun render(state: SessionState) {
        val mic = micButton ?: return
        mic.visual = when {
            passwordField -> BubbleView.Visual.DISABLED
            state.phase == Phase.IDLE -> BubbleView.Visual.IDLE
            state.phase == Phase.STARTING -> BubbleView.Visual.STARTING
            state.phase == Phase.LISTENING -> BubbleView.Visual.LISTENING
            state.phase == Phase.PROCESSING -> BubbleView.Visual.PROCESSING
            else -> BubbleView.Visual.ERROR
        }
        mic.level = state.level
        val key = Triple(state.phase, state.displayText, state.error)
        if (key == lastPanelKey) return
        lastPanelKey = key
        statusView?.text = when {
            passwordField -> "Dictation is turned off in password fields."
            state.phase == Phase.STARTING -> "Starting microphone…"
            state.phase == Phase.LISTENING -> state.displayText.ifBlank { "Listening… tap the mic when you're done" }
            state.phase == Phase.PROCESSING -> "Processing…"
            state.phase == Phase.ERROR -> state.error?.let { e ->
                buildString {
                    append(e.message).append(' ').append(e.suggestion)
                    if (state.recoveredText.isNotBlank()) append(" Captured: “").append(state.recoveredText).append("” (tap Original to insert it)")
                }
            } ?: "Something went wrong."
            else -> statusView?.text ?: ""
        }
    }

    override fun insertionContext(): InsertionContext {
        if (passwordField) return InsertionContext.EMPTY
        val ic = currentInputConnection ?: return InsertionContext.EMPTY
        return inserter.context(ConnectionEditor(ic))
    }

    override fun onNotice(message: String) {
        statusView?.text = message
    }

    override fun deliver(output: DictationOutput, done: (DeliveryReport) -> Unit) {
        lastOutput = output
        val target = currentInputEditorInfo?.packageName.orEmpty()
        if (output.wantsUndoOfPrevious) {
            val outcome = undoLast()
            if (output.text.isBlank()) {
                statusView?.text = outcome.message
                done(DeliveryReport("undo", target))
                return
            }
        }
        if (passwordField) {
            statusView?.text = "Not inserted: password field."
            done(DeliveryReport("refused:password", target))
            return
        }
        val inserted = insertText(output.text)
        statusView?.text = if (inserted) {
            if (output.aiApplied) "Inserted (AI cleanup). Undo or Original are available." else "Inserted. Undo or Original are available."
        } else {
            copyToClipboard(output.text)
            "Couldn't reach the text field — your text was copied to the clipboard."
        }
        updateActionKeys()
        done(DeliveryReport(if (inserted) "inserted:ime" else "copied", target))
    }

    /** Commits [text] at the cursor (replacing any selection) with smart spacing; records undo. */
    private fun insertText(text: String): Boolean {
        val ic = currentInputConnection ?: return false
        return inserter.insert(ConnectionEditor(ic), text, fieldKey(), graph.settings.dictation.smartSpacing)
    }

    private fun fieldKey(): String {
        val info = currentInputEditorInfo ?: return ""
        return "${info.packageName}|${info.fieldId}"
    }

    /** Removes the last dictation if the text before the cursor still ends with it. */
    private fun undoLast(): UndoOutcome {
        val ic = currentInputConnection ?: return UndoOutcome(false, "No text field.")
        val outcome = inserter.undoLast(ConnectionEditor(ic), fieldKey())
        updateActionKeys()
        statusView?.text = outcome.message
        return outcome
    }

    /** Adapts Android's InputConnection to the core [CursorEditor] contract. */
    private class ConnectionEditor(private val ic: InputConnection) : CursorEditor {
        override fun textBeforeCursor(n: Int): CharSequence? = ic.getTextBeforeCursor(n, 0)
        override fun textAfterCursor(n: Int): CharSequence? = ic.getTextAfterCursor(n, 0)
        override fun selectedText(): CharSequence? = ic.getSelectedText(0)
        override fun commit(text: String): Boolean = ic.commitText(text, 1)
        override fun deleteBeforeCursor(n: Int): Boolean = ic.deleteSurroundingText(n, 0)
        override fun beginBatch() {
            ic.beginBatchEdit()
        }
        override fun endBatch() {
            ic.endBatchEdit()
        }
    }

    private fun useOriginal() {
        val out = lastOutput
        val recovered = controller.state.recoveredText
        when {
            controller.state.phase == Phase.ERROR && recovered.isNotBlank() -> {
                // Only put recovered speech back into the field it was dictated in, never into a
                // password field or a different field the user has since moved to.
                if (!passwordField && fieldKey() == sessionFieldKey) {
                    insertText(recovered)
                    statusView?.text = "Inserted what was captured before the interruption."
                } else {
                    copyToClipboard(recovered)
                    statusView?.text = "The captured text belongs to another field, so it was copied to the clipboard."
                }
                controller.dismiss()
            }
            out == null -> statusView?.text = "No dictation yet."
            else -> {
                val outcome = undoLast()
                if (outcome.success) {
                    insertText(out.original.trim())
                    statusView?.text = "Inserted the original transcript."
                } else {
                    statusView?.text = outcome.message
                }
            }
        }
        updateActionKeys()
    }

    private fun copyToClipboard(text: String) {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("FlowForge dictation", text))
    }
}
