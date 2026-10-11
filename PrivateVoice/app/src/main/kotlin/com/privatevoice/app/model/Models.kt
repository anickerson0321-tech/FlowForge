package com.privatevoice.app.model

/**
 * Who sent a message, as exposed by the notification.
 *
 * [stableKey] comes from notification data (MessagingStyle `Person.key` or
 * `Person.uri`, e.g. a `tel:` URI or contact lookup URI) when the messaging
 * app provides one. It is the preferred way to match voice assignments;
 * display names are only used when no key is available.
 */
data class SenderRef(
    val displayName: String,
    val stableKey: String? = null,
    val appPackage: String,
)

/**
 * A short-lived, in-memory message. Never persisted, never logged.
 * [text] is null when the messaging app hides the content (e.g. "Sensitive
 * notification content hidden") — PrivateVoice never invents content.
 */
class IncomingMessage(
    val id: Long,
    val notificationKey: String,
    val appPackage: String,
    val appLabel: String,
    val sender: SenderRef,
    val conversationTitle: String?,
    val isGroup: Boolean,
    text: String?,
    val postedAtMillis: Long,
    val receivedAtElapsed: Long,
) {
    @Volatile
    var text: String? = text
        private set

    val contentAvailable: Boolean get() = !text.isNullOrBlank()

    /** True once it has been read aloud; it stays available to hear again until it expires. */
    @Volatile
    var heard: Boolean = false

    /** Drops the reference to the message text (best effort; JVM strings are immutable). */
    fun wipe() {
        text = null
    }

    override fun toString(): String = "IncomingMessage(id=$id, app=$appPackage, group=$isGroup)" // never includes content
}

/** User-chosen voice category for a sender. Never inferred automatically. */
enum class VoiceCategory { DEFAULT, MASCULINE, FEMININE }

enum class EngineKind { KOKORO, SYSTEM }

data class VoiceInfo(
    val id: String,
    val displayName: String,
    val engine: EngineKind,
    /** Presentation label provided by the voice's authors (e.g. Kokoro "af" = American feminine). */
    val presentation: VoiceCategory,
    val accent: String,
    val description: String,
)

/**
 * A persistent sender -> voice preference. Contains no message content.
 * Matching uses [matchKey] (notification-provided stable identifier) when
 * present; otherwise the exact [matchName] (+ [appPackage] when set).
 */
data class SenderAssignment(
    val id: String,
    val label: String,
    val matchKey: String?,
    val matchName: String,
    val appPackage: String?,
    val voiceId: String?,
    val category: VoiceCategory,
    val updatedAt: Long,
)
