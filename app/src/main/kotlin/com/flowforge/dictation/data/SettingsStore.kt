package com.flowforge.dictation.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import com.flowforge.core.cloud.CloudConfig
import com.flowforge.core.privacy.CloudPurpose
import com.flowforge.core.privacy.ConsentRecord
import com.flowforge.core.privacy.PrivacySettings
import com.flowforge.core.settings.DictationSettings

/**
 * Non-sensitive preferences in SharedPreferences. The cloud API key is the only secret and is
 * stored encrypted with [SecureBox]; it is never written in plain text.
 */
class SettingsStore(context: Context, private val box: SecureBox) {
    private val prefs: SharedPreferences = context.getSharedPreferences("flowforge_settings", Context.MODE_PRIVATE)
    private val listeners = LinkedHashSet<() -> Unit>()
    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> listeners.toList().forEach { it() } }

    init {
        prefs.registerOnSharedPreferenceChangeListener(prefListener)
    }

    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }

    // --- Dictation -----------------------------------------------------------------------------

    var dictation: DictationSettings
        get() {
            val d = DictationSettings()
            return DictationSettings(
                cleanupMode = enumOr(prefs.getString(K_MODE, null), d.cleanupMode),
                spokenPunctuation = prefs.getBoolean(K_SPOKEN_PUNCT, d.spokenPunctuation),
                autoEndPunctuation = prefs.getBoolean(K_END_PUNCT, d.autoEndPunctuation),
                formatLists = prefs.getBoolean(K_LISTS, d.formatLists),
                smartSpacing = prefs.getBoolean(K_SPACING, d.smartSpacing),
                engine = enumOr(prefs.getString(K_ENGINE, null), d.engine),
                allowEngineFallback = prefs.getBoolean(K_FALLBACK, d.allowEngineFallback),
                languageTag = prefs.getString(K_LANGUAGE, d.languageTag) ?: "",
                continuous = prefs.getBoolean(K_CONTINUOUS, d.continuous),
                maxRecordingSeconds = prefs.getInt(K_MAX_SECONDS, d.maxRecordingSeconds),
                insertion = enumOr(prefs.getString(K_INSERTION, null), d.insertion),
                haptics = prefs.getBoolean(K_HAPTICS, d.haptics),
                aiCleanupEnabled = prefs.getBoolean(K_AI_CLEANUP, d.aiCleanupEnabled),
            )
        }
        set(v) {
            prefs.edit()
                .putString(K_MODE, v.cleanupMode.name)
                .putBoolean(K_SPOKEN_PUNCT, v.spokenPunctuation)
                .putBoolean(K_END_PUNCT, v.autoEndPunctuation)
                .putBoolean(K_LISTS, v.formatLists)
                .putBoolean(K_SPACING, v.smartSpacing)
                .putString(K_ENGINE, v.engine.name)
                .putBoolean(K_FALLBACK, v.allowEngineFallback)
                .putString(K_LANGUAGE, v.languageTag)
                .putBoolean(K_CONTINUOUS, v.continuous)
                .putInt(K_MAX_SECONDS, v.maxRecordingSeconds)
                .putString(K_INSERTION, v.insertion.name)
                .putBoolean(K_HAPTICS, v.haptics)
                .putBoolean(K_AI_CLEANUP, v.aiCleanupEnabled)
                .apply()
        }

    fun updateDictation(change: (DictationSettings) -> DictationSettings) { dictation = change(dictation) }

    // --- Privacy -------------------------------------------------------------------------------

    var privacy: PrivacySettings
        get() {
            val d = PrivacySettings()
            return PrivacySettings(
                historyEnabled = prefs.getBoolean(K_HISTORY, d.historyEnabled),
                historyRetentionDays = prefs.getInt(K_RETENTION, d.historyRetentionDays),
                keepOriginalTranscript = prefs.getBoolean(K_KEEP_ORIGINAL, d.keepOriginalTranscript),
                respectIncognitoFields = prefs.getBoolean(K_INCOGNITO, d.respectIncognitoFields),
                recognitionConsent = decodeConsent(prefs.getString(K_CONSENT_RECOGNITION, null)),
                cleanupConsent = decodeConsent(prefs.getString(K_CONSENT_CLEANUP, null)),
            )
        }
        set(v) {
            prefs.edit()
                .putBoolean(K_HISTORY, v.historyEnabled)
                .putInt(K_RETENTION, v.historyRetentionDays)
                .putBoolean(K_KEEP_ORIGINAL, v.keepOriginalTranscript)
                .putBoolean(K_INCOGNITO, v.respectIncognitoFields)
                .putString(K_CONSENT_RECOGNITION, encodeConsent(v.recognitionConsent))
                .putString(K_CONSENT_CLEANUP, encodeConsent(v.cleanupConsent))
                .apply()
        }

    fun updatePrivacy(change: (PrivacySettings) -> PrivacySettings) { privacy = change(privacy) }

    // --- Cloud provider (bring your own key) -------------------------------------------------------

    var cloudBaseUrl: String
        get() = prefs.getString(K_CLOUD_URL, "") ?: ""
        set(v) = prefs.edit().putString(K_CLOUD_URL, v.trim()).apply()

    var cloudTranscriptionModel: String
        get() = prefs.getString(K_CLOUD_STT_MODEL, "whisper-1") ?: "whisper-1"
        set(v) = prefs.edit().putString(K_CLOUD_STT_MODEL, v.trim()).apply()

    var cloudChatModel: String
        get() = prefs.getString(K_CLOUD_CHAT_MODEL, "gpt-4o-mini") ?: "gpt-4o-mini"
        set(v) = prefs.edit().putString(K_CLOUD_CHAT_MODEL, v.trim()).apply()

    val hasCloudApiKey: Boolean get() = prefs.contains(K_CLOUD_KEY)

    fun setCloudApiKey(key: String?) {
        if (key.isNullOrBlank()) {
            prefs.edit().remove(K_CLOUD_KEY).apply()
        } else {
            val enc = Base64.encodeToString(box.encryptString(key.trim()), Base64.NO_WRAP)
            prefs.edit().putString(K_CLOUD_KEY, enc).apply()
        }
    }

    /** Decrypted only at the moment a request is made. */
    fun cloudConfig(): CloudConfig? {
        val url = cloudBaseUrl
        val enc = prefs.getString(K_CLOUD_KEY, null) ?: return null
        if (url.isBlank()) return null
        val key = runCatching { box.decryptString(Base64.decode(enc, Base64.NO_WRAP)) }.getOrNull() ?: return null
        return CloudConfig(url, key, cloudTranscriptionModel, cloudChatModel).takeIf { it.isUsable }
    }

    // --- Bubble & app state ----------------------------------------------------------------------

    /** Bubble position as fractions of the usable screen so it survives rotation and fold/unfold. */
    var bubbleX: Float
        get() = prefs.getFloat(K_BUBBLE_X, 1f)
        set(v) = prefs.edit().putFloat(K_BUBBLE_X, v).apply()

    var bubbleY: Float
        get() = prefs.getFloat(K_BUBBLE_Y, 0.45f)
        set(v) = prefs.edit().putFloat(K_BUBBLE_Y, v).apply()

    /** True while the user wants the bubble shown; used to offer a restore after the process died. */
    var bubbleWanted: Boolean
        get() = prefs.getBoolean(K_BUBBLE_WANTED, false)
        set(v) = prefs.edit().putBoolean(K_BUBBLE_WANTED, v).apply()

    var onboardingDone: Boolean
        get() = prefs.getBoolean(K_ONBOARDED, false)
        set(v) = prefs.edit().putBoolean(K_ONBOARDED, v).apply()

    var accessibilityDisclosureAccepted: Boolean
        get() = prefs.getBoolean(K_A11Y_DISCLOSURE, false)
        set(v) = prefs.edit().putBoolean(K_A11Y_DISCLOSURE, v).apply()

    var useDynamicColor: Boolean
        get() = prefs.getBoolean(K_DYNAMIC_COLOR, false)
        set(v) = prefs.edit().putBoolean(K_DYNAMIC_COLOR, v).apply()

    var clearClipboardAfterPaste: Boolean
        get() = prefs.getBoolean(K_CLEAR_CLIP, true)
        set(v) = prefs.edit().putBoolean(K_CLEAR_CLIP, v).apply()

    var blockedPackages: Set<String>
        get() = prefs.getStringSet(K_BLOCKED, emptySet())?.toSet() ?: emptySet()
        set(v) = prefs.edit().putStringSet(K_BLOCKED, v).apply()

    /** Removes every preference including the encrypted API key. */
    fun clearAll() = prefs.edit().clear().apply()

    private fun encodeConsent(c: ConsentRecord?): String? =
        c?.let { "${it.purpose.name}|${it.endpointHost}|${it.grantedAtMillis}|${it.noticeVersion}" }

    private fun decodeConsent(s: String?): ConsentRecord? {
        val parts = s?.split('|') ?: return null
        if (parts.size != 4) return null
        return runCatching {
            ConsentRecord(CloudPurpose.valueOf(parts[0]), parts[1], parts[2].toLong(), parts[3].toInt())
        }.getOrNull()
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        name?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: default

    private companion object {
        const val K_MODE = "cleanup_mode"
        const val K_SPOKEN_PUNCT = "spoken_punctuation"
        const val K_END_PUNCT = "auto_end_punctuation"
        const val K_LISTS = "format_lists"
        const val K_SPACING = "smart_spacing"
        const val K_ENGINE = "engine"
        const val K_FALLBACK = "engine_fallback"
        const val K_LANGUAGE = "language"
        const val K_CONTINUOUS = "continuous"
        const val K_MAX_SECONDS = "max_seconds"
        const val K_INSERTION = "insertion"
        const val K_HAPTICS = "haptics"
        const val K_AI_CLEANUP = "ai_cleanup"
        const val K_HISTORY = "history_enabled"
        const val K_RETENTION = "history_retention_days"
        const val K_KEEP_ORIGINAL = "keep_original"
        const val K_INCOGNITO = "respect_incognito"
        const val K_CONSENT_RECOGNITION = "consent_recognition"
        const val K_CONSENT_CLEANUP = "consent_cleanup"
        const val K_CLOUD_URL = "cloud_url"
        const val K_CLOUD_KEY = "cloud_key_enc"
        const val K_CLOUD_STT_MODEL = "cloud_stt_model"
        const val K_CLOUD_CHAT_MODEL = "cloud_chat_model"
        const val K_BUBBLE_X = "bubble_x"
        const val K_BUBBLE_Y = "bubble_y"
        const val K_BUBBLE_WANTED = "bubble_wanted"
        const val K_ONBOARDED = "onboarding_done"
        const val K_A11Y_DISCLOSURE = "a11y_disclosure"
        const val K_DYNAMIC_COLOR = "dynamic_color"
        const val K_CLEAR_CLIP = "clear_clipboard"
        const val K_BLOCKED = "blocked_packages"
    }
}
