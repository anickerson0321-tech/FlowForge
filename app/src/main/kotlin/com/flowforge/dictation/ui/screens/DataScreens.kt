package com.flowforge.dictation.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.text.format.DateUtils
import android.view.View
import android.widget.LinearLayout
import com.flowforge.core.dictionary.DictionaryEntry
import com.flowforge.core.dictionary.EntryCategory
import com.flowforge.core.dictionary.PersonalDictionary
import com.flowforge.core.privacy.CloudPurpose
import com.flowforge.core.privacy.ConsentPolicy
import com.flowforge.core.privacy.RetentionPolicy
import com.flowforge.core.recognition.EngineKind
import com.flowforge.dictation.overlay.DictationService
import com.flowforge.dictation.ui.MainActivity
import com.flowforge.dictation.ui.kit.Ui

// =================================================================================================
// Personal dictionary
// =================================================================================================

class DictionaryScreen(host: MainActivity) : Screen(host) {
    override val title = "Personal dictionary"
    private var category = EntryCategory.NAME

    override fun build(ui: Ui): View {
        val col = ui.column(spacingDp = 8)
        col.addView(ui.caption("Teach FlowForge names, places and terms. Entries stay on this phone (encrypted) and are applied in every cleanup mode. Only whole words change, never parts of other words."))

        col.addView(ui.heading("Add a word"))
        val term = ui.input("How it should be written (e.g. Siobhan, Kubernetes, ACME)")
        val spoken = ui.input("How it sounds or gets misheard, comma-separated (optional)")
        col.addView(term, ui.matchWidth())
        col.addView(spoken, ui.matchWidth())
        val chips = ui.row(6)
        fun renderChips() {
            chips.removeAllViews()
            EntryCategory.values().forEach { c ->
                chips.addView(ui.chip(c.name.lowercase().replaceFirstChar { it.uppercase() }, c == category) {
                    category = c
                    renderChips()
                })
            }
        }
        renderChips()
        col.addView(android.widget.HorizontalScrollView(host).apply { addView(chips) })
        col.addView(ui.button("Add to dictionary") {
            val t = term.text.toString().trim()
            if (t.isEmpty()) {
                host.toast("Type the word first.")
                return@button
            }
            val forms = spoken.text.toString().split(',').map { it.trim() }.filter { it.isNotEmpty() }
            val risky = forms.filter { PersonalDictionary.warnsAsCommonWord(it) }
            val save = {
                graph.background({ graph.dictionary.upsert(DictionaryEntry(graph.dictionary.newId(), t, forms, category)) }) {
                    host.toast("Added “$t”.")
                    host.refresh()
                }
            }
            if (risky.isNotEmpty()) {
                host.confirm(
                    "Replace a common word?",
                    "“${risky.joinToString("”, “")}” is an everyday word. Every time you say it, FlowForge will write “$t” instead. Continue?",
                    "Add anyway",
                ) { save() }
            } else {
                save()
            }
        })

        col.addView(ui.heading("Your words"))
        val list = ui.column(spacingDp = 6)
        list.addView(ui.caption("Loading…"))
        col.addView(list, ui.matchWidth())
        graph.background({ graph.dictionary.list() }) { r ->
            list.removeAllViews()
            val entries = r.getOrDefault(emptyList())
            if (entries.isEmpty()) list.addView(ui.caption("No words yet."))
            entries.forEach { e ->
                list.addView(ui.card(LinearLayout(host).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    addView(ui.column().apply {
                        addView(ui.text(e.term, 16f, bold = true))
                        val sub = buildString {
                            append(e.category.name.lowercase())
                            if (e.spokenForms.isNotEmpty()) append(" · heard as: ").append(e.spokenForms.joinToString(", "))
                        }
                        addView(ui.caption(sub))
                    }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                    addView(ui.button("Delete", Ui.ButtonStyle.TEXT) {
                        graph.background({ graph.dictionary.delete(e.id) }) { host.refresh() }
                    }.apply { contentDescription = "Delete ${e.term}" })
                }))
            }
            if (entries.isNotEmpty()) {
                list.addView(ui.button("Delete all words", Ui.ButtonStyle.OUTLINED) {
                    host.confirm("Delete all words?", "This removes your whole personal dictionary from this phone.", "Delete") {
                        graph.background({ graph.dictionary.clear() }) { host.refresh() }
                    }
                })
            }
        }
        return col
    }
}

// =================================================================================================
// Privacy
// =================================================================================================

class PrivacyScreen(host: MainActivity) : Screen(host) {
    override val title = "Privacy"

    override fun build(ui: Ui): View {
        val s = graph.settings
        val p = s.privacy
        val d = s.dictation
        val col = ui.column(spacingDp = 8)

        col.addView(ui.heading("Where your data goes"))
        val engineLine = when (d.engine) {
            EngineKind.LOCAL_MODEL -> "Speech: transcribed on this phone by FlowForge's offline model. Audio never leaves the phone; the internet is only used once, to download the model when you ask."
            EngineKind.ON_DEVICE -> "Speech: processed on this phone by Android's on-device recognizer (once its language pack is installed)."
            EngineKind.SYSTEM -> "Speech: handled by your phone's speech service (${d.engine.label}). Depending on that service, audio may be sent to its provider (for example Google or Samsung)."
            EngineKind.CLOUD -> "Speech: uploaded to ${s.cloudConfig()?.host ?: "your configured provider"} for transcription, only while you dictate."
        }
        col.addView(ui.card(ui.column(spacingDp = 6).apply {
            addView(ui.body(engineLine))
            addView(ui.body("Cleanup: runs on this phone." + if (d.aiCleanupEnabled) " AI cleanup is on: cleaned text is also sent to ${s.cloudConfig()?.host ?: "your provider"}." else ""))
            addView(ui.body("Recording: only between your start and stop taps. Android shows its green microphone indicator and FlowForge shows a notification while recording."))
            addView(ui.body("Audio: kept in memory only and discarded after recognition; never written to storage."))
            addView(ui.body("Other apps: FlowForge reads only the focused text field, only when inserting, and never password fields. It stores nothing from other apps."))
            addView(ui.body("No analytics, advertising or tracking libraries are included. FlowForge has no server."))
        }))

        col.addView(ui.heading("History"))
        col.addView(ui.switchRow("Keep dictation history", "Stored only on this phone, encrypted with a key that never leaves it.", p.historyEnabled) { v ->
            s.updatePrivacy { it.copy(historyEnabled = v) }
            if (!v) graph.background({ graph.history.clear() }) { host.toast("History turned off and cleared.") }
        })
        col.addView(ui.body("Delete history after"))
        col.addView(ui.radioGroup(RetentionPolicy.ALLOWED_DAYS, p.historyRetentionDays, { days -> (if (days == 1) "1 day" else "$days days") to null }) { days ->
            s.updatePrivacy { it.copy(historyRetentionDays = days) }
            graph.background({ graph.history.purgeExpired() }) {}
        })
        col.addView(ui.switchRow("Keep the original transcript", "Lets you restore exactly what was recognized. Off: only the cleaned text is kept.", p.keepOriginalTranscript) { v -> s.updatePrivacy { it.copy(keepOriginalTranscript = v) } })
        col.addView(ui.switchRow("Respect incognito fields", "Never save history from fields that ask keyboards not to learn (private browsing, etc.).", p.respectIncognitoFields) { v -> s.updatePrivacy { it.copy(respectIncognitoFields = v) } })

        col.addView(ui.heading("Cloud permissions"))
        val recognitionOk = ConsentPolicy.isValid(p.recognitionConsent, CloudPurpose.RECOGNITION, s.cloudBaseUrl)
        val cleanupOk = ConsentPolicy.isValid(p.cleanupConsent, CloudPurpose.AI_CLEANUP, s.cloudBaseUrl)
        col.addView(ui.body("Cloud transcription: " + if (recognitionOk) "allowed for ${p.recognitionConsent?.endpointHost}" else "not allowed"))
        col.addView(ui.body("AI cleanup: " + if (cleanupOk) "allowed for ${p.cleanupConsent?.endpointHost}" else "not allowed"))
        if (recognitionOk || cleanupOk) {
            col.addView(ui.button("Withdraw all cloud permissions", Ui.ButtonStyle.OUTLINED) {
                s.updatePrivacy { it.copy(recognitionConsent = null, cleanupConsent = null) }
                s.updateDictation { it.copy(aiCleanupEnabled = false, engine = if (it.engine == EngineKind.CLOUD) EngineKind.LOCAL_MODEL else it.engine) }
                host.refresh()
            })
        }

        col.addView(ui.heading("Delete data"))
        col.addView(ui.button("Delete history now", Ui.ButtonStyle.TONAL) {
            graph.background({ graph.history.clear() }) { host.toast("History deleted.") }
        })
        col.addView(ui.button("Delete all FlowForge data", Ui.ButtonStyle.OUTLINED) {
            host.confirm(
                "Delete everything?",
                "Removes history, your dictionary, all settings, the saved API key and cloud permissions, and destroys the encryption key. This can't be undone.",
                "Delete everything",
            ) {
                DictationService.hide(host)
                graph.background({
                    graph.history.clear()
                    graph.dictionary.clear()
                    graph.settings.clearAll()
                    graph.secureBox.destroyKey()
                }) {
                    host.toast("All FlowForge data deleted.")
                    host.navigate(MainActivity.Dest.PERMISSIONS)
                }
            }
        })
        return col
    }
}

// =================================================================================================
// History
// =================================================================================================

class HistoryScreen(host: MainActivity) : Screen(host) {
    override val title = "History"

    override fun build(ui: Ui): View {
        val col = ui.column(spacingDp = 8)
        val p = graph.settings.privacy
        col.addView(ui.caption(if (p.historyEnabled) "Kept on this phone for ${p.historyRetentionDays} days, encrypted. Change this in Privacy." else "History is off. Turn it on in Privacy."))
        val list = ui.column(spacingDp = 8)
        list.addView(ui.caption("Loading…"))
        col.addView(list, ui.matchWidth())
        graph.background({ graph.history.list() }) { r ->
            list.removeAllViews()
            val entries = r.getOrDefault(emptyList())
            if (entries.isEmpty()) list.addView(ui.caption("No dictations yet."))
            entries.forEach { e ->
                val when_ = DateUtils.getRelativeTimeSpanString(e.createdAtMillis, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
                val showOriginal = e.original.isNotBlank() && e.original.trim() != e.cleaned
                list.addView(ui.card(ui.column(spacingDp = 6).apply {
                    addView(ui.caption("$when_ · ${e.mode.label} · ${e.engine.ifBlank { "engine n/a" }} · ${outcomeLabel(e.outcome)}"))
                    addView(ui.body(e.cleaned).apply { setTextIsSelectable(true) })
                    if (showOriginal) addView(ui.caption("Original: ${e.original.trim()}").apply { setTextIsSelectable(true) })
                    addView(ui.row(6).apply {
                        addView(ui.button("Copy", Ui.ButtonStyle.TONAL) { copy(e.cleaned) })
                        if (showOriginal) addView(ui.button("Copy original", Ui.ButtonStyle.TEXT) { copy(e.original.trim()) })
                        addView(ui.button("Delete", Ui.ButtonStyle.TEXT) {
                            graph.background({ graph.history.delete(e.id) }) { host.refresh() }
                        })
                    })
                }))
            }
            if (entries.isNotEmpty()) {
                list.addView(ui.button("Clear all history", Ui.ButtonStyle.OUTLINED) {
                    host.confirm("Clear history?", "All saved dictations will be deleted from this phone.", "Clear") {
                        graph.background({ graph.history.clear() }) { host.refresh() }
                    }
                })
            }
        }
        return col
    }

    private fun outcomeLabel(outcome: String) = when {
        outcome.startsWith("inserted") -> "inserted"
        outcome.startsWith("refused") -> "not inserted (protected field)"
        outcome == "preview" || outcome == "confirm" -> "shown for copy"
        else -> outcome
    }

    private fun copy(text: String) {
        host.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("FlowForge dictation", text))
        host.toast("Copied.")
    }
}
