package com.privatevoice.app.ui

import android.app.StatusBarManager
import android.content.ComponentName
import android.graphics.drawable.Icon
import android.os.Build
import android.os.SystemClock
import android.widget.LinearLayout
import com.privatevoice.app.R
import com.privatevoice.app.audio.PlaybackState
import com.privatevoice.app.model.EngineKind
import com.privatevoice.app.model.VoiceCategory
import com.privatevoice.app.notifications.SupportedApps
import com.privatevoice.app.reader.OutputKind
import com.privatevoice.app.reader.QuietHours
import com.privatevoice.app.reader.ReadResult
import com.privatevoice.app.reader.RoutePolicy
import com.privatevoice.app.settings.ReadContent
import com.privatevoice.app.settings.RouteRestriction
import com.privatevoice.app.speech.EngineState
import com.privatevoice.app.tile.ReadLatestTileService
import com.privatevoice.app.voices.KokoroVoices

object Screens {

    // ------------------------------------------------------------------ Dashboard
    fun dashboard(a: MainActivity, root: LinearLayout) = with(a.ui) {
        val g = a.graph
        val s = g.settings.current
        root.title("PrivateVoice").layoutParams = lp(top = 8)
        root.caption("Your messages, read aloud privately — on this phone, offline.").layoutParams = lp(bottom = 16)

        // Onboarding
        val needsAccess = !a.notificationAccessGranted()
        val needsPost = !a.postNotificationsGranted()
        if (needsAccess || needsPost) {
            root.card {
                background = rounded(Palette.PRIMARY_CONTAINER, 20)
                headline("Finish setup")
                if (needsAccess) {
                    body("PrivateVoice reads messages through Android's notification access. Message text stays in memory for a few minutes and is never saved or sent anywhere.").layoutParams = lp(top = 6, bottom = 8)
                    primaryButton("Allow notification access") { a.openNotificationAccess() }
                    caption("Toggle greyed out? Android restricts sideloaded apps: open App info → ⋮ menu → \"Allow restricted settings\", then try again.").layoutParams = lp(top = 4)
                    outlineButton("Open App info") { a.openAppInfo() }
                }
                if (needsPost) {
                    body("Allow notifications so PrivateVoice can show Read aloud / Stop controls (it never shows message text).").layoutParams = lp(top = 10, bottom = 6)
                    tonalButton("Allow notifications") { a.requestPostNotifications() }
                }
            }
        }

        // Read controls
        root.card {
            val pendingLabel = caption("")
            pendingLabel.layoutParams = lp(bottom = 8)
            val readBtn = primaryButton("Read latest message") {
                if (g.reader.readLatest() == ReadResult.NOTHING_PENDING) g.toast("No messages waiting")
            }
            val controls = row {
                outlineButton("■  Stop") { g.reader.stop() }
                val pause = outlineButton("❚❚  Pause") {
                    if (g.player.state.value is PlaybackState.Paused) g.reader.resume() else g.reader.pause()
                }
                outlineButton("↻  Repeat") { if (!g.reader.repeat()) g.toast("Nothing to repeat yet") }
                a.live += { pause.text = if (g.player.state.value is PlaybackState.Paused) "▶  Resume" else "❚❚  Pause" }
            }
            controls.layoutParams = lp(top = 4)
            addView(controls)
            a.live += {
                val p = g.repository.state.value
                pendingLabel.text = when {
                    p.count == 0 && p.total > 0 -> "No new messages · ${p.total} recent message(s) you can hear again below"
                    p.count == 0 -> "No messages waiting yet. New messages will appear here."
                    p.count == 1 -> "1 message waiting from ${p.latestSender}${p.latestAppLabel?.let { " · $it" } ?: ""}"
                    else -> "${p.count} messages waiting · latest from ${p.latestSender}"
                }
                readBtn.alpha = if (p.total == 0) 0.6f else 1f
            }
        }

        // Try it without receiving a text
        root.sectionHeader("Check that it works")
        root.card {
            body("No texts coming in? Play a sample message to hear the voices, or pull in messages that are already sitting in your notification shade.", Palette.TEXT_DIM)
            buttonRow {
                tonalButton("▶  Play a test message") { g.reader.playTestMessage() }
                outlineButton("Check notifications") {
                    when (val n = g.rescanNotifications()) {
                        null -> g.toast("Notification access isn't connected yet")
                        0 -> g.toast("No new message notifications in the shade")
                        else -> g.toast("Found $n message(s) — tap Read latest message")
                    }
                    a.render()
                }
            }
            if (g.settings.current.smsHistoryEnabled || g.smsInbox.hasPermission()) {
                outlineButton("Open text message history") { a.navigate(Screen.SMS) }
            } else {
                caption("Want to hear older texts too? Open Text Messages to read your SMS inbox (optional).").layoutParams = lp(top = 6)
                outlineButton("Text Messages (SMS history)") { a.navigate(Screen.SMS) }
            }
        }

        // Status
        root.sectionHeader("Status")
        root.card {
            val (access, accessDot) = statusRow("Notification access", "", null)
            val (engine, engineDot) = statusRow("Voice engine", "", null)
            statusRow("Offline mode", "On-device only · no internet permission", true)
            val (auto, autoDot) = statusRow("Automatic reading", "", null)
            val (play, playDot) = statusRow("Playback", "", null)
            val (route, _) = statusRow("Audio output", "", null)
            val (seen, seenDot) = statusRow("Last message seen", "", null)
            a.live += {
                val d = g.diagnostics.value
                seen.text = if (d.lastMessageAtMillis == 0L) {
                    if (d.notificationsSeen == 0) "None yet since the app started" else "None yet (${d.notificationsSeen} other notifications)"
                } else {
                    val mins = (System.currentTimeMillis() - d.lastMessageAtMillis) / 60_000
                    (if (mins < 1) "Just now" else "$mins min ago") + " · ${d.lastMessageApp}"
                }
                seenDot.background = rounded(statusColor(if (d.lastMessageAtMillis > 0) true else null), 6)
            }
            a.live += {
                val ok = a.notificationAccessGranted()
                val connected = g.listenerConnected.value
                access.text = when { !ok -> "Not allowed"; connected -> "Connected"; else -> "Allowed · connecting" }
                accessDot.background = rounded(statusColor(ok && connected), 6)
                val st = g.kokoro.state.value
                engine.text = when {
                    g.settings.current.preferSystemEngine -> "System offline voice (by choice)"
                    st is EngineState.Ready -> "Kokoro neural · ready"
                    st is EngineState.Loading -> "Kokoro neural · loading…"
                    st is EngineState.Failed -> "Neural unavailable · system fallback"
                    else -> "Kokoro neural · loads on demand"
                }
                engineDot.background = rounded(statusColor(if (st is EngineState.Failed) false else true), 6)
                val cur = g.settings.current
                auto.text = if (!cur.autoReadEnabled) "Off" else if (System.currentTimeMillis() < cur.snoozeUntilMillis) "Paused" else "On · ${RoutePolicy.describe(cur.autoReadRoute)}"
                autoDot.background = rounded(statusColor(if (cur.autoReadEnabled) true else null), 6)
                play.text = when (val ps = g.player.state.value) {
                    PlaybackState.Idle -> "Idle"
                    is PlaybackState.Preparing -> "Preparing…"
                    is PlaybackState.Speaking -> "Reading · ${ps.label}" + if (ps.engine == EngineKind.SYSTEM) " (system voice)" else ""
                    is PlaybackState.Paused -> "Paused · ${ps.label}"
                }
                playDot.background = rounded(statusColor(if (g.player.state.value is PlaybackState.Idle) null else true), 6)
                val r = g.routes.route.value
                route.text = when (r.kind) {
                    OutputKind.SPEAKER -> "Phone speaker"
                    OutputKind.WIRED_HEADSET, OutputKind.USB_HEADSET -> "Wired headphones"
                    OutputKind.BLE_SPEAKER -> "Bluetooth speaker"
                    OutputKind.OTHER -> "Other output"
                    else -> "Bluetooth" + (r.name?.let { " · $it" } ?: "")
                }
            }
            g.reader.lastAutoDecision?.let { caption("Last automatic decision: $it").layoutParams = lp(top = 6) }
        }

        // Quick toggles
        root.sectionHeader("Quick settings")
        root.card {
            switchRow("Automatic reading", "Off by default. Reads new messages when your conditions are met.", s.autoReadEnabled) { v ->
                g.settings.update { it.copy(autoReadEnabled = v) }; if (v) g.warmUpEngine(); a.render()
            }
            divider()
            switchRow("Floating read button", "A small movable button over other apps", s.floatingEnabled) { v ->
                if (v) a.enableFloatingButton() else { g.pendingOverlayEnable = false; g.settings.update { it.copy(floatingEnabled = false) } }
            }
            divider()
            valueRow("Default voice", voiceName(a, s.defaultVoice)) { Dialogs.pickVoice(a, "Default voice", s.defaultVoice) { id -> g.settings.update { it.copy(defaultVoice = id) }; a.render() } }
        }

        root.sectionHeader("More")
        root.card {
            navRow("♪", "Voice Studio", "Voices, previews, speed, announcer") { a.navigate(Screen.VOICES) }
            navRow("✉", "Text Messages", "Read older texts from your SMS inbox (optional)") { a.navigate(Screen.SMS) }
            navRow("☺", "People & Voices", "${g.assignments.assignments.value.size} saved · give people their own voice") { a.navigate(Screen.PEOPLE) }
            navRow("⟳", "Automatic Reading", "Headphones, quiet hours, calls, groups") { a.navigate(Screen.AUTO) }
            navRow("◉", "Floating Button & Shortcuts", "Overlay, Quick Settings tile") { a.navigate(Screen.CONTROLS) }
            navRow("⛨", "Privacy Center", "What is kept, what isn't, clear now") { a.navigate(Screen.PRIVACY) }
            navRow("ⓘ", "About", "Version, voice engine, licenses") { a.navigate(Screen.ABOUT) }
        }

        // Waiting messages (sender + app only; never the text)
        val pending = g.repository.all()
        if (pending.isNotEmpty()) {
            root.sectionHeader("Recent messages")
            root.card {
                caption("Kept in memory so you can listen again, even after opening the conversation. Text is never shown or saved.").layoutParams = lp(bottom = 4)
                pending.asReversed().forEach { m ->
                    val leftMin = ((g.repository.ttlMillis - (SystemClock.elapsedRealtime() - m.receivedAtElapsed)) / 60_000).coerceAtLeast(0) + 1
                    val left = if (leftMin >= 120) "${leftMin / 60} h" else "$leftMin min"
                    val where = m.conversationTitle?.let { " in $it" } ?: ""
                    val hidden = if (m.contentAvailable) "" else " · text not shared by app"
                    val state = if (m.heard) "heard" else "new"
                    valueRow("${m.sender.displayName}$where", if (m.heard) "Hear again ▶" else "Read ▶", "${m.appLabel} · $state · kept $left more$hidden") {
                        g.reader.readMessage(m.id); a.render()
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ Voice Studio
    fun voiceStudio(a: MainActivity, root: LinearLayout) = with(a.ui) {
        val g = a.graph
        val s = g.settings.current
        root.caption("All voices run on this phone. Kokoro-82M is a neural text-to-speech model; nothing is sent anywhere.").layoutParams = lp(bottom = 8)

        root.sectionHeader("Engine")
        root.card {
            val (engine, dot) = statusRow("Kokoro-82M v1.0 (8-bit)", "", null)
            val detail = caption("")
            a.live += {
                val st = g.kokoro.state.value
                engine.text = when (st) {
                    is EngineState.Ready -> "Ready (loaded in ${st.loadMillis} ms)"
                    EngineState.Loading -> "Loading…"
                    is EngineState.Failed -> "Unavailable"
                    EngineState.NotLoaded -> "Idle (loads when needed)"
                }
                dot.background = rounded(statusColor(st !is EngineState.Failed), 6)
                val rtf = g.kokoro.lastRealTimeFactor
                detail.text = buildString {
                    append("ONNX Runtime on CPU · 24 kHz · ${KokoroVoices.all.size} voices")
                    if (rtf > 0) append(" · last speed ${"%.2f".format(rtf)}× real time")
                    if (st is EngineState.Failed) append("\nReason: ${st.reason}\nPrivateVoice uses your phone's offline system voice instead.")
                }
            }
            buttonRow {
                tonalButton("Load now") { g.kokoro.resetFailure(); g.warmUpEngine() }
                outlineButton("Unload") { g.kokoro.release() }
            }
            divider()
            switchRow("Use system voice instead", "Android's installed offline voices (lower quality, lower memory use)", s.preferSystemEngine) { v ->
                g.settings.update { it.copy(preferSystemEngine = v) }; if (v) g.systemTts.start(); a.render()
            }
        }

        root.sectionHeader("Speech")
        root.card {
            slider("Speaking speed", 0.5f, 2.0f, s.speechRate, 0.05f, { "%.2f×".format(it) }) { v -> g.settings.update { it.copy(speechRate = v) } }
            slider("Reading volume", 0.2f, 1.0f, s.volume, 0.05f, { "${(it * 100).toInt()}%" }) { v -> g.settings.update { it.copy(volume = v) } }
            switchRow("Describe emoji", "😂 → \"laughing\". Off: emoji are skipped", s.describeEmoji) { v -> g.settings.update { it.copy(describeEmoji = v) } }
            switchRow("Expand texting shorthand", "\"brb\" → \"be right back\", \"idk\" → \"I don't know\"", s.expandShorthand) { v -> g.settings.update { it.copy(expandShorthand = v) } }
        }

        root.sectionHeader("Voice roles")
        root.card {
            valueRow("Default voice", voiceName(a, s.defaultVoice), "Unknown senders and anyone without an assignment") {
                Dialogs.pickVoice(a, "Default voice", s.defaultVoice) { id -> g.settings.update { it.copy(defaultVoice = id) }; a.render() }
            }
            switchRow("Announcer uses default voice", "Who says \"Message from …\"", s.announcerUsesDefaultVoice) { v -> g.settings.update { it.copy(announcerUsesDefaultVoice = v) }; a.render() }
            if (!s.announcerUsesDefaultVoice) {
                valueRow("Announcer voice", voiceName(a, s.announcerVoice)) {
                    Dialogs.pickVoice(a, "Announcer voice", s.announcerVoice) { id -> g.settings.update { it.copy(announcerVoice = id) }; a.render() }
                }
            }
            valueRow("Masculine category voice", voiceName(a, s.masculineVoice), "Used for people you put in the masculine category") {
                Dialogs.pickVoice(a, "Masculine category voice", s.masculineVoice, VoiceCategory.MASCULINE) { id -> g.settings.update { it.copy(masculineVoice = id) }; a.render() }
            }
            valueRow("Feminine category voice", voiceName(a, s.feminineVoice), "Used for people you put in the feminine category") {
                Dialogs.pickVoice(a, "Feminine category voice", s.feminineVoice, VoiceCategory.FEMININE) { id -> g.settings.update { it.copy(feminineVoice = id) }; a.render() }
            }
        }

        root.sectionHeader("Installed voices · tap to preview")
        root.card {
            val all = KokoroVoices.all + g.systemTts.voices()
            all.forEach { v ->
                val tag = when { v.engine == EngineKind.SYSTEM -> "System"; v.presentation == VoiceCategory.MASCULINE -> "Masculine"; else -> "Feminine" }
                valueRow(v.displayName, "▶ Preview", "$tag · ${v.accent} · ${v.description}") { g.reader.previewVoice(v.id, v.displayName) }
            }
            if (g.systemTts.voices().isEmpty()) {
                tonalButton("Show system offline voices") { g.systemTts.start(); a.root().postDelayed({ a.render() }, 1500) }
            }
        }
    }

    // ------------------------------------------------------------------ People & Voices
    fun people(a: MainActivity, root: LinearLayout) = with(a.ui) {
        val g = a.graph
        val list = g.assignments.assignments.value
        root.caption("Give people their own voice. You choose the voice or category for each person — PrivateVoice never guesses anyone's gender or identity.").layoutParams = lp(bottom = 8)
        root.primaryButton("+  Add sender") { Dialogs.editAssignment(a, null) }
        root.caption("Priority: the person's own voice → their category's voice → the default voice.").layoutParams = lp(top = 4, bottom = 8)

        if (list.isEmpty()) {
            root.card {
                headline("No one yet")
                body("Add a sender to hear them in a different voice. Senders you add from a received message are matched by the messaging app's sender ID, so someone else with the same name won't get their voice.", Palette.TEXT_DIM)
            }
        } else {
            root.sectionHeader("Saved (${list.size})")
            list.forEach { asg ->
                root.card {
                    headline(asg.label)
                    val voiceText = asg.voiceId?.let { "Voice: ${voiceName(a, it)}" } ?: when (asg.category) {
                        VoiceCategory.MASCULINE -> "Category: masculine → ${voiceName(a, g.settings.current.masculineVoice)}"
                        VoiceCategory.FEMININE -> "Category: feminine → ${voiceName(a, g.settings.current.feminineVoice)}"
                        VoiceCategory.DEFAULT -> "Default voice"
                    }
                    body(voiceText)
                    val app = asg.appPackage?.let { g.appLabel(it) }
                    caption(
                        if (asg.matchKey != null) "Matched by sender ID${app?.let { " in $it" } ?: ""}"
                        else "Matched by exact name \"${asg.matchName}\"${app?.let { " in $it" } ?: " in any app"}",
                    )
                    buttonRow {
                        tonalButton("▶ Preview") {
                            val choice = g.resolver.resolve(
                                com.privatevoice.app.model.SenderRef(asg.matchName, asg.matchKey, asg.appPackage ?: ""),
                                listOf(asg),
                                com.privatevoice.app.voices.VoiceDefaults(g.settings.current.defaultVoice, g.settings.current.announcerVoice, g.settings.current.announcerUsesDefaultVoice, g.settings.current.masculineVoice, g.settings.current.feminineVoice),
                            )
                            g.reader.previewVoice(choice.messageVoice, asg.label)
                        }
                        outlineButton("Edit") { Dialogs.editAssignment(a, asg) }
                        outlineButton("Remove", Palette.DANGER) {
                            Dialogs.confirm(a, "Remove ${asg.label}?", "They'll be read with the default voice.") { g.assignments.remove(asg.id) }
                        }
                    }
                }
            }
            root.outlineButton("Delete all assignments", Palette.DANGER) {
                Dialogs.confirm(a, "Delete all assignments?", "Removes every saved sender-to-voice preference and destroys its encryption key.") { g.privacy.deleteAllAssignments() }
            }
        }
    }

    // ------------------------------------------------------------------ Automatic reading
    fun autoReading(a: MainActivity, root: LinearLayout) = with(a.ui) {
        val g = a.graph
        val s = g.settings.current
        val now = System.currentTimeMillis()
        root.card {
            switchRow("Automatic reading", "Read new messages aloud without tapping", s.autoReadEnabled) { v ->
                g.settings.update { it.copy(autoReadEnabled = v) }; if (v) g.warmUpEngine(); a.render()
            }
            if (s.autoReadEnabled) {
                if (now < s.snoozeUntilMillis) {
                    body("Paused for ${((s.snoozeUntilMillis - now) / 60_000) + 1} more min", Palette.WARNING)
                    tonalButton("Resume now") { g.settings.update { it.copy(snoozeUntilMillis = 0) }; a.render() }
                } else {
                    caption("Temporarily pause").layoutParams = lp(top = 8)
                    val f = flow()
                    f.chip("15 min", false) { g.settings.update { it.copy(snoozeUntilMillis = now + 15 * 60_000) }; a.render() }
                    f.chip("1 hour", false) { g.settings.update { it.copy(snoozeUntilMillis = now + 60 * 60_000) }; a.render() }
                    f.chip("8 hours", false) { g.settings.update { it.copy(snoozeUntilMillis = now + 8 * 60 * 60_000) }; a.render() }
                    addView(f)
                }
            }
        }

        root.sectionHeader("Where it may play")
        root.card {
            caption("Automatic reading only plays on outputs you allow. Bluetooth devices can be car systems or speakers, so they only count as headphones when you approve them below.")
            val f = flow()
            RouteRestriction.entries.forEach { r ->
                f.chip(RoutePolicy.describe(r), s.autoReadRoute == r) { g.settings.update { it.copy(autoReadRoute = r) }; a.render() }
            }
            f.layoutParams = lp(top = 8)
            addView(f)
            divider()
            body("Approved Bluetooth headphones")
            val connected = g.routes.connectedBluetoothNames()
            val known = (s.approvedBluetoothDevices + connected).sorted()
            if (known.isEmpty()) caption("Connect your Bluetooth headphones to approve them here.")
            known.forEach { name ->
                switchRow(name, if (name in connected) "Connected now" else "Not connected", name in s.approvedBluetoothDevices) { v ->
                    g.settings.update { it.copy(approvedBluetoothDevices = if (v) it.approvedBluetoothDevices + name else it.approvedBluetoothDevices - name) }
                }
            }
            divider()
            switchRow("Stop when audio output disconnects", "Headphones unplugged or Bluetooth lost", s.stopOnDisconnect) { v -> g.settings.update { it.copy(stopOnDisconnect = v) } }
        }

        root.sectionHeader("When")
        root.card {
            switchRow("Only while phone is unlocked", null, s.autoReadOnlyUnlocked) { v -> g.settings.update { it.copy(autoReadOnlyUnlocked = v) } }
            switchRow("Pause during calls", "Never reads during phone or VoIP calls", s.pauseDuringCalls) { v -> g.settings.update { it.copy(pauseDuringCalls = v) } }
            switchRow("Quiet hours", "${QuietHours.format(s.quietStartMinutes)} – ${QuietHours.format(s.quietEndMinutes)}", s.quietHoursEnabled) { v -> g.settings.update { it.copy(quietHoursEnabled = v) } }
            buttonRow {
                outlineButton("Start ${QuietHours.format(s.quietStartMinutes)}") {
                    Dialogs.time(a, s.quietStartMinutes) { m -> g.settings.update { it.copy(quietStartMinutes = m) }; a.render() }
                }
                outlineButton("End ${QuietHours.format(s.quietEndMinutes)}") {
                    Dialogs.time(a, s.quietEndMinutes) { m -> g.settings.update { it.copy(quietEndMinutes = m) }; a.render() }
                }
            }
            switchRow("Skip group messages", null, s.suppressGroups) { v -> g.settings.update { it.copy(suppressGroups = v) } }
        }

        root.sectionHeader("What it says")
        root.card {
            val f = flow()
            f.chip("Sender and message", s.readContent == ReadContent.SENDER_AND_MESSAGE) { g.settings.update { it.copy(readContent = ReadContent.SENDER_AND_MESSAGE) }; a.render() }
            f.chip("Sender name only", s.readContent == ReadContent.SENDER_ONLY) { g.settings.update { it.copy(readContent = ReadContent.SENDER_ONLY) }; a.render() }
            addView(f)
            switchRow("Announce sender first", "\"Message from Sarah.\" in the announcer voice, then the message in Sarah's voice", s.announceSender) { v -> g.settings.update { it.copy(announceSender = v) } }
            switchRow("Repeat sender name", "Announce every message, even several in a row from the same person", s.repeatSenderName) { v -> g.settings.update { it.copy(repeatSenderName = v) } }
            switchRow("Read group name", "\"Message from Sam in Family\"", s.readGroupName) { v -> g.settings.update { it.copy(readGroupName = v) } }
        }

        root.sectionHeader("Manual reading & retention")
        root.card {
            caption("Output allowed for manual reading (Read button, tile, floating button)")
            val f = flow()
            RouteRestriction.entries.forEach { r -> f.chip(RoutePolicy.describe(r), s.manualRoute == r) { g.settings.update { it.copy(manualRoute = r) }; a.render() } }
            addView(f)
            caption("Keep messages to listen again for").layoutParams = lp(top = 12)
            val keep = flow()
            listOf(10 to "10 min", 60 to "1 hour", 240 to "4 hours", 1440 to "24 hours").forEach { (mins, label) ->
                keep.chip(label, s.pendingTtlMinutes == mins) { g.settings.update { it.copy(pendingTtlMinutes = mins) }; a.render() }
            }
            addView(keep)
            caption("Held only in memory: if Android closes PrivateVoice in the background, the list is lost. Regular SMS texts can always be heard again from Text Messages.")
            switchRow("Forget a message once I open it", "Off: it stays in Recent messages until the time above", s.forgetOnDismiss) { v -> g.settings.update { it.copy(forgetOnDismiss = v) } }
            switchRow("Show Read aloud notification", "PrivateVoice's own controls (never shows message text)", s.showReadNotification) { v -> g.settings.update { it.copy(showReadNotification = v) } }
        }

        root.sectionHeader("Apps")
        root.card {
            switchRow("Any messaging app", "Also read apps that post standard Android message notifications", s.readAnyMessagingApp) { v -> g.settings.update { it.copy(readAnyMessagingApp = v) } }
            caption("Always supported: " + SupportedApps.DEFAULT.values.joinToString(", "))
        }

        root.sectionHeader("Reliability")
        root.card {
            val unrestricted = g.privacy.status().batteryUnrestricted
            body(if (unrestricted) "Battery: unrestricted ✓" else "Battery: optimized")
            caption("Android 15+ only lets apps manage audio (lowering music while speaking) from the background when they may start a foreground service. Allowing unrestricted battery use lets automatic reading do that.")
            if (!unrestricted) tonalButton("Allow unrestricted battery use") { a.requestBatteryUnrestricted() }
        }
    }

    // ------------------------------------------------------------------ Floating button & shortcuts
    fun controls(a: MainActivity, root: LinearLayout) = with(a.ui) {
        val g = a.graph
        val s = g.settings.current
        root.sectionHeader("Floating button")
        root.card {
            val can = g.overlay.canDraw()
            switchRow("Show floating read button", if (can) "Tap: read latest · Long-press: controls · Drag to move" else "Needs \"Display over other apps\" permission", s.floatingEnabled) { v ->
                if (v) a.enableFloatingButton() else { g.pendingOverlayEnable = false; g.settings.update { it.copy(floatingEnabled = false) }; a.render() }
            }
            if (!can) tonalButton("Grant overlay permission") { a.enableFloatingButton() }
            slider("Transparency", 0.3f, 1f, s.floatingAlpha, 0.05f, { "${(it * 100).toInt()}% opaque" }) { v -> g.settings.update { it.copy(floatingAlpha = v) } }
            switchRow("Hide when no message is waiting", null, s.floatingHideWhenEmpty) { v -> g.settings.update { it.copy(floatingHideWhenEmpty = v) } }
            switchRow("Hide on the lock screen", "Recommended for privacy", s.floatingHideOnLockScreen) { v -> g.settings.update { it.copy(floatingHideOnLockScreen = v) } }
            outlineButton("Reset position") { g.settings.update { it.copy(floatingX = 0.92f, floatingY = 0.6f) }; g.overlay.reposition() }
        }

        root.sectionHeader("Quick Settings tile")
        root.card {
            body("Add the \"Read message\" tile to your Quick Settings panel. Tap it to read the latest message; tap again to stop. On the lock screen it asks you to unlock first.")
            if (Build.VERSION.SDK_INT >= 33) {
                tonalButton("Add tile") {
                    val sbm = a.getSystemService(StatusBarManager::class.java)
                    sbm.requestAddTileService(
                        ComponentName(a, ReadLatestTileService::class.java), "Read message",
                        Icon.createWithResource(a, R.drawable.ic_stat_voice), a.mainExecutor,
                    ) { }
                }
            }
        }

        root.sectionHeader("Notification")
        root.card {
            body("When a message arrives, PrivateVoice shows a quiet notification with Read aloud, Repeat and Dismiss. While reading it shows Pause and Stop. It never contains the message text, and on the lock screen it only says a message is ready.")
        }
    }

    // ------------------------------------------------------------------ Privacy center
    fun privacy(a: MainActivity, root: LinearLayout) = with(a.ui) {
        val g = a.graph
        val st = g.privacy.status()
        root.card {
            headline("On-device only")
            body("PrivateVoice has no internet permission, no analytics, no ads and no crash reporting. Messages are turned into speech on this phone by the bundled Kokoro voice model.", Palette.TEXT_DIM)
            statusRow("Internet permission", if (st.hasInternetPermission) "Present" else "Not requested", !st.hasInternetPermission)
        }

        root.sectionHeader("Permissions")
        root.card {
            statusRow("Notification access", if (st.notificationAccess) "Allowed" else "Not allowed", st.notificationAccess)
            statusRow("Show notifications", if (st.postNotifications) "Allowed" else "Not allowed", st.postNotifications)
            statusRow("Display over other apps", if (st.overlayPermission) "Allowed" else "Not allowed", if (st.overlayPermission) true else null)
            statusRow("Unrestricted battery", if (st.batteryUnrestricted) "Yes" else "No", if (st.batteryUnrestricted) true else null)
            caption("Never requested: microphone, contacts, SMS, accessibility, internet.").layoutParams = lp(top = 6)
            buttonRow {
                tonalButton("Notification access") { a.openNotificationAccess() }
                outlineButton("App info") { a.openAppInfo() }
            }
        }

        root.sectionHeader("Temporary message content")
        root.card {
            val count = caption("")
            a.live += { count.text = "${g.repository.state.value.count} message(s) held in memory right now." }
            val keepMin = g.settings.current.pendingTtlMinutes
            val keepText = if (keepMin >= 60) "${keepMin / 60} hour(s)" else "$keepMin minutes"
            body("Message text is kept only in memory, for at most $keepText so you can listen again, and speech audio only in memory for Repeat (at most 30 minutes). Both are cleared when they expire, when you tap Clear, when automatic reading is turned off, when notification access is removed, and when the app closes. Text message history (if you turned it on) is read straight from your SMS inbox each time and never copied.", Palette.TEXT_DIM)
            primaryButton("Clear temporary content now") { g.privacy.clearTemporaryContent(); g.toast("Temporary content cleared"); a.render() }
        }

        root.sectionHeader("Saved voice assignments")
        root.card {
            body("The one thing PrivateVoice saves on purpose: which voice you chose for which sender (name or the messaging app's sender ID, and the voice). It never stores message text with them. They are encrypted with a key kept in the Android Keystore and excluded from backups.", Palette.TEXT_DIM)
            statusRow("Saved assignments", st.savedAssignments.toString(), null)
            buttonRow {
                tonalButton("Manage") { a.navigate(Screen.PEOPLE) }
                outlineButton("Delete all", Palette.DANGER) {
                    Dialogs.confirm(a, "Delete all assignments?", "This cannot be undone.") { g.privacy.deleteAllAssignments(); a.render() }
                }
            }
        }

        root.sectionHeader("Honest limits")
        root.card {
            body(
                "• Android keeps its own copy of notifications; PrivateVoice can't erase those.\n" +
                    "• Text in Java/Kotlin memory can't be securely wiped; PrivateVoice drops references and zeroes audio buffers, and the system reclaims the memory later.\n" +
                    "• Bluetooth devices may be speakers or car systems. Restrict automatic reading to approved headphones if others could hear.\n" +
                    "• If you choose the system voice, speech is produced by your phone's installed text-to-speech app (offline voices only).",
                Palette.TEXT_DIM,
            )
        }
    }

    // ------------------------------------------------------------------ Text message history (opt-in SMS)
    fun sms(a: MainActivity, root: LinearLayout) = with(a.ui) {
        val g = a.graph
        val inbox = g.smsInbox
        if (!inbox.hasPermission()) {
            root.card {
                headline("Read your text message history")
                body(
                    "PrivateVoice can read texts that are already on your phone (your SMS inbox), not just new ones. " +
                        "Android will ask you to allow SMS access.",
                ).layoutParams = lp(top = 6, bottom = 6)
                body(
                    "Texts are read only when you tap them — never copied, saved, uploaded, or shown anywhere else. " +
                        "This is optional and off until you allow it.",
                    Palette.TEXT_DIM,
                )
                primaryButton("Allow SMS access") { a.requestReadSms() }
                caption("If Android says \"Restricted setting\": open App info → ⋮ menu → \"Allow restricted settings\", then try again.").layoutParams = lp(top = 4)
                outlineButton("Open App info") { a.openAppInfo() }
            }
            root.card {
                headline("Good to know")
                body(
                    "• Chat/RCS messages (the ones Google Messages or Samsung Messages send over data) are stored privately by those apps, " +
                        "so they can't be read from history — PrivateVoice reads those from their notifications instead.\n" +
                        "• Without Contacts access, senders show as phone numbers. Tap \"Name & voice\" on a message to give that number a name and its own voice.",
                    Palette.TEXT_DIM,
                )
            }
            return@with
        }
        if (!g.settings.current.smsHistoryEnabled) g.settings.update { it.copy(smsHistoryEnabled = true) }
        val assignments = g.assignments.assignments.value
        val items = inbox.recent(40)
        root.caption("Your ${items.size} most recent texts. Tap ▶ to hear one. Nothing here is saved.").layoutParams = lp(bottom = 8)
        root.buttonRow {
            tonalButton("▶  Newest unread") {
                val unread = inbox.recent(5, unreadOnly = true)
                if (unread.isEmpty()) g.toast("No unread texts")
                unread.reversed().forEachIndexed { i, item ->
                    g.reader.readNow(com.privatevoice.app.sms.SmsSenders.sender(item.address, assignments, g.resolver), null, item.body, interrupt = i == 0)
                    item.wipe()
                }
            }
            outlineButton("↻  Refresh") { a.render() }
        }
        if (items.isEmpty()) {
            root.card { body("Your SMS inbox is empty.", Palette.TEXT_DIM) }
        } else {
            root.card {
                items.forEachIndexed { index, item ->
                    if (index > 0) divider()
                    val label = com.privatevoice.app.sms.SmsSenders.listLabel(item.address, assignments, g.resolver)
                    val whenText = android.text.format.DateUtils.getRelativeTimeSpanString(
                        item.dateMillis, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS,
                    ).toString()
                    addView(row {
                        minimumHeight = dp(64)
                        val texts = column { layoutParams = lp(w = 0, weight = 1f, end = 8) }
                        texts.text(label, 16f, Palette.TEXT, bold = !item.read)
                        texts.caption(whenText + if (!item.read) " · unread" else "")
                        addView(texts)
                        addView(android.widget.TextView(a).apply {
                            text = "▶"; textSize = 18f; gravity = android.view.Gravity.CENTER; setTextColor(Palette.ON_PRIMARY)
                            contentDescription = "Read message from $label"
                            background = ripple(rounded(Palette.PRIMARY, 24))
                            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginEnd = dp(8) }
                            setOnClickListener {
                                g.reader.readNow(com.privatevoice.app.sms.SmsSenders.sender(item.address, assignments, g.resolver), null, item.body)
                            }
                        })
                        addView(android.widget.TextView(a).apply {
                            text = "Name &\nvoice"; textSize = 12f; gravity = android.view.Gravity.CENTER; setTextColor(Palette.TEXT)
                            contentDescription = "Give $label a name and voice"
                            background = ripple(rounded(Palette.SURFACE_HIGH, 14, Palette.OUTLINE))
                            layoutParams = LinearLayout.LayoutParams(dp(64), dp(48))
                            setOnClickListener {
                                val key = com.privatevoice.app.voices.PhoneNumbers.toKey(item.address)
                                val existing = g.resolver.match(com.privatevoice.app.model.SenderRef(item.address, key, com.privatevoice.app.sms.SmsSenders.PACKAGE), assignments)
                                Dialogs.editAssignment(a, existing, prefillKey = key)
                            }
                        })
                    })
                }
            }
        }
        root.outlineButton("Stop using text history", Palette.DANGER) {
            g.settings.update { it.copy(smsHistoryEnabled = false) }
            // Android removes the permission when the app next closes.
            runCatching { a.revokeSelfPermissionOnKill(android.Manifest.permission.READ_SMS) }
            g.toast("SMS access will be removed when PrivateVoice closes")
            a.navigate(Screen.DASHBOARD)
        }
    }

    // ------------------------------------------------------------------ About
    fun about(a: MainActivity, root: LinearLayout) = with(a.ui) {
        val info = a.packageManager.getPackageInfo(a.packageName, 0)
        root.card {
            headline("PrivateVoice ${info.versionName}")
            caption("Version code ${info.longVersionCode} · target Android 16 (API 36) · ${Build.MODEL} · Android ${Build.VERSION.RELEASE}")
        }
        root.sectionHeader("Voice engine")
        root.card {
            body("Kokoro-82M v1.0 by hexgrad (Apache-2.0), 8-bit quantized ONNX export by onnx-community, run with ONNX Runtime (MIT) on the phone's CPU. Pronunciation lexicon from misaki (Apache-2.0). Voices: ${KokoroVoices.all.size} English voices.")
            val manifest = runCatching { a.assets.open("kokoro/MANIFEST.txt").bufferedReader().readText() }.getOrNull()
            if (manifest != null) caption(manifest.trim())
        }
        root.sectionHeader("Open-source licenses")
        root.card {
            val text = runCatching { a.assets.open("licenses.txt").bufferedReader().readText() }.getOrDefault("License file missing.")
            caption(text)
        }
    }

    fun voiceName(a: MainActivity, id: String): String =
        KokoroVoices.find(id)?.displayName ?: a.graph.systemTts.voices().firstOrNull { it.id == id }?.displayName
            ?: if (id.startsWith("system:")) id.removePrefix("system:") else id
}

/** Root view of the activity (for posting delayed refreshes). */
fun MainActivity.root(): android.view.View = window.decorView
