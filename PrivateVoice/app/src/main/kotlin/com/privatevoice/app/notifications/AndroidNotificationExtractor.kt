package com.privatevoice.app.notifications

import android.app.Notification
import android.service.notification.StatusBarNotification

/** Messaging apps PrivateVoice targets by default (others are optional via settings). */
object SupportedApps {
    val DEFAULT: Map<String, String> = linkedMapOf(
        "com.google.android.apps.messaging" to "Google Messages",
        "com.samsung.android.messaging" to "Samsung Messages",
        "com.android.messaging" to "Messaging",
        "com.whatsapp" to "WhatsApp",
        "com.whatsapp.w4b" to "WhatsApp Business",
        "org.thoughtcrime.securesms" to "Signal",
        "org.telegram.messenger" to "Telegram",
        "com.facebook.orca" to "Messenger",
        "com.google.android.apps.dynamite" to "Google Chat",
        "com.discord" to "Discord",
        "com.Slack" to "Slack",
        "com.microsoft.teams" to "Microsoft Teams",
        "com.viber.voip" to "Viber",
        "jp.naver.line.android" to "LINE",
        "com.instagram.android" to "Instagram",
        "com.snapchat.android" to "Snapchat",
        "com.textra" to "Textra",
        "xyz.klinker.messenger" to "Pulse SMS",
        "com.verizon.messaging.vzmsgs" to "Verizon Messages",
    )
}

/**
 * Copies the relevant, plain-text parts of a [StatusBarNotification] into a
 * [NotificationSnapshot]. Only CharSequence/String/long values are read; no
 * custom Parcelables or code from the posting app are deserialized beyond what
 * the framework's own MessagingStyle parser does.
 */
object AndroidNotificationExtractor {
    private const val MESSAGING_TEMPLATE = "android.app.Notification\$MessagingStyle"

    fun extract(sbn: StatusBarNotification): NotificationSnapshot {
        val n = sbn.notification
        val extras = n.extras
        val template = extras.getString(Notification.EXTRA_TEMPLATE)
        val isMessaging = template == MESSAGING_TEMPLATE
        var messages: List<SnapshotMessage> = emptyList()
        var selfName: String? = null
        if (isMessaging) {
            selfName = runCatching {
                extras.getParcelable(Notification.EXTRA_MESSAGING_PERSON, android.app.Person::class.java)?.name?.toString()
            }.getOrNull()
            val bundles = runCatching { extras.getParcelableArray(Notification.EXTRA_MESSAGES, android.os.Parcelable::class.java) }.getOrNull()
            messages = runCatching {
                Notification.MessagingStyle.Message.getMessagesFromBundleArray(bundles).map { m ->
                    val person = m.senderPerson
                    SnapshotMessage(
                        senderName = person?.name?.toString(),
                        senderKey = person?.key ?: person?.uri,
                        text = m.text?.toString(),
                        timestamp = m.timestamp,
                        // MessagingStyle uses a null sender for the device user's own messages.
                        isFromSelf = person == null,
                    )
                }
            }.getOrDefault(emptyList())
        }
        return NotificationSnapshot(
            key = sbn.key,
            packageName = sbn.packageName,
            postTime = sbn.postTime,
            category = n.category,
            isGroupSummary = (n.flags and Notification.FLAG_GROUP_SUMMARY) != 0,
            isOngoing = (n.flags and Notification.FLAG_ONGOING_EVENT) != 0 || (n.flags and Notification.FLAG_FOREGROUND_SERVICE) != 0,
            isMessagingStyle = isMessaging,
            selfName = selfName,
            conversationTitle = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString(),
            isGroupConversation = extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false),
            messages = messages,
            title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
            text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
            bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString(),
            textLines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.map { it.toString() } ?: emptyList(),
            sensitiveContentHidden = false,
        )
    }
}
