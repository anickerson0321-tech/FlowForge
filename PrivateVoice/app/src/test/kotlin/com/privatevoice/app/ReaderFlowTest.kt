package com.privatevoice.app

import com.privatevoice.app.audio.AudioPlaybackController
import com.privatevoice.app.audio.PlaybackEvent
import com.privatevoice.app.audio.PlaybackJob
import com.privatevoice.app.audio.PlaybackState
import com.privatevoice.app.messaging.InMemoryMessageRepository
import com.privatevoice.app.messaging.NewMessageTracker
import com.privatevoice.app.model.SenderAssignment
import com.privatevoice.app.model.VoiceCategory
import com.privatevoice.app.notifications.ParsedMessage
import com.privatevoice.app.reader.AudioRoute
import com.privatevoice.app.reader.AutoReadContext
import com.privatevoice.app.reader.AutoReadDecision
import com.privatevoice.app.reader.AutoReadPolicy
import com.privatevoice.app.reader.DeviceState
import com.privatevoice.app.reader.OutputKind
import com.privatevoice.app.reader.QuietHours
import com.privatevoice.app.reader.ReadResult
import com.privatevoice.app.reader.ReaderController
import com.privatevoice.app.reader.RoutePolicy
import com.privatevoice.app.settings.AppSettings
import com.privatevoice.app.settings.ReadContent
import com.privatevoice.app.settings.RouteRestriction
import com.privatevoice.app.voices.DefaultVoiceSelectionResolver
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderFlowTest {
    // ---------------- fakes
    class FakePlayer : AudioPlaybackController {
        val played = ArrayList<Pair<PlaybackJob, Boolean>>()
        var stopped = 0; var cleared = 0; var repeats = 0
        override val state = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
        override val events = MutableSharedFlow<PlaybackEvent>()
        override fun play(job: PlaybackJob, interrupt: Boolean) { played += job to interrupt }
        override fun stop() { stopped++ }
        override fun pause() {}
        override fun resume() {}
        override fun repeatLast(): Boolean { repeats++; return played.isNotEmpty() }
        override fun hasRepeat() = played.isNotEmpty()
        override fun clearCache() { cleared++ }
        override fun clearCacheFor(messageId: Long) {}
        override fun release() {}
    }

    class FakeDevice : DeviceState {
        var route = AudioRoute(OutputKind.WIRED_HEADSET, null)
        var locked = false; var call = false; var minute = 12 * 60; var elapsed = 0L
        override fun route() = route
        override fun isLocked() = locked
        override fun inCall() = call
        override fun minuteOfDay() = minute
        override fun nowMillis() = 1_000_000L
        override fun elapsedMillis() = elapsed
    }

    private var settings = AppSettings()
    private val assignments = ArrayList<SenderAssignment>()
    private val device = FakeDevice()
    private val player = FakePlayer()
    private val repo = InMemoryMessageRepository(clock = { device.elapsed })
    private val reader = ReaderController(repo, NewMessageTracker(), player, DefaultVoiceSelectionResolver(), { settings }, { assignments }, device)

    private fun post(sender: String, text: String?, ts: Long, key: String = "n1", senderKey: String? = null, group: String? = null) =
        reader.onMessagesPosted(key, "com.google.android.apps.messaging", "Google Messages", listOf(ParsedMessage(sender, senderKey, text, ts, group, group != null)))

    // ---------------- manual reading
    @Test fun manualModeHoldsMessageUntilReadLatest() {
        post("Sarah", "Hey, are we still going out tonight?", 100)
        assertTrue(player.played.isEmpty())
        assertEquals(1, repo.state.value.count)
        assertEquals(ReadResult.STARTED, reader.readLatest())
        val (job, interrupt) = player.played.single()
        assertTrue(interrupt)
        assertEquals(RouteRestriction.ANY, job.route)
        assertEquals(0, repo.state.value.count) // no longer "new"...
        assertEquals(1, repo.state.value.total) // ...but kept so it can be heard again
        assertTrue(repo.all().single().heard)
        // Nothing new: Read latest replays the most recent message.
        assertEquals(ReadResult.STARTED, reader.readLatest())
        assertEquals(2, player.played.size)
    }

    @Test fun openingTheConversationDoesNotForgetTheMessage() {
        post("Sarah", "Call me later", 100, key = "conv-sarah")
        reader.onNotificationRemoved("conv-sarah") // user opened the chat in their messaging app
        assertEquals(1, repo.all().size)
        assertEquals(ReadResult.STARTED, reader.readMessage(repo.all().single().id))
        settings = settings.copy(forgetOnDismiss = true)
        post("Bob", "x", 200, key = "conv-bob")
        reader.onNotificationRemoved("conv-bob")
        assertEquals(listOf("Sarah"), repo.all().map { it.sender.displayName })
    }

    @Test fun heardMessagesStayUntilTheyExpire() {
        settings = settings.copy(pendingTtlMinutes = 24 * 60)
        post("Sarah", "one", 100)
        reader.readLatest()
        device.elapsed = 23 * 60 * 60_000L
        reader.tick()
        assertEquals(1, repo.all().size)
        device.elapsed = 24 * 60 * 60_000L
        reader.tick()
        assertEquals(0, repo.all().size)
    }

    @Test fun messagesAlreadyInTheShadeAreNeverReadAutomatically() {
        settings = settings.copy(autoReadEnabled = true, autoReadRoute = RouteRestriction.ANY)
        val n = reader.addExisting("old", "pkg", "App", listOf(ParsedMessage("Sarah", null, "earlier text", 50, null, false)))
        assertEquals(1, n)
        assertTrue(player.played.isEmpty())
        assertEquals(1, repo.state.value.count)
        // Seen again when the listener reconnects: not duplicated.
        assertEquals(0, reader.addExisting("old", "pkg", "App", listOf(ParsedMessage("Sarah", null, "earlier text", 50, null, false))))
    }

    @Test fun testMessageUsesAnnouncerAndSenderVoice() {
        assignments += SenderAssignment("1", "Sarah", null, "Sarah", null, "kokoro:af_bella", VoiceCategory.DEFAULT, 0)
        reader.playTestMessage()
        val segs = player.played.single().first.segments
        assertEquals("Message from Sarah.", segs.first().text)
        assertEquals("kokoro:af_bella", segs.last().voiceId)
        assertEquals(ReaderController.TEST_TEXT, segs.last().text)
        assertEquals(0, repo.all().size) // test messages are not kept
    }

    @Test fun smsHistorySendersUseTheNameTheUserGave() {
        val resolver = DefaultVoiceSelectionResolver()
        assignments += SenderAssignment("1", "Mom", "tel:+15551234567", "", null, "kokoro:af_sarah", VoiceCategory.DEFAULT, 0)
        val known = com.privatevoice.app.sms.SmsSenders.sender("(555) 123-4567", assignments, resolver)
        assertEquals("Mom", known.displayName)
        val unknown = com.privatevoice.app.sms.SmsSenders.sender("+1 555 000 9876", assignments, resolver)
        assertEquals("a number ending in 9 8 7 6", unknown.displayName)
        reader.readNow(known, null, "Dinner is ready")
        val segs = player.played.single().first.segments
        assertEquals("Message from Mom.", segs.first().text)
        assertEquals("kokoro:af_sarah", segs.last().voiceId)
    }

    @Test fun announcerAndSenderVoicesAreSwitched() {
        settings = settings.copy(announcerUsesDefaultVoice = true, defaultVoice = "kokoro:af_heart")
        assignments += SenderAssignment("1", "Sarah", null, "Sarah", null, "kokoro:af_bella", VoiceCategory.DEFAULT, 0)
        post("Sarah", "Hey, are we still going out tonight?", 100)
        reader.readLatest()
        val segs = player.played.single().first.segments
        assertEquals(2, segs.size)
        assertEquals("Message from Sarah.", segs[0].text)
        assertEquals("kokoro:af_heart", segs[0].voiceId)
        assertEquals("Hey, are we still going out tonight?", segs[1].text)
        assertEquals("kokoro:af_bella", segs[1].voiceId)
    }

    @Test fun categoryFallbackAndUnknownSender() {
        assignments += SenderAssignment("1", "Buddy", null, "Buddy", null, null, VoiceCategory.MASCULINE, 0)
        settings = settings.copy(announceSender = false)
        post("Buddy", "game tonight?", 100); reader.readLatest()
        assertEquals(settings.masculineVoice, player.played.last().first.segments.single().voiceId)
        post("Unknown sender", "hi", 200); reader.readLatest()
        assertEquals(settings.defaultVoice, player.played.last().first.segments.single().voiceId)
    }

    @Test fun groupNameIsAnnouncedWhenEnabled() {
        settings = settings.copy(announcerUsesDefaultVoice = false, announcerVoice = "kokoro:bm_george")
        post("Mom", "Dinner at 6", 100, group = "Family")
        reader.readLatest()
        assertEquals("Message from Mom in Family.", player.played.single().first.segments.first().text)
    }

    @Test fun repeatedSenderNameCanBeSkipped() {
        settings = settings.copy(repeatSenderName = false)
        post("Sarah", "one", 100); reader.readLatest()
        post("Sarah", "two", 200); reader.readLatest()
        assertEquals("two", player.played.last().first.segments.joinToString(" ") { it.text })
        settings = settings.copy(repeatSenderName = true)
        post("Sarah", "three", 300); reader.readLatest()
        // Same announcer and message voice here, so both parts are merged into one utterance.
        assertEquals("Message from Sarah. three", player.played.last().first.segments.joinToString(" ") { it.text })
    }

    @Test fun hiddenContentAnnouncesSenderOnly() {
        post("Sarah", null, 100); reader.readLatest()
        val seg = player.played.single().first.segments.single()
        assertTrue(seg.text.startsWith("Message from Sarah."))
        assertTrue(seg.text.contains("didn't share"))
    }

    // ---------------- automatic reading
    @Test fun automaticReadingIsOffByDefault() {
        assertEquals(false, AppSettings().autoReadEnabled)
        post("Sarah", "hi", 100)
        assertTrue(player.played.isEmpty())
    }

    @Test fun automaticReadingPlaysOnAllowedRouteOnly() {
        settings = settings.copy(autoReadEnabled = true, autoReadRoute = RouteRestriction.HEADPHONES_ONLY)
        post("Sarah", "hi", 100)
        assertEquals(1, player.played.size)
        assertEquals(false, player.played.single().second) // queued, does not interrupt
        device.route = AudioRoute.SPEAKER
        post("Sarah", "again", 200)
        assertEquals(1, player.played.size)
        assertTrue(reader.lastAutoDecision!!.startsWith("Audio output not allowed"))
    }

    @Test fun duplicateNotificationUpdatesAreNotReadTwice() {
        settings = settings.copy(autoReadEnabled = true, autoReadRoute = RouteRestriction.ANY)
        post("Sarah", "hi", 100)
        post("Sarah", "hi", 100) // same notification re-posted (e.g. updated badge)
        assertEquals(1, player.played.size)
        // Updated MessagingStyle notification carrying the old + a new message.
        reader.onMessagesPosted("n1", "pkg", "App", listOf(ParsedMessage("Sarah", null, "hi", 100, null, false), ParsedMessage("Sarah", null, "new", 300, null, false)))
        assertEquals(2, player.played.size)
        assertEquals("new", player.played.last().first.segments.last().text)
    }

    @Test fun senderOnlyMode() {
        settings = settings.copy(autoReadEnabled = true, autoReadRoute = RouteRestriction.ANY, readContent = ReadContent.SENDER_ONLY)
        post("Sarah", "private stuff", 100)
        val segs = player.played.single().first.segments
        assertEquals(listOf("You have a message from Sarah."), segs.map { it.text })
    }

    @Test fun clearingAndDisconnectRemoveAllTemporaryContent() {
        post("A", "x", 1); post("B", "y", 2, key = "n2")
        reader.onListenerDisconnected()
        assertEquals(0, repo.state.value.count)
        assertTrue(player.stopped > 0 && player.cleared > 0)
    }

    @Test fun pendingMessagesExpire() {
        settings = settings.copy(pendingTtlMinutes = 2)
        post("A", "x", 1)
        device.elapsed = 2 * 60_000L
        reader.tick()
        assertEquals(0, repo.state.value.count)
    }

    // ---------------- policies
    private fun ctx(s: AppSettings, route: AudioRoute = AudioRoute(OutputKind.WIRED_HEADSET, null), locked: Boolean = false, call: Boolean = false, minute: Int = 600, group: Boolean = false, content: Boolean = true) =
        AutoReadContext(s, route, locked, call, minute, 1000, group, content)

    @Test fun autoReadPolicyRules() {
        val on = AppSettings(autoReadEnabled = true)
        assertTrue(AutoReadPolicy.evaluate(ctx(on)) is AutoReadDecision.Read)
        assertTrue(AutoReadPolicy.evaluate(ctx(AppSettings())) is AutoReadDecision.Skip)
        assertTrue(AutoReadPolicy.evaluate(ctx(on.copy(snoozeUntilMillis = 5000))) is AutoReadDecision.Skip)
        assertTrue(AutoReadPolicy.evaluate(ctx(on, call = true)) is AutoReadDecision.Skip)
        assertTrue(AutoReadPolicy.evaluate(ctx(on.copy(autoReadOnlyUnlocked = true), locked = true)) is AutoReadDecision.Skip)
        assertTrue(AutoReadPolicy.evaluate(ctx(on.copy(suppressGroups = true), group = true)) is AutoReadDecision.Skip)
        assertTrue(AutoReadPolicy.evaluate(ctx(on.copy(quietHoursEnabled = true, quietStartMinutes = 22 * 60, quietEndMinutes = 7 * 60), minute = 23 * 60)) is AutoReadDecision.Skip)
        assertEquals(AutoReadDecision.Read(ReadContent.SENDER_ONLY), AutoReadPolicy.evaluate(ctx(on, content = false)))
    }

    @Test fun quietHoursWrapMidnight() {
        assertTrue(QuietHours.isQuiet(23 * 60, 22 * 60, 7 * 60))
        assertTrue(QuietHours.isQuiet(3 * 60, 22 * 60, 7 * 60))
        assertTrue(!QuietHours.isQuiet(12 * 60, 22 * 60, 7 * 60))
        assertTrue(QuietHours.isQuiet(13 * 60, 12 * 60, 14 * 60))
        assertTrue(!QuietHours.isQuiet(10, 300, 300))
    }

    @Test fun bluetoothIsNotAssumedPrivate() {
        val car = AudioRoute(OutputKind.BLUETOOTH_A2DP, "My Car")
        val buds = AudioRoute(OutputKind.BLUETOOTH_A2DP, "Galaxy Buds3 Pro")
        assertTrue(!RoutePolicy.allows(car, RouteRestriction.HEADPHONES_ONLY, emptySet()))
        assertTrue(RoutePolicy.allows(buds, RouteRestriction.HEADPHONES_ONLY, setOf("Galaxy Buds3 Pro")))
        assertTrue(!RoutePolicy.allows(AudioRoute(OutputKind.BLE_SPEAKER, "Speaker"), RouteRestriction.HEADPHONES_ONLY, setOf("Speaker")))
        assertTrue(RoutePolicy.allows(car, RouteRestriction.BLUETOOTH_ONLY, emptySet()))
        assertTrue(!RoutePolicy.allows(AudioRoute.SPEAKER, RouteRestriction.BLUETOOTH_ONLY, emptySet()))
        assertTrue(RoutePolicy.allows(AudioRoute.SPEAKER, RouteRestriction.ANY, emptySet()))
        assertTrue(RoutePolicy.allows(AudioRoute(OutputKind.WIRED_HEADSET, null), RouteRestriction.HEADPHONES_ONLY, emptySet()))
    }
}
