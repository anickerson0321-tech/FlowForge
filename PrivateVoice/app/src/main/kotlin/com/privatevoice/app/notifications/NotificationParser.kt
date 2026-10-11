package com.privatevoice.app.notifications

/**
 * Plain snapshot of the parts of a notification PrivateVoice needs. Built by
 * [AndroidNotificationExtractor] so that parsing logic is free of Android
 * types and unit-testable.
 */
data class NotificationSnapshot(
    val key: String,
    val packageName: String,
    val postTime: Long,
    val category: String?,
    val isGroupSummary: Boolean,
    val isOngoing: Boolean,
    val isMessagingStyle: Boolean,
    val selfName: String?,
    val conversationTitle: String?,
    val isGroupConversation: Boolean,
    val messages: List<SnapshotMessage>,
    val title: String?,
    val text: String?,
    val bigText: String?,
    val textLines: List<String>,
    val sensitiveContentHidden: Boolean,
)

data class SnapshotMessage(
    val senderName: String?,
    val senderKey: String?,
    val text: String?,
    val timestamp: Long,
    val isFromSelf: Boolean,
)

/** One readable message extracted from a notification (still in memory only). */
data class ParsedMessage(
    val senderName: String,
    val senderKey: String?,
    val text: String?,
    val timestamp: Long,
    val conversationTitle: String?,
    val isGroup: Boolean,
)

interface NotificationParser {
    /** True if the notification looks like an incoming chat/SMS message worth considering. */
    fun isCandidate(snapshot: NotificationSnapshot, supportedApps: Set<String>, allowAnyMessagingApp: Boolean): Boolean

    /** Extracts readable messages, oldest first. Returns an empty list for non-message notifications. */
    fun parse(snapshot: NotificationSnapshot): List<ParsedMessage>
}

class MessagingNotificationParser : NotificationParser {

    override fun isCandidate(snapshot: NotificationSnapshot, supportedApps: Set<String>, allowAnyMessagingApp: Boolean): Boolean {
        if (snapshot.isGroupSummary || snapshot.isOngoing) return false
        val known = snapshot.packageName in supportedApps
        val looksLikeMessage = snapshot.isMessagingStyle || snapshot.category == CATEGORY_MESSAGE
        return known && (looksLikeMessage || snapshot.category == null) || (allowAnyMessagingApp && looksLikeMessage)
    }

    override fun parse(snapshot: NotificationSnapshot): List<ParsedMessage> {
        if (snapshot.isMessagingStyle && snapshot.messages.isNotEmpty()) {
            val group = snapshot.isGroupConversation
            val title = snapshot.conversationTitle?.let(::clean)?.takeIf { it.isNotEmpty() }
            return snapshot.messages
                .filter { !it.isFromSelf }
                .map { m ->
                    val name = m.senderName?.let(::clean)?.takeIf { it.isNotEmpty() }
                        ?: (if (!group) snapshot.title?.let(::clean) else null)
                        ?: title
                        ?: UNKNOWN_SENDER
                    ParsedMessage(
                        senderName = name,
                        senderKey = m.senderKey?.trim()?.takeIf { it.isNotEmpty() && it.length < 512 },
                        text = readable(m.text, snapshot.sensitiveContentHidden),
                        timestamp = m.timestamp,
                        conversationTitle = if (group) title else null,
                        isGroup = group,
                    )
                }
                .sortedBy { it.timestamp }
        }
        // Classic notification: title = sender (or "Sender @ Group"), text/bigText = message.
        val rawTitle = snapshot.title?.let(::clean)?.takeIf { it.isNotEmpty() } ?: return emptyList()
        val body = snapshot.bigText ?: snapshot.text ?: snapshot.textLines.lastOrNull()
        var sender = rawTitle
        var group: String? = null
        GROUP_TITLE.matchEntire(rawTitle)?.let { m -> sender = m.groupValues[1].trim(); group = m.groupValues[2].trim() }
        // "New messages" style summaries are not attributable to one sender.
        if (SUMMARY_TITLE.containsMatchIn(rawTitle) && group == null) return emptyList()
        return listOf(
            ParsedMessage(
                senderName = sender,
                senderKey = null,
                text = readable(body, snapshot.sensitiveContentHidden),
                timestamp = snapshot.postTime,
                conversationTitle = group,
                isGroup = group != null,
            ),
        )
    }

    private fun readable(text: String?, hidden: Boolean): String? {
        if (hidden || text == null) return null
        val t = clean(text)
        if (t.isEmpty() || HIDDEN_PLACEHOLDERS.any { t.equals(it, ignoreCase = true) }) return null
        return if (t.length > MAX_MESSAGE_CHARS) t.substring(0, MAX_MESSAGE_CHARS) else t
    }

    companion object {
        const val CATEGORY_MESSAGE = "msg"
        const val UNKNOWN_SENDER = "Unknown sender"
        const val MAX_MESSAGE_CHARS = 4000
        private val GROUP_TITLE = Regex("^(.+?)\\s+@\\s+(.+)$")
        private val SUMMARY_TITLE = Regex("(?i)^\\d+\\s+(new\\s+)?messages?\\b|^new messages?$")
        private val HIDDEN_PLACEHOLDERS = listOf(
            "Sensitive notification content hidden", "Contents hidden", "New message", "Message hidden",
            "Notification hidden", "Content hidden",
        )

        /** Removes control/bidi characters and collapses whitespace. Input is treated as plain text only. */
        fun clean(s: CharSequence): String {
            val sb = StringBuilder(s.length)
            for (ch in s) {
                if (Character.isISOControl(ch) && ch != '\n') continue
                if (ch in '‪'..'‮' || ch in '⁦'..'⁩' || ch == '￼') continue
                sb.append(if (ch == '\n') ' ' else ch)
            }
            return sb.toString().replace(Regex("\\s+"), " ").trim()
        }
    }
}
