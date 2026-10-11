package com.privatevoice.app.reader

import com.privatevoice.app.audio.AudioPlaybackController
import com.privatevoice.app.audio.PlaybackJob
import com.privatevoice.app.audio.Segment
import com.privatevoice.app.messaging.NewMessageTracker
import com.privatevoice.app.messaging.TemporaryMessageRepository
import com.privatevoice.app.model.IncomingMessage
import com.privatevoice.app.model.SenderAssignment
import com.privatevoice.app.model.SenderRef
import com.privatevoice.app.notifications.ParsedMessage
import com.privatevoice.app.settings.AppSettings
import com.privatevoice.app.settings.ReadContent
import com.privatevoice.app.voices.VoiceDefaults
import com.privatevoice.app.voices.VoiceSelectionResolver
import java.util.concurrent.atomic.AtomicLong

/** Device facts the reader needs, abstracted for testing. */
interface DeviceState {
    fun route(): AudioRoute
    fun isLocked(): Boolean
    fun inCall(): Boolean
    fun minuteOfDay(): Int
    fun nowMillis(): Long
    fun elapsedMillis(): Long
}

/** Receives UI-facing updates (our own notification, tile, overlay). */
interface ReaderListener {
    fun onPendingChanged()
    fun onInfo(message: String) {}
}

enum class ReadResult { STARTED, NOTHING_PENDING }

/**
 * Coordinates incoming messages, manual/automatic reading, voice selection and
 * playback. Holds message content only through [TemporaryMessageRepository].
 */
class ReaderController(
    private val repository: TemporaryMessageRepository,
    private val tracker: NewMessageTracker,
    private val player: AudioPlaybackController,
    private val resolver: VoiceSelectionResolver,
    private val settings: () -> AppSettings,
    private val assignments: () -> List<SenderAssignment>,
    private val device: DeviceState,
    private val warmUp: () -> Unit = {},
) {
    private val ids = AtomicLong(1)
    var listener: ReaderListener? = null
    @Volatile private var lastSpokenSender: String? = null
    @Volatile private var lastSpokenAt: Long = 0

    /** Last automatic-reading decision, for the dashboard ("why didn't it read?"). Contains no content. */
    @Volatile var lastAutoDecision: String? = null
        private set

    /**
     * Handles messages parsed from one posted notification. Returns the messages
     * that were new (after metadata-only de-duplication).
     */
    fun onMessagesPosted(
        notificationKey: String,
        appPackage: String,
        appLabel: String,
        parsed: List<ParsedMessage>,
    ): List<IncomingMessage> {
        val fresh = parsed.filter { tracker.isNew(notificationKey, it.timestamp) }
        if (fresh.isEmpty()) return emptyList()
        val created = fresh.map { p ->
            IncomingMessage(
                id = ids.getAndIncrement(),
                notificationKey = notificationKey,
                appPackage = appPackage,
                appLabel = appLabel,
                sender = SenderRef(p.senderName, p.senderKey, appPackage),
                conversationTitle = p.conversationTitle,
                isGroup = p.isGroup,
                text = p.text,
                postedAtMillis = p.timestamp,
                receivedAtElapsed = device.elapsedMillis(),
            )
        }
        val s = settings()
        repository.ttlMillis = s.pendingTtlMinutes * 60_000L
        created.forEach { repository.add(it) }
        listener?.onPendingChanged()
        if (s.autoReadEnabled) warmUp()
        for (m in created) {
            val decision = AutoReadPolicy.evaluate(
                AutoReadContext(
                    settings = s,
                    route = device.route(),
                    deviceLocked = device.isLocked(),
                    inCall = device.inCall(),
                    minuteOfDay = device.minuteOfDay(),
                    nowMillis = device.nowMillis(),
                    isGroup = m.isGroup,
                    contentAvailable = m.contentAvailable,
                ),
            )
            when (decision) {
                is AutoReadDecision.Read -> {
                    lastAutoDecision = "Read automatically"
                    speak(m, decision.content, automatic = true, interrupt = false)
                }
                is AutoReadDecision.Skip -> lastAutoDecision = decision.reason
            }
        }
        return created
    }

    /** Marks messages of a notification as already seen without reading (e.g. present when the listener connects). */
    fun primeExisting(notificationKey: String, parsed: List<ParsedMessage>) {
        parsed.forEach { tracker.prime(notificationKey, it.timestamp) }
    }

    fun readLatest(): ReadResult {
        val m = repository.latest() ?: return ReadResult.NOTHING_PENDING
        speak(m, settings().readContent, automatic = false, interrupt = true)
        return ReadResult.STARTED
    }

    fun readMessage(id: Long): ReadResult {
        val m = repository.get(id) ?: return ReadResult.NOTHING_PENDING
        speak(m, settings().readContent, automatic = false, interrupt = true)
        return ReadResult.STARTED
    }

    /** Builds the playback job for [m]; exposed for tests. */
    fun buildJob(m: IncomingMessage, content: ReadContent, automatic: Boolean): PlaybackJob {
        val s = settings()
        val choice = resolver.resolve(
            m.sender,
            assignments(),
            VoiceDefaults(s.defaultVoice, s.announcerVoice, s.announcerUsesDefaultVoice, s.masculineVoice, s.feminineVoice),
        )
        val now = device.elapsedMillis()
        val previous = lastSpokenSender.takeIf { now - lastSpokenAt < SAME_SESSION_MILLIS }
        val segments = ScriptBuilder.build(
            senderName = m.sender.displayName,
            groupName = m.conversationTitle,
            text = m.text,
            voices = choice,
            options = ScriptOptions(s.announceSender, s.readGroupName, s.repeatSenderName, content),
            previousSenderOfSession = previous,
        )
        return PlaybackJob(
            id = ids.getAndIncrement(),
            label = m.sender.displayName,
            messageId = m.id,
            segments = segments,
            route = if (automatic) s.autoReadRoute else s.manualRoute,
            automatic = automatic,
        )
    }

    private fun speak(m: IncomingMessage, content: ReadContent, automatic: Boolean, interrupt: Boolean) {
        val job = buildJob(m, content, automatic)
        lastSpokenSender = m.sender.displayName
        lastSpokenAt = device.elapsedMillis()
        player.play(job, interrupt)
        // Once handed to the player the message is no longer "waiting"; the player keeps
        // the rendered audio only for Repeat until it is cleared or expires.
        repository.remove(m.id)
        listener?.onPendingChanged()
    }

    fun previewVoice(voiceId: String, voiceName: String) {
        val s = settings()
        player.play(
            PlaybackJob(ids.getAndIncrement(), "Voice preview", null, listOf(Segment(ScriptBuilder.previewText(voiceName), voiceId)), s.manualRoute, false),
            interrupt = true,
        )
    }

    fun stop() = player.stop()
    fun pause() = player.pause()
    fun resume() = player.resume()
    fun repeat(): Boolean = player.repeatLast()

    /** Called periodically: drops expired messages and the Repeat cache after the same lifetime. */
    fun tick() {
        val removed = repository.expire()
        if (removed > 0) listener?.onPendingChanged()
        if (lastSpokenAt != 0L && device.elapsedMillis() - lastSpokenAt > repository.ttlMillis) {
            player.clearCache()
            lastSpokenSender = null
            lastSpokenAt = 0
        }
    }

    fun onNotificationRemoved(key: String) {
        repository.removeByNotificationKey(key)
        listener?.onPendingChanged()
    }

    /** Clears all temporary content: pending messages, playback, and the Repeat cache. */
    fun clearTemporary() {
        player.stop()
        player.clearCache()
        repository.clear()
        lastSpokenSender = null
        lastSpokenAt = 0
        lastAutoDecision = null
        listener?.onPendingChanged()
    }

    fun onAutoReadDisabled() {
        clearTemporary()
    }

    fun onListenerDisconnected() {
        clearTemporary()
        tracker.clear()
    }

    companion object {
        /** Consecutive messages from the same sender within this window can skip the repeated announcement. */
        const val SAME_SESSION_MILLIS = 2 * 60_000L
    }
}
