package com.flowforge.dictation.ui.screens

import android.app.AlertDialog
import android.os.Build
import android.view.View
import android.view.inputmethod.InputMethodManager
import com.flowforge.dictation.BuildConfig
import com.flowforge.dictation.R
import com.flowforge.dictation.overlay.DictationService
import com.flowforge.dictation.speech.EngineAvailability
import com.flowforge.dictation.speech.EngineMetrics
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
            col.addView(ui.body("Dictate anywhere and get clean, correctly punctuated text. A few permissions are needed; each one is explained, and you can skip the ones for features you don't use."))
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

        col.addView(ui.heading("Choose how text gets into other apps"))
        col.addView(ui.caption("Option A is simplest and most reliable. Option B lets you keep your usual keyboard. You can enable both."))

        val kbEnabled = Setup.keyboardEnabled(host)
        step(3, "Option A — Voice keyboard", kbEnabled && Setup.keyboardSelected(host),
            "Enable “FlowForge Voice Keyboard”, then switch to it in any text field (🌐 switches back). Android may show a standard warning that keyboards can see what you type; FlowForge only types what you dictate and stores nothing else.",
            if (kbEnabled) "Switch to FlowForge keyboard" else "Enable keyboard",
        ) {
            if (kbEnabled) host.getSystemService(InputMethodManager::class.java).showInputMethodPicker() else host.open(Setup.keyboardSettings())
        }

        step(4, "Option B — Bubble: display over other apps", Setup.canOverlay(host),
            "Lets the floating microphone appear over other apps.", "Allow display over apps") { host.open(Setup.overlaySettings(host)) }
        step(5, "Option B — Bubble: text insertion", Setup.accessibilityEnabled(host),
            "Lets the bubble type your dictation into the focused text field. Without it, results are shown for you to copy.",
            "Turn on text insertion") { showAccessibilityDisclosure() }

        // Engine check
        val onDevice = EngineAvailability.onDeviceAvailable(host)
        val system = EngineAvailability.systemAvailable(host)
        step(6, "Speech recognition", onDevice || system,
            when {
                onDevice -> "On-device recognition is available. Check the offline language pack in Speech engine."
                system -> "A speech service is available. On-device recognition isn't, so audio may be processed by that service's provider."
                else -> "No speech recognition service found. Install “Speech Recognition & Synthesis from Google” (or your phone maker's) from the app store, or set up a cloud provider."
            },
            "Speech engine settings") { host.navigate(MainActivity.Dest.ENGINE) }

        col.addView(ui.button(if (firstRun) "Start using FlowForge" else "Done", Ui.ButtonStyle.FILLED) {
            graph.settings.onboardingDone = true
            host.navigate(MainActivity.Dest.HOME)
        })
        return col
    }

    /** Prominent disclosure required before enabling a non-accessibility-tool AccessibilityService. */
    private fun showAccessibilityDisclosure() {
        AlertDialog.Builder(host)
            .setTitle("Text insertion uses an accessibility service")
            .setMessage(host.getString(R.string.accessibility_description) +
                "\n\nAfter tapping Continue, choose “FlowForge text insertion” and turn it on." +
                "\n\nIf Android says the setting is restricted (common for apps installed from a file), open App info → ⋮ menu → “Allow restricted settings”, then try again.")
            .setPositiveButton("Continue") { _, _ ->
                graph.settings.accessibilityDisclosureAccepted = true
                host.open(Setup.accessibilitySettings())
            }
            .setNeutralButton("App info") { _, _ -> host.open(Setup.appDetails(host)) }
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
            "Keyboard: switch to the FlowForge keyboard, tap the mic, speak, tap again. The text is inserted at your cursor (or replaces your selection).",
            "Bubble: tap to start, tap to finish. Long-press for Undo, cleanup mode and Hide. Drag to move it; drag onto ✕ to hide.",
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
            "Text isn't inserted: turn on “FlowForge text insertion” in Accessibility. Password and payment fields are always skipped. Some apps block automated typing; use Copy, or the voice keyboard.",
            "“Restricted setting” when enabling text insertion: App info → ⋮ → Allow restricted settings (Android 13+ requirement for apps installed from a file).",
            "Recording stops on its own: FlowForge stops after 20 s of silence or at the maximum length (Dictation settings).",
            "Bubble disappears after a while: Samsung may close background apps. Settings → Apps → FlowForge → Battery → Unrestricted.",
            "Works offline? Only with the on-device engine and an installed language pack (Speech engine → Check).",
        )

        col.addView(ui.heading("Diagnostics (measured on this phone)"))
        val diag = buildString {
            appendLine("FlowForge ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}), built with ${BuildConfig.TOOLCHAIN}")
            appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Window width: ${host.resources.configuration.screenWidthDp} dp (${if (host.isWide) "two-pane" else "single-pane"})")
            appendLine("On-device recognizer: ${EngineAvailability.onDeviceAvailable(host)} · System recognizer: ${EngineAvailability.systemAvailable(host)}")
            appendLine("Network: ${EngineAvailability.networkAvailable(host)}")
            appendLine("Bubble running: ${DictationService.isRunning} · Text insertion on: ${Setup.accessibilityEnabled(host)} · Keyboard enabled: ${Setup.keyboardEnabled(host)}")
            appendLine("Dictation sessions this run: ${EngineMetrics.sessions} · last engine: ${EngineMetrics.lastEngine.ifBlank { "—" }}")
            appendLine("Last mic start latency: ${ms(EngineMetrics.lastStartLatencyMs)} · last stop→result: ${ms(EngineMetrics.lastFinalLatencyMs)}")
        }
        col.addView(ui.card(ui.text(diag, 13f, ui.palette.onSurfaceVariant).apply { setTextIsSelectable(true) }))

        section(
            "About",
            "FlowForge is an independent dictation app. It contains no analytics, advertising or tracking code and has no server of its own.",
            "Open-source components: Kotlin standard library (Apache License 2.0).",
        )
        return col
    }

    private fun ms(v: Long) = if (v < 0) "not measured yet" else "$v ms"
}
