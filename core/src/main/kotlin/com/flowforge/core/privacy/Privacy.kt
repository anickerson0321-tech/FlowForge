package com.flowforge.core.privacy

import java.util.Locale
import java.util.concurrent.TimeUnit

/** What a consent covers. */
enum class CloudPurpose(val label: String) {
    RECOGNITION("upload dictated audio for transcription"),
    AI_CLEANUP("send dictated text for AI cleanup"),
}

/**
 * Consent is tied to the exact provider host and to the wording version the user saw.
 * Changing the endpoint host or the notice text invalidates it, so audio is never sent to a
 * provider the user did not approve.
 */
data class ConsentRecord(
    val purpose: CloudPurpose,
    val endpointHost: String,
    val grantedAtMillis: Long,
    val noticeVersion: Int,
)

object ConsentPolicy {
    /** Bump when the cloud notice text changes materially. */
    const val NOTICE_VERSION = 1

    fun isValid(record: ConsentRecord?, purpose: CloudPurpose, endpointUrl: String): Boolean {
        if (record == null || record.purpose != purpose || record.noticeVersion != NOTICE_VERSION) return false
        val host = hostOf(endpointUrl) ?: return false
        return record.endpointHost.equals(host, ignoreCase = true)
    }

    fun grant(purpose: CloudPurpose, endpointUrl: String, nowMillis: Long): ConsentRecord? =
        hostOf(endpointUrl)?.let { ConsentRecord(purpose, it, nowMillis, NOTICE_VERSION) }

    /**
     * Host of an https URL, or null if the URL is not https. Plain http is only accepted for
     * localhost-style development hosts; the app never sends audio unencrypted over the internet.
     */
    fun hostOf(url: String): String? {
        val trimmed = url.trim()
        val m = URL_PATTERN.matchEntire(trimmed) ?: return null
        val scheme = m.groupValues[1].lowercase(Locale.ROOT)
        val host = m.groupValues[2].lowercase(Locale.ROOT)
        if (host.isEmpty()) return null
        return when {
            scheme == "https" -> host
            scheme == "http" && isLocalHost(host) -> host
            else -> null
        }
    }

    /** Loopback, mDNS (.local) and private IPv4 ranges: the only hosts allowed over plain http. */
    fun isLocalHost(host: String): Boolean {
        if (host == "localhost" || host.endsWith(".local")) return true
        val octets = host.split('.')
        if (octets.size != 4) return false
        val n = octets.map { it.toIntOrNull()?.takeIf { v -> v in 0..255 } ?: return false }
        return n[0] == 127 || n[0] == 10 || (n[0] == 192 && n[1] == 168) || (n[0] == 172 && n[1] in 16..31)
    }

    private val URL_PATTERN = Regex("""(?i)(https?)://([^/:?#\s]+)(:\d+)?(/[^\s]*)?""")

    fun noticeText(purpose: CloudPurpose, host: String): String = when (purpose) {
        CloudPurpose.RECOGNITION ->
            "When you dictate with the cloud engine, FlowForge records your voice and uploads the audio to $host " +
                "over an encrypted connection for transcription. $host's own privacy policy and retention rules apply. " +
                "Audio is held in memory only and is not saved on this phone. You can withdraw consent at any time " +
                "in Privacy settings; FlowForge then stops uploading immediately."
        CloudPurpose.AI_CLEANUP ->
            "With AI cleanup enabled, the text you dictate (after FlowForge's on-device cleanup) is sent to $host " +
                "to improve grammar and formatting. The result is checked on your phone: if it changes numbers, dates, " +
                "names or negations, FlowForge discards it and keeps the on-device version."
    }
}

data class PrivacySettings(
    val historyEnabled: Boolean = true,
    val historyRetentionDays: Int = 7,
    val keepOriginalTranscript: Boolean = true,
    /** Skip history entirely in fields that ask for no personalized learning (incognito keyboards). */
    val respectIncognitoFields: Boolean = true,
    val recognitionConsent: ConsentRecord? = null,
    val cleanupConsent: ConsentRecord? = null,
)

object RetentionPolicy {
    val ALLOWED_DAYS = listOf(1, 7, 30, 90)

    fun cutoffMillis(nowMillis: Long, retentionDays: Int): Long = nowMillis - TimeUnit.DAYS.toMillis(retentionDays.toLong())

    fun isExpired(createdAtMillis: Long, nowMillis: Long, retentionDays: Int): Boolean =
        createdAtMillis < cutoffMillis(nowMillis, retentionDays)
}
