package com.privatevoice.app

import com.privatevoice.app.messaging.InMemoryMessageRepository
import com.privatevoice.app.messaging.NewMessageTracker
import com.privatevoice.app.model.IncomingMessage
import com.privatevoice.app.model.SenderAssignment
import com.privatevoice.app.model.SenderRef
import com.privatevoice.app.model.VoiceCategory
import com.privatevoice.app.voices.AssignmentCodec
import com.privatevoice.app.voices.DefaultVoiceSelectionResolver
import com.privatevoice.app.voices.EncryptedFileAssignmentRepository
import com.privatevoice.app.voices.SecretBox
import com.privatevoice.app.voices.VoiceChoice
import com.privatevoice.app.voices.VoiceDefaults
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class MessagingAndVoicesTest {
    private var now = 0L
    private fun msg(id: Long, text: String? = "hello", key: String = "k$id", at: Long = now) = IncomingMessage(
        id, key, "pkg", "App", SenderRef("Sender$id", null, "pkg"), null, false, text, 0, at,
    )

    // ---- temporary storage
    @Test fun messagesExpireAndAreWiped() {
        val repo = InMemoryMessageRepository(ttlMillis = 60_000, clock = { now })
        val m = msg(1)
        repo.add(m)
        assertEquals(1, repo.state.value.count)
        now = 59_999
        assertEquals(m, repo.latest())
        now = 60_000
        assertNull(repo.latest())
        assertNull(m.text) // wiped
        assertEquals(0, repo.state.value.count)
    }

    @Test fun repositoryIsBounded() {
        val repo = InMemoryMessageRepository(ttlMillis = 600_000, capacity = 3, clock = { now })
        val ms = (1L..5L).map { msg(it) }
        ms.forEach { repo.add(it) }
        assertEquals(listOf(3L, 4L, 5L), repo.all().map { it.id })
        assertNull(ms[0].text)
    }

    @Test fun clearWipesEverything() {
        val repo = InMemoryMessageRepository(clock = { now })
        val ms = (1L..3L).map { msg(it) }
        ms.forEach { repo.add(it) }
        repo.clear()
        assertTrue(repo.all().isEmpty())
        assertTrue(ms.all { it.text == null })
    }

    @Test fun pendingStateNeverContainsText() {
        val repo = InMemoryMessageRepository(clock = { now })
        repo.add(msg(1, text = "secret words"))
        assertFalse(repo.state.value.toString().contains("secret"))
        assertFalse(msg(2, text = "secret words").toString().contains("secret"))
    }

    // ---- duplicate suppression
    @Test fun notificationUpdatesAreNotReadTwice() {
        val t = NewMessageTracker()
        assertTrue(t.isNew("conv1", 100))
        assertFalse(t.isNew("conv1", 100)) // same notification re-posted
        assertFalse(t.isNew("conv1", 90))  // older message in an updated MessagingStyle
        assertTrue(t.isNew("conv1", 200))  // genuinely new message
        assertTrue(t.isNew("conv2", 100))  // different conversation
    }

    @Test fun trackerIsBoundedAndPrimeable() {
        val t = NewMessageTracker(maxKeys = 2)
        t.prime("a", 10)
        assertFalse(t.isNew("a", 10))
        t.isNew("b", 1); t.isNew("c", 1)
        assertEquals(2, t.size())
        assertTrue(t.isNew("a", 10)) // evicted -> treated as unseen (bounded memory)
    }

    // ---- voice selection
    private val defaults = VoiceDefaults("kokoro:af_heart", "kokoro:bm_george", false, "kokoro:am_michael", "kokoro:af_bella")
    private val resolver = DefaultVoiceSelectionResolver()
    private fun asg(id: String, name: String, key: String? = null, app: String? = null, voice: String? = null, cat: VoiceCategory = VoiceCategory.DEFAULT) =
        SenderAssignment(id, name, key, name, app, voice, cat, 0)

    @Test fun exactVoiceBeatsCategoryBeatsDefault() {
        val list = listOf(asg("1", "Sarah", voice = "kokoro:af_nicole", cat = VoiceCategory.MASCULINE), asg("2", "Mike", cat = VoiceCategory.MASCULINE))
        val sarah = resolver.resolve(SenderRef("Sarah", null, "pkg"), list, defaults)
        assertEquals("kokoro:af_nicole", sarah.messageVoice)
        assertEquals(VoiceChoice.Source.SENDER_VOICE, sarah.source)
        val mike = resolver.resolve(SenderRef("Mike", null, "pkg"), list, defaults)
        assertEquals("kokoro:am_michael", mike.messageVoice)
        assertEquals(VoiceChoice.Source.SENDER_CATEGORY, mike.source)
        val stranger = resolver.resolve(SenderRef("Unknown sender", null, "pkg"), list, defaults)
        assertEquals("kokoro:af_heart", stranger.messageVoice)
        assertEquals(VoiceChoice.Source.DEFAULT, stranger.source)
    }

    @Test fun announcerVoiceIsIndependent() {
        val c = resolver.resolve(SenderRef("Sarah", null, "p"), listOf(asg("1", "Sarah", voice = "kokoro:af_sky")), defaults)
        assertEquals("kokoro:bm_george", c.announcerVoice)
        assertEquals("kokoro:af_sky", c.messageVoice)
        val sameAsDefault = resolver.resolve(SenderRef("Sarah", null, "p"), emptyList(), defaults.copy(announcerUsesDefault = true))
        assertEquals("kokoro:af_heart", sameAsDefault.announcerVoice)
    }

    @Test fun keyedAssignmentDoesNotLeakToSameNameStranger() {
        val girlfriend = asg("1", "Sarah", key = "tel:+15550001", app = "msgs", voice = "kokoro:af_heart")
        val otherSarah = SenderRef("Sarah", "tel:+15559999", "msgs")
        assertNull(resolver.match(otherSarah, listOf(girlfriend)))
        assertEquals(girlfriend, resolver.match(SenderRef("Sarah", "tel:+15550001", "msgs"), listOf(girlfriend)))
        // Same key but the display name changed in the contact list: still matches.
        assertEquals(girlfriend, resolver.match(SenderRef("Sarah ❤️", "tel:+15550001", "msgs"), listOf(girlfriend)))
    }

    @Test fun phoneNumbersMatchAcrossFormats() {
        val mom = asg("1", "Mom", key = "tel:+15551234567", voice = "kokoro:af_sarah")
        assertEquals(mom, resolver.match(SenderRef("Mom", "tel:5551234567", "com.google.android.apps.messaging"), listOf(mom)))
        assertEquals(mom, resolver.match(SenderRef("x", "tel:+1 (555) 123-4567", "sms"), listOf(mom)))
        assertNull(resolver.match(SenderRef("x", "tel:+15551234568", "sms"), listOf(mom)))
        assertNull(resolver.match(SenderRef("x", "contact-key-123", "sms"), listOf(mom)))
    }

    @Test fun nameMatchingIsExactAndPrefersSameApp() {
        val any = asg("1", "Alex", voice = "kokoro:am_adam")
        val wa = asg("2", "Alex", app = "com.whatsapp", voice = "kokoro:am_onyx")
        assertEquals(wa, resolver.match(SenderRef("alex ", null, "com.whatsapp"), listOf(any, wa)))
        assertEquals(any, resolver.match(SenderRef("Alex", null, "sms"), listOf(any, wa)))
        assertNull(resolver.match(SenderRef("Alexander", null, "sms"), listOf(any, wa)))
    }

    @Test fun keyedAssignmentMatchesByNameOnlyWhenNotificationHasNoKey() {
        val keyed = asg("1", "Sam", key = "k-sam", app = "app1")
        assertEquals(keyed, resolver.match(SenderRef("Sam", null, "app1"), listOf(keyed)))
        assertNull(resolver.match(SenderRef("Sam", null, "app2"), listOf(keyed)))
    }

    @Test fun matchingIsDeterministic() {
        val a = asg("b-id", "Pat", voice = "v1"); val b = asg("a-id", "Pat", voice = "v2")
        repeat(5) { assertEquals("a-id", resolver.match(SenderRef("Pat", null, "x"), listOf(a, b).shuffled())!!.id) }
    }

    // ---- persistence of assignments (no message content)
    private class XorBox : SecretBox {
        var destroyed = false
        override fun seal(plain: ByteArray) = plain.map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
        override fun open(sealed: ByteArray) = seal(sealed)
        override fun destroyKey() { destroyed = true }
    }

    @Test fun codecRoundTripsUnicodeAndNulls() {
        val list = listOf(asg("1", "Zoë\tTab", key = "tel:+1", app = "a", voice = "kokoro:af_sky", cat = VoiceCategory.FEMININE), asg("2", "Bob"))
        assertEquals(list, AssignmentCodec.decode(AssignmentCodec.encode(list)))
    }

    @Test fun encryptedStorePersistsAndDeletesAll() {
        val dir = Files.createTempDirectory("pv").toFile()
        val f = File(dir, "a.bin")
        val box = XorBox()
        val repo = EncryptedFileAssignmentRepository(f, box)
        repo.upsert(asg("1", "Sarah", voice = "kokoro:af_heart"))
        assertFalse(String(f.readBytes()).contains("Sarah"))
        assertEquals(1, EncryptedFileAssignmentRepository(f, box).assignments.value.size)
        repo.deleteAll()
        assertFalse(f.exists())
        assertTrue(box.destroyed)
        assertTrue(repo.assignments.value.isEmpty())
    }

    @Test fun corruptStoreStartsEmptyInsteadOfCrashing() {
        val f = File(Files.createTempDirectory("pv").toFile(), "a.bin").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        assertTrue(EncryptedFileAssignmentRepository(f, XorBox()).assignments.value.isEmpty())
        assertArrayEquals(byteArrayOf(1, 2, 3), f.readBytes())
    }
}
