package com.flowforge.dictation.ui.screens

import android.app.AlertDialog
import android.os.Build
import android.view.View
import com.flowforge.dictation.BuildConfig
import com.flowforge.dictation.R
import com.flowforge.dictation.overlay.DictationService
import com.flowforge.dictation.speech.EngineAvailability
import com.flowforge.dictation.speech.EngineMetrics
import com.flowforge.dictation.speech.LocalAsr
import com.flowforge.core.recognition.LocalModelStatus
import com.flowforge.dictation.ui.MainActivity
import com.flowforge.dictation.ui.Setup
import com.flowforge.dictation.ui.kit.Ui

// =================================================================================================
// Setup & permissions (also the first-run onboarding)
// =================================================================================================

class PermissionsScreen(host: MainActivity) : Screen(host) {
    override val title = "Setup & permissions"
    override val refreshOnResume = true

    override fun build(ui: Ui): View {
        val col = ui.column(spacingDp = 10)
        val firstRun = !graph.settings.onboardingDone
        if (firstRun) {
            col.addView(ui.text("Welcome to FlowForge", 26f, bold = true).apply { isAccessibilityHeading = true })
            col.addView(ui.body("Keep your normal keyboard. A small floating mic sits on top of your apps: hold it and talk, let go, and clean text appears where you're typing. A few permissions are needed; each one is explained."))
        }

        fun step(number: Int, title: String, done: Boolean, explanation: String, actionLabel: String?, action: (() -> Unit)?) {
            col.addView(ui.card(ui.column(spacingDp = 6).apply {
                addView(ui.text((if (done) "✓ " else "$number. ") + title, 17f, if (done) ui.palette.success else ui.palette.onSurface, bold = true))
                addView(ui.caption(explanation))
                if (!done && actionLabel != null && action != null) addView(ui.button(actionLabel, Ui.ButtonStyle.TONAL, action))
            }))
        }

        val mic = Setup.hasMic(host)
        step(1, "Microphone", mic, "Required. Used only while you are dictating; Android shows a green indicator whenever it is in use.", "Allow microphone") { host.requestMic() }
        step(2, "Notifications", Setup.hasNotifications(host), "Recommended. Shows when the bubble is active and when it is recording, with a Stop button.", "Allow notifications") { host.requestNotifications() }

        step(3, "Show the bubble over other apps", Setup.canOverlay(host),
            "Lets the floating microphone appear on top of whatever app you're typing in. You keep your normal keyboard.",
            "Allow display over apps") { host.open(Setup.overlaySettings(host)) }

        val a11y = Setup.accessibilityEnabled(host)
        step(4, "Let the bubble type for you", a11y,
            "Turns on “FlowForge text insertion”, which puts your words into the text field you're in. Without it, FlowForge shows the text for you to copy and paste.",
            null, null)
        if (!a11y) col.addView(restrictedSettingsHelp(ui))

        // Speech engine
        val local = LocalAsr.status(host, graph.settings)
        val onDevice = EngineAvailability.onDeviceAvailable(host)
        val system = EngineAvailability.systemAvailable(host)
        step(5, "Download the offline speech model", local == LocalModelStatus.READY,
            when (local) {
                LocalModelStatus.READY -> "Installed."
                LocalModelStatus.NOT_DOWNLOADED -> "Recommended. A one-time download (250–480 MB, Wi-Fi recommended) that gives the most accurate results and works with no internet. " +
                    if (onDevice || system) "Until then, Android's speech recognizer is used." else "No other speech recognizer was found on this phone, so this is needed."
                LocalModelStatus.LANGUAGE_UNSUPPORTED -> "The offline models are English-only, and your dictation language isn't English. Android's recognizer is used instead."
                LocalModelStatus.DEVICE_UNSUPPORTED -> "This phone can't run the offline model; Android's recognizer is used instead."
            },
            if (local == LocalModelStatus.NOT_DOWNLOADED) "Choose a model" else null) { host.navigate(MainActivity.Dest.ENGINE) }

        col.addView(ui.button(if (firstRun) "Start using FlowForge" else "Done", Ui.ButtonStyle.FILLED) {
            graph.settings.onboardingDone = true
            host.navigate(MainActivity.Dest.HOME)
        })
        return col
    }

    /**
     * Android 13+ blocks accessibility services of apps installed from a file ("Restricted
     * setting") until the user unlocks them in App info. The unlock item only appears after one
     * attempt to turn the service on, so the steps must be done in this order.
     */
    private fun restrictedSettingsHelp(ui: Ui): View = ui.card(ui.column(spacingDp = 8).apply {
        addView(ui.text("How to turn it on", 16f, bold = true))
        addView(ui.caption("Because FlowForge was installed from a file rather than the Play Store, Android blocks this switch at first and shows “Restricted setting”. That's a normal Android safety check; it's a one-time unlock."))
        addView(ui.body("A. Tap “Open Accessibility”. Go to Installed apps (Samsung) or Downloaded apps, tap “FlowForge text insertion” and try to turn it on. If Android says “Restricted setting”, tap OK. This first try is required: it makes the unlock option appear."))
        addView(ui.button("Open Accessibility", Ui.ButtonStyle.TONAL) { showAccessibilityDisclosure() })
        addView(ui.body("B. Tap “Open App info”. Tap the ⋮ menu in the top-right corner, then “Allow restricted settings”, and confirm with your fingerprint or PIN."))
        addView(ui.button("Open App info", Ui.ButtonStyle.TONAL) { host.open(Setup.appDetails(host)) })
        addView(ui.body("C. Open Accessibility again and turn on “FlowForge text insertion”. Android will ask you to confirm; tap Allow."))
        addView(ui.caption("Don't see “Allow restricted settings” in the ⋮ menu? Do step A first, then reopen App info. If there is no ⋮ menu at all, your phone isn't restricting FlowForge; just turn the switch on in step C."))
    })

    /** Prominent disclosure required before enabling a non-accessibility-tool AccessibilityService. */
    private fun showAccessibilityDisclosure() {
        AlertDialog.Builder(host)
            .setTitle("Text insertion uses an accessibility service")
            .setMessage(host.getString(R.string.accessibility_description))
            .setPositiveButton("Continue") { _, _ ->
                graph.settings.accessibilityDisclosureAccepted = true
                host.open(Setup.accessibilitySettings())
            }
            .setNegativeButton("Not now", null)
            .show()
    }
}

// =================================================================================================
// Help & troubleshooting
// =================================================================================================

class HelpScreen(host: MainActivity) : Screen(host) {
    override val title = "Help"

    override fun build(ui: Ui): View {
        val col = ui.column(spacingDp = 8)
        fun section(title: String, vararg lines: String) {
            col.addView(ui.heading(title))
            col.addView(ui.card(ui.column(spacingDp = 6).apply { lines.forEach { addView(ui.body(it)) } }))
        }
        section(
            "Dictating",
            "Hold to talk: press and hold the bubble, speak, let go. Your words are typed at the cursor (or replace your selection). Slide your finger far away before letting go to cancel.",
            "Tap to talk: tap the bubble once, speak as long as you like, tap again to finish.",
            "Move the bubble by dragging it; drag it onto ✕ to hide it. After each insertion, the panel offers Undo.",
            "Want to check the text before it goes in? Dictation settings → Insertion → “Preview first”.",
            "Test anything on the Home screen with “Try it here”.",
        )
        section(
            "Voice commands",
            "“comma”, “period”, “question mark”, “exclamation point”, “colon”, “semicolon” — punctuation (say “literal comma” to write the word).",
            "“new line”, “new paragraph” — line breaks.",
            "“scratch that” — removes the sentence you just said; said on its own, it undoes your last dictation.",
            "“make that Friday”, “… Thursday, I mean Friday”, “two, no, three” — corrects the last item of the same kind (day, date, number, time, name).",
            "Professional mode: “first … second … third …” becomes a numbered list.",
        )
        section(
            "Troubleshooting",
            "Bubble doesn't appear: allow “Display over other apps”, then start it from Home or the Quick Settings tile (add the “FlowForge bubble” tile by editing Quick Settings).",
            "Text isn't inserted: turn on “FlowForge text insertion” (Setup & permissions, step 4). Password and payment fields are always skipped. Some apps block automated typing; use Copy.",
            "“Restricted setting” when turning on text insertion: first try to turn it on once and tap OK, then Settings → Apps → FlowForge → ⋮ (top right) → Allow restricted settings, then turn it on again.",
            "Words missing or recognition cutting in and out: download the offline speech model (Speech engine). It records continuously instead of restarting between sentences.",
            "Recording stops on its own: tap-to-talk stops at the maximum length (Dictation settings).",
            "Bubble disappears after a while: Samsung may close background apps. Settings → Apps → FlowForge → Battery → Unrestricted.",
            "Works offline? Yes with the FlowForge offline model once it's downloaded. Android's on-device engine also works offline if its language pack is installed (Speech engine → Check).",
        )

        col.addView(ui.heading("Diagnostics (measured on this phone)"))
        val diag = buildString {
            appendLine("FlowForge ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}), built with ${BuildConfig.TOOLCHAIN}")
            appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Window width: ${host.resources.configuration.screenWidthDp} dp (${if (host.isWide) "two-pane" else "single-pane"})")
            appendLine("Offline model: ${LocalAsr.status(host, graph.settings)} (${LocalAsr.selectedModel(graph.settings).id}) · last load: ${ms(LocalAsr.lastLoadMillis)}${LocalAsr.lastError?.let { " · error: $it" } ?: ""}")
            appendLine("On-device recognizer: ${EngineAvailability.onDeviceAvailable(host)} · System recognizer: ${EngineAvailability.systemAvailable(host)}")
            appendLine("Network: ${EngineAvailability.networkAvailable(host)}")
            appendLine("Bubble running: ${DictationService.isRunning} · Text insertion on: ${Setup.accessibilityEnabled(host)}")
            appendLine("Dictation sessions this run: ${EngineMetrics.sessions} · last engine: ${EngineMetrics.lastEngine.ifBlank { "—" }}")
            appendLine("Last mic start latency: ${ms(EngineMetrics.lastStartLatencyMs)} · last stop→result: ${ms(EngineMetrics.lastFinalLatencyMs)}")
        }
        col.addView(ui.card(ui.text(diag, 13f, ui.palette.onSurfaceVariant).apply { setTextIsSelectable(true) }))

        section(
            "About",
            "FlowForge is an independent dictation app. It contains no analytics, advertising or tracking code and has no server of its own.",
            "Open-source components: Kotlin standard library (Apache License 2.0); sherpa-onnx speech recognition (Apache License 2.0) with ONNX Runtime (MIT License); jbzip2 decompressor (MIT License).",
            "Offline speech models (downloaded only when you choose): NVIDIA Parakeet TDT 0.6B v2 (CC-BY-4.0, © NVIDIA); Moonshine Base by Useful Sensors (MIT License). Converted for sherpa-onnx by the k2-fsa project.",
        )
        return col
    }

    private fun ms(v: Long) = if (v < 0) "not measured yet" else "$v ms"
}
