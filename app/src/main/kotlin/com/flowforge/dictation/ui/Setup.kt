package com.flowforge.dictation.ui

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.view.inputmethod.InputMethodManager
import com.flowforge.dictation.accessibility.FlowForgeAccessibilityService
import com.flowforge.dictation.ime.FlowForgeKeyboardService

/** Read-only checks of what the user has enabled, plus intents to the right system screens. */
object Setup {
    fun hasMic(c: Context) = c.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    fun hasNotifications(c: Context) = c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    fun canOverlay(c: Context) = Settings.canDrawOverlays(c)

    fun accessibilityEnabled(c: Context): Boolean {
        val am = c.getSystemService(AccessibilityManager::class.java) ?: return false
        val ours = ComponentName(c, FlowForgeAccessibilityService::class.java)
        return am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any {
            val si = it.resolveInfo?.serviceInfo
            si != null && si.packageName == ours.packageName && si.name == ours.className
        }
    }

    fun keyboardEnabled(c: Context): Boolean {
        val imm = c.getSystemService(InputMethodManager::class.java) ?: return false
        val ours = ComponentName(c, FlowForgeKeyboardService::class.java)
        return imm.enabledInputMethodList.any { it.component == ours }
    }

    fun keyboardSelected(c: Context): Boolean {
        val current = Settings.Secure.getString(c.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD) ?: return false
        return ComponentName.unflattenFromString(current) == ComponentName(c, FlowForgeKeyboardService::class.java)
    }

    fun overlaySettings(c: Context) = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${c.packageName}"))
    fun accessibilitySettings() = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
    fun keyboardSettings() = Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)
    fun appDetails(c: Context) = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${c.packageName}"))
    fun notificationSettings(c: Context) = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, c.packageName)
    fun voiceInputSettings() = Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)
}
