package com.privatevoice.app.reader

import com.privatevoice.app.settings.AppSettings
import com.privatevoice.app.settings.ReadContent
import com.privatevoice.app.settings.RouteRestriction

/** Kind of the currently active media output. */
enum class OutputKind { SPEAKER, WIRED_HEADSET, USB_HEADSET, BLE_HEADSET, BLUETOOTH_A2DP, BLUETOOTH_SCO, BLE_SPEAKER, HEARING_AID, OTHER }

/** The active output route: kind plus product name (used for user-approved Bluetooth devices). */
data class AudioRoute(val kind: OutputKind, val name: String?) {
    val isBluetooth: Boolean
        get() = kind == OutputKind.BLUETOOTH_A2DP || kind == OutputKind.BLUETOOTH_SCO || kind == OutputKind.BLE_HEADSET ||
            kind == OutputKind.BLE_SPEAKER || kind == OutputKind.HEARING_AID

    companion object { val SPEAKER = AudioRoute(OutputKind.SPEAKER, null) }
}

object RoutePolicy {
    /**
     * Whether private message audio may play on [route] under [restriction].
     * Bluetooth A2DP devices can be speakers or car systems, so they only count as
     * "headphones" when the user approved that device by name.
     */
    fun allows(route: AudioRoute, restriction: RouteRestriction, approvedBluetooth: Set<String>): Boolean = when (restriction) {
        RouteRestriction.ANY -> true
        RouteRestriction.BLUETOOTH_ONLY -> route.isBluetooth
        RouteRestriction.HEADPHONES_ONLY -> when (route.kind) {
            OutputKind.WIRED_HEADSET, OutputKind.USB_HEADSET, OutputKind.BLE_HEADSET, OutputKind.HEARING_AID -> true
            OutputKind.BLUETOOTH_A2DP, OutputKind.BLUETOOTH_SCO -> route.name != null && route.name in approvedBluetooth
            else -> false
        }
    }

    fun describe(restriction: RouteRestriction): String = when (restriction) {
        RouteRestriction.ANY -> "Any output (including speaker)"
        RouteRestriction.HEADPHONES_ONLY -> "Headphones only"
        RouteRestriction.BLUETOOTH_ONLY -> "Bluetooth audio only"
    }
}

object QuietHours {
    /** True if [minuteOfDay] falls in [start, end), handling windows that wrap past midnight. */
    fun isQuiet(minuteOfDay: Int, start: Int, end: Int): Boolean = when {
        start == end -> false
        start < end -> minuteOfDay in start until end
        else -> minuteOfDay >= start || minuteOfDay < end
    }

    fun format(minutes: Int): String {
        val h = minutes / 60
        val m = minutes % 60
        val h12 = if (h % 12 == 0) 12 else h % 12
        return "%d:%02d %s".format(h12, m, if (h < 12) "AM" else "PM")
    }
}

/** Everything the automatic-reading decision depends on. */
data class AutoReadContext(
    val settings: AppSettings,
    val route: AudioRoute,
    val deviceLocked: Boolean,
    val inCall: Boolean,
    val minuteOfDay: Int,
    val nowMillis: Long,
    val isGroup: Boolean,
    val contentAvailable: Boolean,
)

sealed class AutoReadDecision {
    data class Read(val content: ReadContent) : AutoReadDecision()
    data class Skip(val reason: String) : AutoReadDecision()
}

object AutoReadPolicy {
    fun evaluate(c: AutoReadContext): AutoReadDecision {
        val s = c.settings
        return when {
            !s.autoReadEnabled -> AutoReadDecision.Skip("Automatic reading is off")
            c.nowMillis < s.snoozeUntilMillis -> AutoReadDecision.Skip("Automatic reading is paused")
            s.quietHoursEnabled && QuietHours.isQuiet(c.minuteOfDay, s.quietStartMinutes, s.quietEndMinutes) ->
                AutoReadDecision.Skip("Quiet hours")
            s.pauseDuringCalls && c.inCall -> AutoReadDecision.Skip("Phone call in progress")
            s.autoReadOnlyUnlocked && c.deviceLocked -> AutoReadDecision.Skip("Phone is locked")
            s.suppressGroups && c.isGroup -> AutoReadDecision.Skip("Group message")
            !RoutePolicy.allows(c.route, s.autoReadRoute, s.approvedBluetoothDevices) ->
                AutoReadDecision.Skip("Audio output not allowed: ${RoutePolicy.describe(s.autoReadRoute)}")
            // Without readable text, announce the sender only; never invent content.
            !c.contentAvailable -> AutoReadDecision.Read(ReadContent.SENDER_ONLY)
            else -> AutoReadDecision.Read(s.readContent)
        }
    }
}
