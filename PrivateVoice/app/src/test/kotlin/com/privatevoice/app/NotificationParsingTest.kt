package com.privatevoice.app

import com.privatevoice.app.notifications.MessagingNotificationParser
import com.privatevoice.app.notifications.NotificationSnapshot
import com.privatevoice.app.notifications.SnapshotMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationParsingTest {
    private val parser = MessagingNotificationParser()
    private val apps = setOf("com.google.android.apps.messaging", "com.samsung.android.messaging")

    private fun snap(
        pkg: String = "com.google.android.apps.messaging",
        messaging: Boolean = true,
        group: Boolean = false,
        title: String? = "Sarah",
        conv: String? = null,
        messages: List<SnapshotMessage> = emptyList(),
        text: String? = null,
        category: String? = "msg",
        summary: Boolean = false,
    ) = NotificationSnapshot(
        key = "0|$pkg|1|null|10123", packageName = pkg, postTime = 1000, category = category, isGroupSummary = summary,
        isOngoing = false, isMessagingStyle = messaging, selfName = "Me", conversationTitle = conv, isGroupConversation = group,
        messages = messages, title = title, text = text, bigText = null, textLines = emptyList(), sensitiveContentHidden = false,
    )

    @Test fun messagingStyleIndividual() {
        val s = snap(messages = listOf(SnapshotMessage("Sarah", "tel:+15551234567", "Are we still going out tonight?", 100, false)))
        val p = parser.parse(s).single()
        assertEquals("Sarah", p.senderName)
        assertEquals("tel:+15551234567", p.senderKey)
        assertEquals("Are we still going out tonight?", p.text)
        assertFalse(p.isGroup)
        assertNull(p.conversationTitle)
    }

    @Test fun groupMessagesUseIndividualSenderAndGroupTitle() {
        val s = snap(group = true, conv = "Family", title = "Family", messages = listOf(
            SnapshotMessage("Mom", "key-mom", "Dinner at 6", 100, false),
            SnapshotMessage("Dad", "key-dad", "I'll bring dessert", 200, false),
        ))
        val ps = parser.parse(s)
        assertEquals(listOf("Mom", "Dad"), ps.map { it.senderName })
        assertTrue(ps.all { it.isGroup && it.conversationTitle == "Family" })
    }

    @Test fun multipleMessagesSortedAndOwnMessagesExcluded() {
        val s = snap(messages = listOf(
            SnapshotMessage("Sarah", "k", "second", 200, false),
            SnapshotMessage(null, null, "my reply", 150, true),
            SnapshotMessage("Sarah", "k", "first", 100, false),
        ))
        assertEquals(listOf("first", "second"), parser.parse(s).map { it.text })
    }

    @Test fun hiddenContentIsNotInvented() {
        val s = snap(messages = listOf(SnapshotMessage("Sarah", "k", "Sensitive notification content hidden", 100, false)))
        val p = parser.parse(s).single()
        assertNull(p.text)
        assertEquals("Sarah", p.senderName)
    }

    @Test fun missingSenderNameFallsBackSafely() {
        val s = snap(title = null, messages = listOf(SnapshotMessage(null, null, "hi", 100, false).copy(isFromSelf = false)))
        assertEquals(MessagingNotificationParser.UNKNOWN_SENDER, parser.parse(s).single().senderName)
    }

    @Test fun classicNotificationAndGroupTitle() {
        val s = snap(messaging = false, title = "Sam @ Soccer team", text = "Practice moved to 5")
        val p = parser.parse(s).single()
        assertEquals("Sam", p.senderName)
        assertEquals("Soccer team", p.conversationTitle)
        assertTrue(p.isGroup)
        assertNull(p.senderKey)
    }

    @Test fun summariesAndOngoingAreIgnored() {
        assertFalse(parser.isCandidate(snap(summary = true), apps, true))
        assertTrue(parser.parse(snap(messaging = false, title = "3 new messages", text = "x")).isEmpty())
    }

    @Test fun candidateRules() {
        assertTrue(parser.isCandidate(snap(), apps, false))
        assertFalse(parser.isCandidate(snap(pkg = "com.example.game", messaging = false, category = "promo"), apps, true))
        assertTrue(parser.isCandidate(snap(pkg = "com.example.chat", messaging = true), apps, true))
        assertFalse(parser.isCandidate(snap(pkg = "com.example.chat", messaging = true), apps, false))
    }

    @Test fun controlAndBidiCharactersAreStripped() {
        assertEquals("hello world", MessagingNotificationParser.clean("hello‮\u0000 \nworld"))
    }

    @Test fun veryLongMessagesAreBounded() {
        val long = "a".repeat(10_000)
        val p = parser.parse(snap(messages = listOf(SnapshotMessage("S", null, long, 1, false)))).single()
        assertEquals(MessagingNotificationParser.MAX_MESSAGE_CHARS, p.text!!.length)
    }
}
