package com.privatevoice.app.ui

import android.app.AlertDialog
import android.app.TimePickerDialog
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.privatevoice.app.model.EngineKind
import com.privatevoice.app.model.SenderAssignment
import com.privatevoice.app.model.VoiceCategory
import com.privatevoice.app.voices.KokoroVoices
import java.util.UUID

object Dialogs {
    private const val THEME = android.R.style.Theme_DeviceDefault_Dialog_Alert

    fun confirm(a: MainActivity, title: String, message: String, onYes: () -> Unit) {
        AlertDialog.Builder(a, THEME)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("Confirm") { _, _ -> onYes() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    fun time(a: MainActivity, minutes: Int, onPick: (Int) -> Unit) {
        TimePickerDialog(a, THEME, { _, h, m -> onPick(h * 60 + m) }, minutes / 60, minutes % 60, false).show()
    }

    /** Picks a concrete voice (no "none" option). */
    fun pickVoice(a: MainActivity, title: String, current: String, prefer: VoiceCategory? = null, onPick: (String) -> Unit) =
        voicePicker(a, title, current, prefer, null) { id -> if (id != null) onPick(id) }

    /**
     * Voice list with a preview button per voice. [prefer] floats voices with that
     * presentation to the top (a sorting convenience only).
     */
    fun voicePicker(a: MainActivity, title: String, current: String?, prefer: VoiceCategory? = null, allowNone: String? = null, onPick: (String?) -> Unit) {
        val ui = a.ui
        val g = a.graph
        val voices = (KokoroVoices.all + g.systemTts.voices()).sortedWith(
            compareBy({ it.engine == EngineKind.SYSTEM }, { prefer != null && it.presentation != prefer }),
        )
        val list = ui.column(8)
        var dialog: AlertDialog? = null
        with(ui) {
            if (allowNone != null) {
                list.valueRow(allowNone, if (current == null) "✓ Selected" else "Select") { onPick(null); dialog?.dismiss() }
                list.divider()
            }
            voices.forEach { v ->
                val selected = v.id == current
                val r = row {
                    minimumHeight = dp(60)
                    val texts = column { layoutParams = lp(w = 0, weight = 1f) }
                    texts.text((if (selected) "✓ " else "") + v.displayName, 16f, if (selected) Palette.PRIMARY else Palette.TEXT, bold = selected)
                    val tag = when { v.engine == EngineKind.SYSTEM -> "System"; v.presentation == VoiceCategory.MASCULINE -> "Masculine"; else -> "Feminine" }
                    texts.caption("$tag · ${v.accent} · ${v.description}")
                    addView(texts)
                    addView(TextView(a).apply {
                        text = "▶"; textSize = 18f; gravity = Gravity.CENTER; setTextColor(Palette.PRIMARY)
                        contentDescription = "Preview ${v.displayName}"
                        background = ripple(rounded(Palette.PRIMARY_CONTAINER, 22))
                        layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
                        setOnClickListener { g.reader.previewVoice(v.id, v.displayName) }
                    })
                    background = ripple(rounded(android.graphics.Color.TRANSPARENT, 12))
                    setOnClickListener { onPick(v.id); dialog?.dismiss() }
                }
                list.addView(r)
            }
        }
        dialog = AlertDialog.Builder(a, THEME)
            .setTitle(title)
            .setView(ScrollView(a).apply { addView(list) })
            .setNegativeButton("Close") { _, _ -> g.reader.stop() }
            .show()
    }

    /** Add or edit a sender assignment. New senders can be picked from messages currently in memory. */
    fun editAssignment(a: MainActivity, existing: SenderAssignment?) {
        val ui = a.ui
        val g = a.graph
        var matchKey = existing?.matchKey
        var appPackage = existing?.appPackage
        var voiceId = existing?.voiceId
        var category = existing?.category ?: VoiceCategory.DEFAULT
        val form = ui.column(20)
        val name = EditText(a).apply {
            hint = "Name as it appears in messages"
            setText(existing?.matchName ?: "")
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setTextColor(Palette.TEXT)
            setHintTextColor(Palette.TEXT_DIM)
            minHeight = ui.dp(52)
        }
        val matchInfo = TextView(a).apply { textSize = 13f; setTextColor(Palette.TEXT_DIM) }
        fun updateMatchInfo() {
            matchInfo.text = if (matchKey != null) "Will match this exact sender ID from ${appPackage?.let { g.appLabel(it) } ?: "the app"} (not just the name)."
            else "Will match the exact display name in any app."
        }
        updateMatchInfo()
        val voiceLabel = TextView(a).apply { textSize = 15f; setTextColor(Palette.PRIMARY); minHeight = ui.dp(48); gravity = Gravity.CENTER_VERTICAL }
        fun updateVoiceLabel() {
            voiceLabel.text = "Voice: " + (voiceId?.let { Screens.voiceName(a, it) } ?: when (category) {
                VoiceCategory.MASCULINE -> "masculine category (${Screens.voiceName(a, g.settings.current.masculineVoice)})"
                VoiceCategory.FEMININE -> "feminine category (${Screens.voiceName(a, g.settings.current.feminineVoice)})"
                VoiceCategory.DEFAULT -> "default voice"
            }) + "  ›"
        }
        updateVoiceLabel()
        with(ui) {
            if (existing == null) {
                val recent = g.repository.all().map { it.sender }.distinctBy { (it.stableKey ?: it.displayName) + it.appPackage }
                if (recent.isNotEmpty()) {
                    form.caption("From messages waiting now (more precise):")
                    val f = flow()
                    recent.forEach { sender ->
                        f.chip(sender.displayName + " · " + g.appLabel(sender.appPackage), false) {
                            name.setText(sender.displayName)
                            matchKey = sender.stableKey
                            appPackage = sender.appPackage
                            updateMatchInfo()
                        }
                    }
                    form.addView(f)
                }
            }
            form.addView(name)
            form.addView(matchInfo)
            form.spacer(12)
            form.caption("Category (your choice — used when no specific voice is set)")
            val cats = flow()
            val catChips = HashMap<VoiceCategory, TextView>()
            fun paint() {
                catChips.forEach { (c, t) ->
                    val sel = c == category
                    t.setTextColor(if (sel) Palette.ON_PRIMARY else Palette.TEXT)
                    t.background = ripple(rounded(if (sel) Palette.PRIMARY else Palette.SURFACE_HIGH, 18, if (sel) null else Palette.OUTLINE))
                }
            }
            listOf(VoiceCategory.DEFAULT to "Default", VoiceCategory.MASCULINE to "Masculine voice", VoiceCategory.FEMININE to "Feminine voice").forEach { (c, label) ->
                catChips[c] = cats.chip(label, c == category) { category = c; paint(); updateVoiceLabel() }
            }
            form.addView(cats)
            voiceLabel.setOnClickListener {
                voicePicker(a, "Voice for ${name.text.ifBlank { "sender" }}", voiceId, prefer = category.takeIf { it != VoiceCategory.DEFAULT }, allowNone = "Use category / default voice") { id ->
                    voiceId = id; updateVoiceLabel()
                }
            }
            form.addView(voiceLabel)
        }
        AlertDialog.Builder(a, THEME)
            .setTitle(if (existing == null) "Add sender" else "Edit ${existing.label}")
            .setView(ScrollView(a).apply { addView(form) })
            .setPositiveButton("Save") { _, _ ->
                val n = name.text.toString().trim()
                if (n.isEmpty()) { g.toast("Enter a name"); return@setPositiveButton }
                // If the name was edited away from the picked sender, fall back to name matching.
                val keepKey = matchKey != null && (existing?.matchName == n || g.repository.all().any { it.sender.stableKey == matchKey && it.sender.displayName == n } || existing == null)
                g.assignments.upsert(
                    SenderAssignment(
                        id = existing?.id ?: UUID.randomUUID().toString(),
                        label = n,
                        matchKey = if (keepKey) matchKey else null,
                        matchName = n,
                        appPackage = if (keepKey) appPackage else null,
                        voiceId = voiceId,
                        category = category,
                        updatedAt = System.currentTimeMillis(),
                    ),
                )
                a.render()
            }
            .setNegativeButton("Cancel") { _, _ -> g.reader.stop() }
            .show()
    }
}
