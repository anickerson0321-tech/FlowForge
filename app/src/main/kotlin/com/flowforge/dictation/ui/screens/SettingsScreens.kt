package com.flowforge.dictation.ui.screens

import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.TextView
import com.flowforge.core.cleanup.CleanupMode
import com.flowforge.core.cleanup.CleanupOptions
import com.flowforge.core.cleanup.TextCleaner
import com.flowforge.core.dictionary.PersonalDictionary
import com.flowforge.core.privacy.CloudPurpose
import com.flowforge.core.privacy.ConsentPolicy
import com.flowforge.core.recognition.EngineKind
import com.flowforge.core.recognition.exposure
import com.flowforge.core.settings.DictationSettings
import com.flowforge.core.settings.InsertionPreference
import com.flowforge.dictation.speech.EngineAvailability
import com.flowforge.dictation.ui.MainActivity
import com.flowforge.dictation.ui.kit.Ui

// =================================================================================================
// Dictation settings
// =================================================================================================

class DictationSettingsScreen(host: MainActivity) : Screen(host) {
    override val title = "Dictation"

    override fun build(ui: Ui): View {
        val s = graph.settings
        val d = s.dictation
        fun update(change: (DictationSettings) -> DictationSettings) = s.updateDictation(change)
        return ui.column(spacingDp = 6).apply {
            addView(ui.heading("Recording"))
            addView(ui.switchRow("Keep listening through pauses", "Dictate long passages; tap the bubble or mic when finished. Stops by itself after 20 seconds of silence.", d.continuous) { v -> update { it.copy(continuous = v) } })
            addView(ui.body("Maximum recording length"))
            addView(ui.radioGroup(DictationSettings.MAX_RECORDING_CHOICES, d.maxRecordingSeconds, { secs -> "${secs / 60} minute" + (if (secs >= 120) "s" else "") to null }) { v -> update { it.copy(maxRecordingSeconds = v) } })
            addView(ui.switchRow("Haptic feedback", "A short vibration when recording starts and stops.", d.haptics) { v -> update { it.copy(haptics = v) } })

            addView(ui.heading("Text"))
            addView(ui.switchRow("Spoken punctuation", "Say “comma”, “period”, “question mark”, “new line”, “new paragraph”.", d.spokenPunctuation) { v -> update { it.copy(spokenPunctuation = v) } })
            addView(ui.switchRow("Finish sentences", "Add a final period or question mark when you don't say one (Light and Professional).", d.autoEndPunctuation) { v -> update { it.copy(autoEndPunctuation = v) } })
            addView(ui.switchRow("Smart spacing", "Add a space between your existing text and the dictation when needed.", d.smartSpacing) { v -> update { it.copy(smartSpacing = v) } })

            addView(ui.heading("Insertion (bubble mode)"))
            addView(ui.radioGroup(InsertionPreference.values().toList(), d.insertion, { it.label to it.summary }) { v -> update { it.copy(insertion = v) } })
            addView(ui.switchRow("Clear clipboard after pasting", "When FlowForge pastes, it removes the dictation from the clipboard afterwards (best effort).", s.clearClipboardAfterPaste) { v -> s.clearClipboardAfterPaste = v })

            addView(ui.heading("Appearance"))
            addView(ui.switchRow("Match system colors", "Use your wallpaper colors (Material You) instead of FlowForge colors.", s.useDynamicColor) { v ->
                s.useDynamicColor = v
                host.refresh()
            })

            addView(ui.heading("Apps where the bubble never types"))
            addView(ui.caption("FlowForge never types into password fields anywhere. You can also turn off typing for whole apps, such as banking apps."))
            addView(blockedApps(ui))
        }
    }

    private fun blockedApps(ui: Ui): View {
        val pm = host.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .filter { it.first != host.packageName }
            .distinctBy { it.first }
            .sortedBy { it.second.lowercase() }
        val blocked = graph.settings.blockedPackages
        return ui.column().apply {
            if (apps.isEmpty()) addView(ui.caption("No apps found."))
            apps.forEach { (pkg, label) ->
                addView(ui.switchRow(label, null, pkg in blocked) { v ->
                    val current = graph.settings.blockedPackages.toMutableSet()
                    if (v) current += pkg else current -= pkg
                    graph.settings.blockedPackages = current
                })
            }
        }
    }
}

// =================================================================================================
// Speech engine
// =================================================================================================

class EngineScreen(host: MainActivity) : Screen(host) {
    override val title = "Speech engine"

    override fun build(ui: Ui): View {
        val s = graph.settings
        val d = s.dictation
        val onDevice = EngineAvailability.onDeviceAvailable(host)
        val system = EngineAvailability.systemAvailable(host)
        val col = ui.column(spacingDp = 8)

        col.addView(ui.heading("Engine"))
        col.addView(ui.radioGroup(EngineKind.values().toList(), d.engine, { k ->
            val avail = when (k) {
                EngineKind.ON_DEVICE -> if (onDevice) "Available on this phone." else "Not available on this phone."
                EngineKind.SYSTEM -> if (system) "Available." else "No speech service installed."
                EngineKind.CLOUD -> if (s.cloudConfig() != null) "Configured for ${s.cloudConfig()?.host}." else "Not set up (see below)."
            }
            k.label to "${k.summary}\n$avail"
        }) { k ->
            s.updateDictation { it.copy(engine = k) }
            host.refresh()
        })
        col.addView(ui.switchRow("Fall back when unavailable", "If the chosen engine can't run, use another local engine. FlowForge never falls back to the cloud on its own.", d.allowEngineFallback) { v ->
            s.updateDictation { it.copy(allowEngineFallback = v) }
        })
        col.addView(ui.banner("Where your audio goes: ${d.engine.exposure().description}", Ui.Tone.INFO))

        // Language & offline packs
        col.addView(ui.heading("Language"))
        val lang = ui.input("Language tag, e.g. en-US (empty = phone language)", d.languageTag)
        col.addView(lang, ui.matchWidth())
        val langStatus = ui.caption("Offline language pack: tap Check.")
        col.addView(ui.row(8).apply {
            addView(ui.button("Save language", Ui.ButtonStyle.TONAL) {
                s.updateDictation { it.copy(languageTag = lang.text.toString().trim()) }
                host.toast("Language saved.")
            })
            addView(ui.button("Check", Ui.ButtonStyle.OUTLINED) {
                langStatus.text = "Checking…"
                val tag = lang.text.toString().trim()
                EngineAvailability.checkOnDeviceLanguages(host, tag) { support ->
                    langStatus.text = "Offline language pack for ${EngineAvailability.effectiveLanguage(tag)}: ${support.status(EngineAvailability.effectiveLanguage(tag))}" +
                        if (support.installed.isNotEmpty()) "\nInstalled: ${support.installed.joinToString(", ")}" else ""
                }
            })
        })
        col.addView(langStatus)
        col.addView(ui.button("Download language pack", Ui.ButtonStyle.TEXT) {
            val ok = EngineAvailability.downloadOnDeviceLanguage(host, lang.text.toString().trim())
            host.toast(if (ok) "Download requested. Android shows progress in its own notification." else "This phone's on-device recognizer can't download languages.")
        })
        col.addView(ui.caption("Offline honesty: on-device recognition works without internet only after its language pack is installed. FlowForge reports what Android tells it; it has not tested your phone offline. You can verify by turning on airplane mode and using “Try it here” on the Home screen."))
        col.addView(ui.button("Android voice input settings", Ui.ButtonStyle.TEXT) { host.open(com.flowforge.dictation.ui.Setup.voiceInputSettings()) })

        // Cloud
        col.addView(ui.heading("Cloud provider (optional, your own key)"))
        col.addView(ui.caption("Works with any OpenAI-compatible /audio/transcriptions endpoint (for example OpenAI, Groq, or a self-hosted Whisper server). You pay your provider directly; FlowForge includes no keys and no subscription."))
        val url = ui.input("https://api.openai.com/v1", s.cloudBaseUrl)
        val key = ui.input(if (s.hasCloudApiKey) "API key saved (encrypted) — type to replace" else "API key", password = true)
        val sttModel = ui.input("Transcription model", s.cloudTranscriptionModel)
        val chatModel = ui.input("Chat model for AI cleanup", s.cloudChatModel)
        listOf(url, key, sttModel, chatModel).forEach { col.addView(it, ui.matchWidth()) }
        col.addView(ui.row(8).apply {
            addView(ui.button("Save provider") {
                val u = url.text.toString().trim()
                if (u.isNotEmpty() && ConsentPolicy.hostOf(u) == null) {
                    host.toast("Use an https:// address (plain http is only allowed for a server on this phone).")
                    return@button
                }
                s.cloudBaseUrl = u
                if (key.text.isNotBlank()) s.setCloudApiKey(key.text.toString())
                s.cloudTranscriptionModel = sttModel.text.toString().ifBlank { "whisper-1" }
                s.cloudChatModel = chatModel.text.toString().ifBlank { "gpt-4o-mini" }
                host.toast("Provider saved. The key is encrypted with this phone's keystore.")
                host.refresh()
            })
            if (s.hasCloudApiKey) {
                addView(ui.button("Remove key", Ui.ButtonStyle.OUTLINED) {
                    s.setCloudApiKey(null)
                    host.refresh()
                })
            }
        })
        val consent = s.privacy.recognitionConsent
        val host0 = ConsentPolicy.hostOf(s.cloudBaseUrl)
        val consentValid = ConsentPolicy.isValid(consent, CloudPurpose.RECOGNITION, s.cloudBaseUrl)
        col.addView(ui.body(if (consentValid) "Cloud transcription allowed for $host0." else "Cloud transcription not allowed yet."))
        if (host0 != null) {
            col.addView(
                if (consentValid) {
                    ui.button("Withdraw permission", Ui.ButtonStyle.OUTLINED) {
                        s.updatePrivacy { it.copy(recognitionConsent = null) }
                        host.refresh()
                    }
                } else {
                    ui.button("Review & allow cloud transcription", Ui.ButtonStyle.TONAL) { askConsent(CloudPurpose.RECOGNITION, s.cloudBaseUrl) }
                },
            )
        }
        return col
    }

    private fun askConsent(purpose: CloudPurpose, url: String) {
        val h = ConsentPolicy.hostOf(url) ?: return
        AlertDialog.Builder(host)
            .setTitle("Send audio to $h?")
            .setMessage(ConsentPolicy.noticeText(purpose, h))
            .setPositiveButton("Allow") { _, _ ->
                val record = ConsentPolicy.grant(purpose, url, System.currentTimeMillis())
                graph.settings.updatePrivacy { it.copy(recognitionConsent = record) }
                host.refresh()
            }
            .setNegativeButton("Don't allow", null)
            .show()
    }
}

// =================================================================================================
// Cleanup modes
// =================================================================================================

class CleanupScreen(host: MainActivity) : Screen(host) {
    override val title = "Cleanup modes"

    override fun build(ui: Ui): View {
        val s = graph.settings
        val d = s.dictation
        val col = ui.column(spacingDp = 8)
        col.addView(ui.heading("Mode"))
        col.addView(ui.radioGroup(CleanupMode.values().toList(), d.cleanupMode, { it.label to it.summary }) { m ->
            s.updateDictation { it.copy(cleanupMode = m) }
        })
        col.addView(ui.switchRow("Format spoken lists", "In Professional mode, “first … second … third …” becomes a numbered list.", d.formatLists) { v -> s.updateDictation { it.copy(formatLists = v) } })
        col.addView(ui.banner("Safety: cleanup never changes numbers, dates, names or negations on its own. If a rule would, it is skipped. The original transcript is always kept so you can restore it.", Ui.Tone.INFO))

        // AI cleanup (optional)
        col.addView(ui.heading("AI cleanup (optional)"))
        val cfg = s.cloudConfig()
        val consentValid = cfg != null && ConsentPolicy.isValid(s.privacy.cleanupConsent, CloudPurpose.AI_CLEANUP, cfg.baseUrl)
        col.addView(ui.caption("Sends the already-cleaned text to your cloud provider's chat model for grammar polishing. The reply is checked on your phone and discarded if it changes numbers, dates, names or negations. Off by default."))
        col.addView(ui.switchRow("Use AI cleanup", when {
            cfg == null -> "Set up a cloud provider in Speech engine first."
            !consentValid -> "Needs your permission to send text to ${cfg.host}."
            else -> "Uses ${cfg.chatModel} at ${cfg.host}."
        }, d.aiCleanupEnabled) { v ->
            if (v && cfg != null && !consentValid) {
                AlertDialog.Builder(host)
                    .setTitle("Send text to ${cfg.host}?")
                    .setMessage(ConsentPolicy.noticeText(CloudPurpose.AI_CLEANUP, cfg.host ?: ""))
                    .setPositiveButton("Allow") { _, _ ->
                        val record = ConsentPolicy.grant(CloudPurpose.AI_CLEANUP, cfg.baseUrl, System.currentTimeMillis())
                        s.updatePrivacy { it.copy(cleanupConsent = record) }
                        s.updateDictation { it.copy(aiCleanupEnabled = true) }
                        host.refresh()
                    }
                    .setNegativeButton("Don't allow") { _, _ -> host.refresh() }
                    .show()
            } else {
                s.updateDictation { it.copy(aiCleanupEnabled = v && cfg != null) }
                if (v && cfg == null) host.refresh()
            }
        })

        // Live comparison
        col.addView(ui.heading("Compare the modes"))
        col.addView(ui.caption("Type or paste what a recognizer might produce:"))
        val input = ui.input("Dictated text", SAMPLE, multiLine = true).apply { minLines = 2 }
        col.addView(input, ui.matchWidth())
        val outputs = CleanupMode.values().associateWith { ui.body("") }
        CleanupMode.values().forEach { m ->
            col.addView(ui.card(ui.column(spacingDp = 4).apply {
                addView(ui.text(m.label, 14f, ui.palette.accent, bold = true))
                addView(outputs.getValue(m))
            }))
        }
        var dictionary = PersonalDictionary.EMPTY
        fun recompute() {
            val text = input.text.toString()
            val cleaner = TextCleaner(dictionary)
            outputs.forEach { (m, view: TextView) ->
                view.text = cleaner.clean(text, CleanupOptions(m, d.spokenPunctuation, d.autoEndPunctuation, d.formatLists)).text.ifEmpty { "—" }
            }
        }
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = recompute()
        })
        recompute()
        // Load the full dictionary (with spoken forms) off the main thread, then refresh the preview.
        graph.background({ graph.dictionary.current() }) { r ->
            r.onSuccess {
                dictionary = it
                recompute()
            }
        }
        return col
    }

    companion object {
        const val SAMPLE = "Hey Jason um I checked the bathrooms and actually we should do it Friday not Thursday. Actually make that Saturday"
    }
}
