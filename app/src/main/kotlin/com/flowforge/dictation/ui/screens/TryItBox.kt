package com.flowforge.dictation.ui.screens

import android.os.SystemClock
import android.view.View
import android.widget.EditText
import android.widget.TextView
import com.flowforge.core.cleanup.InsertionContext
import com.flowforge.core.insertion.FieldState
import com.flowforge.core.insertion.TextInsertionPlanner
import com.flowforge.core.insertion.UndoHistory
import com.flowforge.core.insertion.UndoRecord
import com.flowforge.core.recognition.exposure
import com.flowforge.core.session.Phase
import com.flowforge.core.session.SessionState
import com.flowforge.dictation.dictation.DeliveryReport
import com.flowforge.dictation.dictation.DictationController
import com.flowforge.dictation.dictation.DictationOutput
import com.flowforge.dictation.graph
import com.flowforge.dictation.overlay.BubbleView
import com.flowforge.dictation.ui.MainActivity
import com.flowforge.dictation.ui.kit.Ui

/**
 * In-app dictation test: the full pipeline (engine → cleanup → cursor-aware insertion → undo)
 * against a local text field, so engines and cleanup modes can be tried without enabling the
 * overlay or accessibility service.
 */
class TryItBox(private val host: MainActivity, private val ui: Ui) : DictationController.Surface, BubbleView.Callbacks {
    private val controller = DictationController(host, this)
    private val undo = UndoHistory(5)
    private lateinit var field: EditText
    private lateinit var status: TextView
    private lateinit var details: TextView
    private lateinit var mic: BubbleView
    private var lastOutput: DictationOutput? = null

    fun build(): View {
        val p = ui.palette
        field = ui.input("Tap the mic and speak. Text appears at the cursor.", multiLine = true).apply {
            minLines = 3
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
        }
        status = ui.caption(idleStatus()).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        details = ui.caption("").apply { visibility = View.GONE }
        mic = BubbleView(host, this)
        val controls = ui.row(8).apply {
            addView(mic)
            addView(ui.button("Undo", Ui.ButtonStyle.TONAL) { undoLast() })
            addView(ui.button("Clear", Ui.ButtonStyle.TEXT) { field.setText(""); undo.clear() })
        }
        return ui.card(
            ui.column(spacingDp = 10).apply {
                addView(ui.text("Try it here", 18f, bold = true))
                addView(field, ui.matchWidth())
                addView(controls)
                addView(status)
                addView(details)
            },
            p.surface,
        )
    }

    private fun idleStatus(): String {
        val s = host.graph.settings.dictation
        return "${s.cleanupMode.label} · ${s.engine.label}. ${s.engine.exposure().description}"
    }

    fun release() = controller.release()

    fun cancelIfActive() {
        if (controller.state.isActive) controller.cancel()
    }

    // BubbleView callbacks
    override fun onBubbleTap() {
        if (!com.flowforge.dictation.ui.Setup.hasMic(host)) {
            host.requestMic()
            return
        }
        if (controller.state.phase == Phase.ERROR) controller.dismiss()
        controller.toggle()
    }

    override fun onBubbleLongPress() {
        if (controller.state.isActive) controller.cancel()
    }

    override fun onDragStart() = Unit
    override fun onDragBy(dx: Int, dy: Int, rawX: Float, rawY: Float) = Unit
    override fun onDragEnd(rawX: Float, rawY: Float) = Unit

    // Surface
    override val surfaceName = "in-app test"

    override fun onStateChanged(state: SessionState) {
        mic.visual = when (state.phase) {
            Phase.IDLE -> BubbleView.Visual.IDLE
            Phase.STARTING -> BubbleView.Visual.STARTING
            Phase.LISTENING -> BubbleView.Visual.LISTENING
            Phase.PROCESSING -> BubbleView.Visual.PROCESSING
            Phase.ERROR -> BubbleView.Visual.ERROR
        }
        mic.level = state.level
        status.text = when (state.phase) {
            Phase.IDLE -> idleStatus()
            Phase.STARTING -> "Starting microphone…"
            Phase.LISTENING -> "Listening: ${state.displayText.ifBlank { "speak now" }}"
            Phase.PROCESSING -> "Processing…"
            Phase.ERROR -> state.error?.let { "${it.message} ${it.suggestion}" + if (state.recoveredText.isNotBlank()) " Captured: “${state.recoveredText}”" else "" } ?: ""
        }
    }

    override fun insertionContext(): InsertionContext = currentField().contextAroundSelection()

    override fun onNotice(message: String) {
        host.toast(message)
    }

    private fun currentField() = FieldState(field.text.toString(), field.selectionStart, field.selectionEnd)

    override fun deliver(output: DictationOutput, done: (DeliveryReport) -> Unit) {
        lastOutput = output
        if (output.wantsUndoOfPrevious) undoLast()
        if (output.text.isNotBlank()) {
            val plan = TextInsertionPlanner.plan(currentField(), output.text, host.graph.settings.dictation.smartSpacing)
            field.setText(plan.newText)
            field.setSelection(plan.newCursor.coerceIn(0, field.text.length))
            undo.push(UndoRecord(plan, "tryit", SystemClock.elapsedRealtime()))
        }
        details.visibility = View.VISIBLE
        details.text = buildString {
            append("Recognized: “").append(output.original.trim()).append("”")
            if (output.cleanup.edits.isNotEmpty()) {
                append("\nChanges: ").append(output.cleanup.edits.joinToString("; ") { it.detail })
            }
            if (output.cleanup.warnings.isNotEmpty()) append("\nKept as spoken: ").append(output.cleanup.warnings.joinToString("; "))
            output.note?.let { append("\n").append(it) }
            append("\nEngine: ").append(output.engineName)
        }
        done(DeliveryReport("inserted:tryit", host.packageName))
    }

    private fun undoLast() {
        val record = undo.peek() ?: return host.toast("Nothing to undo.")
        if (!record.canUndo(currentField().copy(selectionStart = record.plan.newCursor, selectionEnd = record.plan.newCursor), "tryit")) {
            undo.pop()
            host.toast("The text changed after dictation, so undo was skipped.")
            return
        }
        val restored = record.restoredState()
        field.setText(restored.text)
        field.setSelection(restored.selectionStart.coerceIn(0, field.text.length), restored.selectionEnd.coerceIn(0, field.text.length))
        undo.pop()
    }
}
