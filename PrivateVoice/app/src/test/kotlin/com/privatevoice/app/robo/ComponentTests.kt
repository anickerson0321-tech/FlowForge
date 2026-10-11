package com.privatevoice.app.robo

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.app.Person
import android.content.Intent
import android.os.Looper
import android.os.Process
import android.service.notification.StatusBarNotification
import android.view.WindowManager
import com.privatevoice.app.PrivateVoiceApp
import com.privatevoice.app.audio.PlaybackState
import com.privatevoice.app.model.EngineKind
import com.privatevoice.app.notifications.ControlsNotifier
import com.privatevoice.app.notifications.PrivateVoiceListenerService
import com.privatevoice.app.notifications.ReaderActionReceiver
import com.privatevoice.app.settings.SettingsRepository
import com.privatevoice.app.speech.EngineState
import com.privatevoice.app.tile.ReadLatestTileService
import com.privatevoice.app.ui.MainActivity
import com.privatevoice.app.ui.Screen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSettings

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp")
class ComponentTests {
    private lateinit var app: Application
    private val graph get() = PrivateVoiceApp.graph(app)

    @Before fun setUp() {
        app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private var nextId = 1
    private fun messagingNotification(
        pkg: String = "com.google.android.apps.messaging",
        group: String? = null,
        vararg msgs: Triple<Person?, String, Long>,
    ): StatusBarNotification {
        val me = Person.Builder().setName("Me").build()
        val style = Notification.MessagingStyle(me)
        if (group != null) { style.conversationTitle = group; style.isGroupConversation = true }
        msgs.forEach { (p, t, ts) -> style.addMessage(Notification.MessagingStyle.Message(t, ts, p)) }
        val n = Notification.Builder(app, "x").setSmallIcon(android.R.drawable.ic_dialog_info)
            .setStyle(style).setCategory(Notification.CATEGORY_MESSAGE).build()
        val id = nextId++
        return StatusBarNotification(pkg, pkg, id, "conv", 10123, 0, n, Process.myUserHandle(), null, 1000L)
    }

    private fun person(name: String, key: String) = Person.Builder().setName(name).setKey(key).build()

    private fun listener(): PrivateVoiceListenerService {
        val c = Robolectric.buildService(PrivateVoiceListenerService::class.java).create()
        return c.get()
    }

    // ------------------------------------------------------------- notification listener
    @Test fun realMessagingStyleNotificationIsParsedIntoMemory() {
        val svc = listener()
        svc.onNotificationPosted(messagingNotification(msgs = arrayOf(Triple(person("Sarah", "tel:+15550001"), "Hey, are we still going out tonight?", 100L))))
        val m = graph.repository.latest()
        assertNotNull(m)
        assertEquals("Sarah", m!!.sender.displayName)
        assertEquals("tel:+15550001", m.sender.stableKey)
        assertEquals("Hey, are we still going out tonight?", m.text)
        assertEquals("Google Messages", m.appLabel)
    }

    @Test fun groupConversationsMultipleMessagesAndOwnRepliesHandled() {
        val svc = listener()
        svc.onNotificationPosted(
            messagingNotification(
                group = "Family",
                msgs = arrayOf(
                    Triple(person("Mom", "k-mom"), "Dinner at 6", 100L),
                    Triple(null, "my own reply", 150L),
                    Triple(person("Dad", "k-dad"), "Bringing dessert", 200L),
                ),
            ),
        )
        val all = graph.repository.all()
        assertEquals(listOf("Mom", "Dad"), all.map { it.sender.displayName })
        assertTrue(all.all { it.isGroup && it.conversationTitle == "Family" })
        assertFalse(all.any { it.text == "my own reply" })
    }

    @Test fun repostedAndUpdatedNotificationsAreDeduplicated() {
        val svc = listener()
        val p = person("Sarah", "k")
        val sbn = messagingNotification(msgs = arrayOf(Triple(p, "one", 100L)))
        svc.onNotificationPosted(sbn)
        svc.onNotificationPosted(sbn)
        assertEquals(1, graph.repository.all().size)
        // Same conversation key, updated with an extra message.
        val n2 = messagingNotification(msgs = arrayOf(Triple(p, "one", 100L), Triple(p, "two", 200L)))
        val updated = StatusBarNotification(sbn.packageName, sbn.packageName, sbn.id, sbn.tag, 10123, 0, n2.notification, Process.myUserHandle(), null, 2000L)
        svc.onNotificationPosted(updated)
        assertEquals(listOf("one", "two"), graph.repository.all().map { it.text })
    }

    @Test fun nonMessagingAndOwnNotificationsAreIgnored() {
        val svc = listener()
        val n = Notification.Builder(app, "x").setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("Sale!").setContentText("50% off").build()
        svc.onNotificationPosted(StatusBarNotification("com.shop", "com.shop", 1, null, 1, 0, n, Process.myUserHandle(), null, 1L))
        svc.onNotificationPosted(messagingNotification(pkg = app.packageName, msgs = arrayOf(Triple(person("X", "x"), "loop", 1L))))
        assertEquals(0, graph.repository.all().size)
    }

    @Test fun listenerDisconnectClearsTemporaryContent() {
        val svc = listener()
        svc.onNotificationPosted(messagingNotification(msgs = arrayOf(Triple(person("Sarah", "k"), "secret", 100L))))
        val m = graph.repository.latest()!!
        svc.onListenerDisconnected()
        assertEquals(0, graph.repository.all().size)
        assertNull(m.text)
    }

    // ------------------------------------------------------------- controls notification
    @Test fun controlsNotificationNeverContainsMessageText() {
        val svc = listener()
        svc.onNotificationPosted(messagingNotification(msgs = arrayOf(Triple(person("Sarah", "k"), "TOP SECRET PLANS", 100L))))
        idle()
        val nm = app.getSystemService(NotificationManager::class.java)
        val posted = shadowOf(nm).allNotifications.single()
        val extras = posted.extras
        val all = listOfNotNull(extras.getCharSequence(Notification.EXTRA_TITLE), extras.getCharSequence(Notification.EXTRA_TEXT)).joinToString(" ")
        assertTrue(all.contains("Sarah"))
        assertFalse(all.contains("SECRET"))
        assertEquals(Notification.VISIBILITY_PRIVATE, posted.visibility)
        val pub = posted.publicVersion!!.extras.getCharSequence(Notification.EXTRA_TITLE).toString()
        assertFalse(pub.contains("Sarah"))
        assertTrue(posted.actions.map { it.title.toString() }.containsAll(listOf("Read aloud", "Repeat", "Dismiss")))
    }

    @Test fun notificationPermissionDeniedDoesNotCrashOrPost() {
        shadowOf(app.getSystemService(NotificationManager::class.java)).setNotificationsEnabled(false)
        listener().onNotificationPosted(messagingNotification(msgs = arrayOf(Triple(person("Sarah", "k"), "hi", 100L))))
        idle()
        assertTrue(shadowOf(app.getSystemService(NotificationManager::class.java)).allNotifications.isEmpty())
        assertEquals(1, graph.repository.all().size) // still readable from the app / tile / floating button
    }

    @Test fun dismissActionClearsPendingMessages() {
        listener().onNotificationPosted(messagingNotification(msgs = arrayOf(Triple(person("Sarah", "k"), "hi", 100L))))
        ReaderActionReceiver().onReceive(app, Intent(app, ReaderActionReceiver::class.java).setAction(ControlsNotifier.ACTION_CLEAR))
        assertEquals(0, graph.repository.all().size)
    }

    // ------------------------------------------------------------- reading + model failure fallback
    @Test fun readLatestFallsBackToSystemVoiceWhenNeuralModelCannotLoad() {
        // Robolectric's asset manager has no model file, so Kokoro initialization fails: the
        // reader must report it and continue with the offline system engine instead of crashing.
        listener().onNotificationPosted(messagingNotification(msgs = arrayOf(Triple(person("Sarah", "k"), "See you soon", 100L))))
        graph.reader.readLatest()
        for (i in 0 until 200) {
            idle()
            Thread.sleep(25)
            if (graph.kokoro.state.value is EngineState.Failed && graph.player.state.value is PlaybackState.Speaking) break
        }
        assertTrue("engine state was ${graph.kokoro.state.value}", graph.kokoro.state.value is EngineState.Failed)
        val st = graph.player.state.value
        assertTrue("player state was $st", st is PlaybackState.Speaking && st.engine == EngineKind.SYSTEM)
        graph.reader.stop()
        assertEquals(PlaybackState.Idle, graph.player.state.value)
    }

    // ------------------------------------------------------------- tile
    @Test fun quickSettingsTileReadsLatest() {
        listener().onNotificationPosted(messagingNotification(msgs = arrayOf(Triple(person("Sarah", "k"), "hi", 100L))))
        val tile = Robolectric.buildService(ReadLatestTileService::class.java).create().get()
        tile.onClick()
        assertEquals(0, graph.repository.state.value.count) // read: no longer new
        assertTrue(graph.repository.all().single().heard)  // but still available to hear again
        graph.reader.stop()
    }

    // ------------------------------------------------------------- settings
    @Test fun settingsPersistWithoutMessageContent() {
        graph.settings.update { it.copy(autoReadEnabled = true, speechRate = 1.25f, approvedBluetoothDevices = setOf("Buds")) }
        val reloaded = SettingsRepository(app).current
        assertTrue(reloaded.autoReadEnabled)
        assertEquals(1.25f, reloaded.speechRate)
        assertEquals(setOf("Buds"), reloaded.approvedBluetoothDevices)
        val prefs = app.getSharedPreferences("settings", 0).all.values.joinToString()
        assertFalse(prefs.contains("hello"))
    }

    @Test fun privacyStatusReportsNoInternetPermission() {
        val st = graph.privacy.status()
        assertFalse(st.hasInternetPermission)
        assertFalse(st.notificationAccess)
    }

    // ------------------------------------------------------------- overlay
    @Test fun floatingButtonLifecycleWithoutDuplicates() {
        ShadowSettings.setCanDrawOverlays(true)
        listener().onNotificationPosted(messagingNotification(msgs = arrayOf(Triple(person("Sarah", "k"), "hi", 100L))))
        graph.settings.update { it.copy(floatingEnabled = true, floatingHideOnLockScreen = false) }
        idle()
        assertTrue(graph.overlay.isShowing)
        // Rotation / fold: reposition must not add another window.
        graph.overlay.reposition(); graph.overlay.refresh(); idle()
        val wm = app.getSystemService(WindowManager::class.java)
        assertTrue(graph.overlay.isShowing)
        graph.settings.update { it.copy(floatingEnabled = false) }
        idle()
        assertFalse(graph.overlay.isShowing)
        assertNotNull(wm)
    }

    @Test fun floatingButtonShowsWhenEnabledEvenWithNothingWaiting() {
        ShadowSettings.setCanDrawOverlays(true)
        graph.settings.update { it.copy(floatingEnabled = true, floatingHideOnLockScreen = false) }
        idle()
        assertTrue(graph.overlay.isShowing)
        graph.settings.update { it.copy(floatingEnabled = false) }
        idle()
    }

    @Test fun textMessagesScreenAsksBeforeReadingSms() {
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        a.navigate(Screen.SMS)
        idle()
        val texts = a.window.decorView.allTexts()
        assertTrue(texts.contains("Allow SMS access"))
        assertFalse(graph.settings.current.smsHistoryEnabled)
    }

    @Test fun dashboardOffersTestMessageAndDiagnostics() {
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val texts = a.window.decorView.allTexts()
        assertTrue(texts.contains("▶  Play a test message"))
        assertTrue(texts.contains("Last message seen"))
        listener().onNotificationPosted(messagingNotification(msgs = arrayOf(Triple(person("Sarah", "k"), "hi", 100L))))
        assertEquals("Google Messages", graph.diagnostics.value.lastMessageApp)
    }

    @Test fun floatingButtonHiddenWhenNothingWaiting() {
        ShadowSettings.setCanDrawOverlays(true)
        graph.settings.update { it.copy(floatingEnabled = true, floatingHideWhenEmpty = true, floatingHideOnLockScreen = false) }
        idle()
        assertFalse(graph.overlay.isShowing)
    }

    // ------------------------------------------------------------- UI on cover and inner screen
    private fun allScreensRender(): Map<Screen, List<String>> {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val a = controller.get()
        val out = LinkedHashMap<Screen, List<String>>()
        for (s in Screen.entries) {
            a.navigate(s)
            idle()
            out[s] = a.window.decorView.allTexts()
        }
        return out
    }

    @Test fun everyScreenRendersOnCoverScreen() {
        val texts = allScreensRender()
        assertTrue(texts.getValue(Screen.VOICES).contains("Load now"))
        assertTrue(texts.getValue(Screen.VOICES).contains("Heart"))
        assertTrue(texts.getValue(Screen.PEOPLE).contains("+  Add sender"))
        assertTrue(texts.getValue(Screen.AUTO).any { it.startsWith("Start ") })
        assertTrue(texts.getValue(Screen.PRIVACY).contains("Clear temporary content now"))
        assertTrue(texts.getValue(Screen.PRIVACY).contains("Manage"))
        assertTrue(texts.getValue(Screen.CONTROLS).contains("Show floating read button"))
        assertTrue(texts.getValue(Screen.ABOUT).any { it.startsWith("PrivateVoice 1.0.0") })
        // phone layout: no navigation rail
        assertFalse(texts.getValue(Screen.VOICES).contains("Private · Offline"))
    }

    @Test @Config(qualifiers = "w841dp-h701dp") fun innerScreenUsesNavigationRail() {
        val texts = allScreensRender()
        assertTrue(texts.getValue(Screen.DASHBOARD).contains("Private · Offline"))
        assertTrue(texts.getValue(Screen.DASHBOARD).contains("Voice Studio"))
    }

    @Test fun foldUnfoldKeepsCurrentScreen() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val a = controller.get()
        a.navigate(Screen.PRIVACY)
        RuntimeEnvironment.setQualifiers("w841dp-h701dp")
        controller.configurationChange(app.resources.configuration)
        idle()
        assertEquals(Screen.PRIVACY, a.screen)
        assertTrue(a.window.decorView.allTexts().contains("Private · Offline"))
        RuntimeEnvironment.setQualifiers("w411dp-h891dp")
        controller.configurationChange(app.resources.configuration)
        idle()
        assertEquals(Screen.PRIVACY, a.screen)
        assertFalse(a.window.decorView.allTexts().contains("Private · Offline"))
    }

    @Test fun onboardingShownWithoutNotificationAccess() {
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        assertTrue(a.window.decorView.allTexts().contains("Allow notification access"))
    }
}
