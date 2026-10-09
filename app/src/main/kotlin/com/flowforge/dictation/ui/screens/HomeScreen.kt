package com.flowforge.dictation.ui.screens

import android.view.View
import android.view.inputmethod.InputMethodManager
import com.flowforge.core.cleanup.CleanupMode
import com.flowforge.core.recognition.exposure
import com.flowforge.dictation.overlay.DictationService
import com.flowforge.dictation.ui.MainActivity
import com.flowforge.dictation.ui.Setup
import com.flowforge.dictation.ui.kit.Ui

class HomeScreen(host: MainActivity) : Screen(host) {
    override val title = "Home"
    private var tryIt: TryItBox? = null

    override fun build(ui: Ui): View {
        val settings = graph.settings
        val d = settings.dictation
        val col = ui.column(spacingDp = 14)

        col.addView(ui.text("FlowForge", 30f, bold = true).apply { isAccessibilityHeading = true })
        col.addView(ui.caption("Speak naturally. Clean text lands where you're typing."))

        // Setup status
        val mic = Setup.hasMic(host)
        val bubbleReady = mic && Setup.canOverlay(host)
        val insertion = Setup.accessibilityEnabled(host)
        val keyboard = Setup.keyboardEnabled(host)
        if (!mic || (!keyboard && !(bubbleReady && insertion))) {
            col.addView(
                ui.banner(
                    if (!mic) "FlowForge needs microphone access before you can dictate." else "Finish setup to dictate into other apps: enable the voice keyboard, or the bubble with text insertion.",
                    Ui.Tone.WARNING, "Finish setup",
                ) { host.navigate(MainActivity.Dest.PERMISSIONS) },
            )
        }
        if (settings.bubbleWanted && !DictationService.isRunning && bubbleReady) {
            col.addView(
                ui.banner("The bubble stopped (the phone may have closed FlowForge to save memory).", Ui.Tone.INFO, "Restore bubble") {
                    DictationService.show(host)?.let(host::toast)
                    host.refresh()
                },
            )
        }

        // Floating bubble
        col.addView(
            ui.card(
                ui.column(spacingDp = 8).apply {
                    addView(ui.text("Floating bubble", 18f, bold = true))
                    addView(ui.caption(
                        when {
                            !bubbleReady -> "A movable mic that works over any app. Needs microphone and display-over-apps permissions."
                            !insertion -> "Ready. Text insertion is off, so results are shown for you to copy."
                            else -> "Tap the bubble to talk, tap again to insert. Drag to move; drag to ✕ to hide."
                        },
                    ))
                    val running = DictationService.isRunning
                    addView(ui.button(if (running) "Hide bubble" else "Show bubble", if (running) Ui.ButtonStyle.OUTLINED else Ui.ButtonStyle.FILLED) {
                        if (running) {
                            DictationService.hide(host)
                            graph.settings.bubbleWanted = false
                            host.window.decorView.postDelayed({ host.refresh() }, 300)
                        } else if (!bubbleReady) {
                            host.navigate(MainActivity.Dest.PERMISSIONS)
                        } else {
                            DictationService.show(host)?.let(host::toast)
                            host.window.decorView.postDelayed({ host.refresh() }, 300)
                        }
                    })
                },
            ),
        )

        // Voice keyboard
        col.addView(
            ui.card(
                ui.column(spacingDp = 8).apply {
                    addView(ui.text("Voice keyboard", 18f, bold = true))
                    addView(ui.caption(
                        if (keyboard) {
                            "Most reliable way to dictate: switch to the FlowForge keyboard in any text field, tap the mic, then 🌐 to go back."
                        } else {
                            "Most reliable way to dictate. Enable “FlowForge Voice Keyboard” in Android's keyboard settings."
                        },
                    ))
                    addView(
                        if (keyboard) {
                            ui.button("Switch keyboard now", Ui.ButtonStyle.TONAL) {
                                host.getSystemService(InputMethodManager::class.java).showInputMethodPicker()
                            }
                        } else {
                            ui.button("Enable keyboard", Ui.ButtonStyle.TONAL) { host.open(Setup.keyboardSettings()) }
                        },
                    )
                },
            ),
        )

        // Cleanup mode quick switch
        col.addView(ui.heading("Cleanup mode"))
        col.addView(ui.row(8).apply {
            CleanupMode.values().forEach { m ->
                addView(ui.chip(m.label, m == d.cleanupMode) {
                    settings.updateDictation { it.copy(cleanupMode = m) }
                    host.refresh()
                })
            }
        })
        col.addView(ui.caption(d.cleanupMode.summary))

        // Try it
        col.addView(ui.heading("Test dictation"))
        val box = TryItBox(host, ui)
        tryIt = box
        col.addView(box.build(), ui.matchWidth())

        // Privacy summary
        col.addView(ui.heading("Privacy"))
        col.addView(ui.card(ui.column(spacingDp = 6).apply {
            addView(ui.body("Engine: ${d.engine.label}. ${d.engine.exposure().description}"))
            addView(ui.caption("History: " + if (graph.settings.privacy.historyEnabled) "kept on this phone, encrypted, for ${graph.settings.privacy.historyRetentionDays} days." else "off."))
            addView(ui.caption("No analytics, ads or tracking. AI cleanup: " + if (d.aiCleanupEnabled) "on (your provider)." else "off."))
        }))
        return col
    }

    override val refreshOnResume = true

    /** Leaving the screen must never leave the in-app test recording in the background. */
    override fun onPause() {
        tryIt?.cancelIfActive()
    }

    override fun onDestroy() {
        tryIt?.release()
        tryIt = null
    }
}
