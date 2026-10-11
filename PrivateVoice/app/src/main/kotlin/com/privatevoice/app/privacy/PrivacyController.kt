package com.privatevoice.app.privacy

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.PowerManager
import android.provider.Settings
import com.privatevoice.app.notifications.PrivateVoiceListenerService
import com.privatevoice.app.reader.ReaderController
import com.privatevoice.app.voices.VoiceAssignmentRepository

/** Facts shown in the Privacy Center. */
data class PrivacyStatus(
    val hasInternetPermission: Boolean,
    val notificationAccess: Boolean,
    val overlayPermission: Boolean,
    val postNotifications: Boolean,
    val batteryUnrestricted: Boolean,
    val savedAssignments: Int,
    val pendingMessages: Int,
)

interface PrivacyController {
    fun status(): PrivacyStatus
    /** Clears pending messages, playback buffers and the Repeat cache. */
    fun clearTemporaryContent()
    /** Deletes every saved sender-to-voice assignment and destroys their encryption key. */
    fun deleteAllAssignments()
}

class DefaultPrivacyController(
    private val context: Context,
    private val reader: ReaderController,
    private val assignments: VoiceAssignmentRepository,
    private val pendingCount: () -> Int,
) : PrivacyController {

    override fun status(): PrivacyStatus {
        val pm = context.packageManager
        val requested = try {
            pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()))
                .requestedPermissions?.toSet() ?: emptySet()
        } catch (e: Exception) { emptySet() }
        return PrivacyStatus(
            hasInternetPermission = Manifest.permission.INTERNET in requested,
            notificationAccess = PrivateVoiceListenerService.isEnabled(context),
            overlayPermission = Settings.canDrawOverlays(context),
            postNotifications = context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
            batteryUnrestricted = context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName),
            savedAssignments = assignments.assignments.value.size,
            pendingMessages = pendingCount(),
        )
    }

    override fun clearTemporaryContent() = reader.clearTemporary()

    override fun deleteAllAssignments() = assignments.deleteAll()
}
