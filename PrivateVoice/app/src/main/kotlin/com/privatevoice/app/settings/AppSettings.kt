package com.privatevoice.app.settings

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Which audio outputs automatic reading may use. */
enum class RouteRestriction {
    /** Any output, including the phone speaker. */
    ANY,
    /** Wired/USB headsets, BLE headsets, and Bluetooth devices the user explicitly approved. */
    HEADPHONES_ONLY,
    /** Any Bluetooth audio output (headphones or car). */
    BLUETOOTH_ONLY,
}

enum class ReadContent { SENDER_AND_MESSAGE, SENDER_ONLY }

/** All user preferences. Contains no message content. */
data class AppSettings(
    // automatic reading
    val autoReadEnabled: Boolean = false,
    val autoReadRoute: RouteRestriction = RouteRestriction.HEADPHONES_ONLY,
    val autoReadOnlyUnlocked: Boolean = false,
    val readContent: ReadContent = ReadContent.SENDER_AND_MESSAGE,
    val suppressGroups: Boolean = false,
    val quietHoursEnabled: Boolean = false,
    val quietStartMinutes: Int = 22 * 60,
    val quietEndMinutes: Int = 7 * 60,
    val pauseDuringCalls: Boolean = true,
    val stopOnDisconnect: Boolean = true,
    val snoozeUntilMillis: Long = 0,
    // manual reading
    val manualRoute: RouteRestriction = RouteRestriction.ANY,
    val pendingTtlMinutes: Int = 60,
    val showReadNotification: Boolean = true,
    // voices
    val defaultVoice: String = "kokoro:af_heart",
    val announcerVoice: String = "kokoro:af_heart",
    val masculineVoice: String = "kokoro:am_michael",
    val feminineVoice: String = "kokoro:af_bella",
    val speechRate: Float = 1.0f,
    val volume: Float = 1.0f,
    val announceSender: Boolean = true,
    val announcerUsesDefaultVoice: Boolean = true,
    val repeatSenderName: Boolean = false,
    val readGroupName: Boolean = true,
    val describeEmoji: Boolean = true,
    val expandShorthand: Boolean = true,
    val preferSystemEngine: Boolean = false,
    // floating button
    val floatingEnabled: Boolean = false,
    val floatingAlpha: Float = 0.85f,
    val floatingHideWhenEmpty: Boolean = false,
    val floatingHideOnLockScreen: Boolean = true,
    val floatingX: Float = 0.92f,
    val floatingY: Float = 0.6f,
    // devices the user approved as private
    val approvedBluetoothDevices: Set<String> = emptySet(),
    val extraApps: Set<String> = emptySet(),
    val readAnyMessagingApp: Boolean = true,
    val onboardingDone: Boolean = false,
    // opt-in: read the SMS inbox on demand (READ_SMS)
    val smsHistoryEnabled: Boolean = false,
    /** Forget a message as soon as its notification is dismissed or opened (off: keep until it expires). */
    val forgetOnDismiss: Boolean = false,
)

/** SharedPreferences-backed settings (excluded from backups via the manifest). */
class SettingsRepository(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<AppSettings> = _settings
    val current: AppSettings get() = _settings.value

    @Synchronized
    fun update(transform: (AppSettings) -> AppSettings) {
        val next = transform(_settings.value)
        if (next == _settings.value) return
        save(next)
        _settings.value = next
    }

    private fun load(): AppSettings {
        val d = AppSettings()
        val p = prefs
        return AppSettings(
            autoReadEnabled = p.getBoolean("autoReadEnabled", d.autoReadEnabled),
            autoReadRoute = enumOr(p.getString("autoReadRoute", null), d.autoReadRoute),
            autoReadOnlyUnlocked = p.getBoolean("autoReadOnlyUnlocked", d.autoReadOnlyUnlocked),
            readContent = enumOr(p.getString("readContent", null), d.readContent),
            suppressGroups = p.getBoolean("suppressGroups", d.suppressGroups),
            quietHoursEnabled = p.getBoolean("quietHoursEnabled", d.quietHoursEnabled),
            quietStartMinutes = p.getInt("quietStartMinutes", d.quietStartMinutes),
            quietEndMinutes = p.getInt("quietEndMinutes", d.quietEndMinutes),
            pauseDuringCalls = p.getBoolean("pauseDuringCalls", d.pauseDuringCalls),
            stopOnDisconnect = p.getBoolean("stopOnDisconnect", d.stopOnDisconnect),
            snoozeUntilMillis = p.getLong("snoozeUntilMillis", d.snoozeUntilMillis),
            manualRoute = enumOr(p.getString("manualRoute", null), d.manualRoute),
            pendingTtlMinutes = p.getInt("pendingTtlMinutes", d.pendingTtlMinutes),
            showReadNotification = p.getBoolean("showReadNotification", d.showReadNotification),
            defaultVoice = p.getString("defaultVoice", null) ?: d.defaultVoice,
            announcerVoice = p.getString("announcerVoice", null) ?: d.announcerVoice,
            masculineVoice = p.getString("masculineVoice", null) ?: d.masculineVoice,
            feminineVoice = p.getString("feminineVoice", null) ?: d.feminineVoice,
            speechRate = p.getFloat("speechRate", d.speechRate),
            volume = p.getFloat("volume", d.volume),
            announceSender = p.getBoolean("announceSender", d.announceSender),
            announcerUsesDefaultVoice = p.getBoolean("announcerUsesDefaultVoice", d.announcerUsesDefaultVoice),
            repeatSenderName = p.getBoolean("repeatSenderName", d.repeatSenderName),
            readGroupName = p.getBoolean("readGroupName", d.readGroupName),
            describeEmoji = p.getBoolean("describeEmoji", d.describeEmoji),
            expandShorthand = p.getBoolean("expandShorthand", d.expandShorthand),
            preferSystemEngine = p.getBoolean("preferSystemEngine", d.preferSystemEngine),
            floatingEnabled = p.getBoolean("floatingEnabled", d.floatingEnabled),
            floatingAlpha = p.getFloat("floatingAlpha", d.floatingAlpha),
            floatingHideWhenEmpty = p.getBoolean("floatingHideWhenEmpty2", d.floatingHideWhenEmpty),
            floatingHideOnLockScreen = p.getBoolean("floatingHideOnLockScreen", d.floatingHideOnLockScreen),
            floatingX = p.getFloat("floatingX", d.floatingX),
            floatingY = p.getFloat("floatingY", d.floatingY),
            approvedBluetoothDevices = p.getStringSet("approvedBluetoothDevices", null)?.toSet() ?: d.approvedBluetoothDevices,
            extraApps = p.getStringSet("extraApps", null)?.toSet() ?: d.extraApps,
            readAnyMessagingApp = p.getBoolean("readAnyMessagingApp", d.readAnyMessagingApp),
            onboardingDone = p.getBoolean("onboardingDone", d.onboardingDone),
            smsHistoryEnabled = p.getBoolean("smsHistoryEnabled", d.smsHistoryEnabled),
            forgetOnDismiss = p.getBoolean("forgetOnDismiss", d.forgetOnDismiss),
        )
    }

    private fun save(s: AppSettings) {
        prefs.edit()
            .putBoolean("autoReadEnabled", s.autoReadEnabled)
            .putString("autoReadRoute", s.autoReadRoute.name)
            .putBoolean("autoReadOnlyUnlocked", s.autoReadOnlyUnlocked)
            .putString("readContent", s.readContent.name)
            .putBoolean("suppressGroups", s.suppressGroups)
            .putBoolean("quietHoursEnabled", s.quietHoursEnabled)
            .putInt("quietStartMinutes", s.quietStartMinutes)
            .putInt("quietEndMinutes", s.quietEndMinutes)
            .putBoolean("pauseDuringCalls", s.pauseDuringCalls)
            .putBoolean("stopOnDisconnect", s.stopOnDisconnect)
            .putLong("snoozeUntilMillis", s.snoozeUntilMillis)
            .putString("manualRoute", s.manualRoute.name)
            .putInt("pendingTtlMinutes", s.pendingTtlMinutes)
            .putBoolean("showReadNotification", s.showReadNotification)
            .putString("defaultVoice", s.defaultVoice)
            .putString("announcerVoice", s.announcerVoice)
            .putString("masculineVoice", s.masculineVoice)
            .putString("feminineVoice", s.feminineVoice)
            .putFloat("speechRate", s.speechRate)
            .putFloat("volume", s.volume)
            .putBoolean("announceSender", s.announceSender)
            .putBoolean("announcerUsesDefaultVoice", s.announcerUsesDefaultVoice)
            .putBoolean("repeatSenderName", s.repeatSenderName)
            .putBoolean("readGroupName", s.readGroupName)
            .putBoolean("describeEmoji", s.describeEmoji)
            .putBoolean("expandShorthand", s.expandShorthand)
            .putBoolean("preferSystemEngine", s.preferSystemEngine)
            .putBoolean("floatingEnabled", s.floatingEnabled)
            .putFloat("floatingAlpha", s.floatingAlpha)
            .putBoolean("floatingHideWhenEmpty2", s.floatingHideWhenEmpty)
            .putBoolean("floatingHideOnLockScreen", s.floatingHideOnLockScreen)
            .putFloat("floatingX", s.floatingX)
            .putFloat("floatingY", s.floatingY)
            .putStringSet("approvedBluetoothDevices", s.approvedBluetoothDevices)
            .putStringSet("extraApps", s.extraApps)
            .putBoolean("readAnyMessagingApp", s.readAnyMessagingApp)
            .putBoolean("onboardingDone", s.onboardingDone)
            .putBoolean("smsHistoryEnabled", s.smsHistoryEnabled)
            .putBoolean("forgetOnDismiss", s.forgetOnDismiss)
            .apply()
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        name?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: default
}
