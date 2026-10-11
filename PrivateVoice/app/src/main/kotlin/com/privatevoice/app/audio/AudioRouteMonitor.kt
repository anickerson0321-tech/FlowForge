package com.privatevoice.app.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.privatevoice.app.reader.AudioRoute
import com.privatevoice.app.reader.OutputKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Tracks the active media output (speaker, wired, Bluetooth...), phone-call
 * state and "becoming noisy" events (headphones unplugged / BT disconnected).
 */
class AudioRouteMonitor(context: Context, private val attributes: AudioAttributes) {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val _route = MutableStateFlow(computeRoute())
    val route: StateFlow<AudioRoute> = _route
    private val _inCall = MutableStateFlow(isCallMode(audioManager.mode))
    val inCall: StateFlow<Boolean> = _inCall

    /** Invoked when the output that was in use goes away (unplugged / disconnected). */
    var onOutputLost: (() -> Unit)? = null
    var onRouteChanged: ((AudioRoute) -> Unit)? = null
    var onCallStateChanged: ((Boolean) -> Unit)? = null

    private var started = false

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = refresh()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = refresh()
    }

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) onOutputLost?.invoke()
        }
    }

    private val modeListener = AudioManager.OnModeChangedListener { mode ->
        val call = isCallMode(mode)
        if (call != _inCall.value) { _inCall.value = call; onCallStateChanged?.invoke(call) }
    }

    fun start() {
        if (started) return
        started = true
        audioManager.registerAudioDeviceCallback(deviceCallback, handler)
        val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        if (Build.VERSION.SDK_INT >= 33) appContext.registerReceiver(noisyReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else appContext.registerReceiver(noisyReceiver, filter)
        audioManager.addOnModeChangedListener(appContext.mainExecutor, modeListener)
        refresh()
    }

    fun refresh() {
        val previous = _route.value
        val now = computeRoute()
        _inCall.value = isCallMode(audioManager.mode)
        if (now != previous) {
            _route.value = now
            onRouteChanged?.invoke(now)
        }
    }

    fun currentRoute(): AudioRoute { refresh(); return _route.value }

    private fun computeRoute(): AudioRoute {
        val device: AudioDeviceInfo? = if (Build.VERSION.SDK_INT >= 33) {
            try { audioManager.getAudioDevicesForAttributes(attributes).firstOrNull() } catch (e: Exception) { null }
        } else null
        val dev = device ?: audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .sortedBy { priority(it.type) }.firstOrNull()
        return dev?.let { AudioRoute(kindOf(it.type), it.productName?.toString()?.takeIf { n -> n.isNotBlank() }) } ?: AudioRoute.SPEAKER
    }

    private fun priority(type: Int) = when (type) {
        AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_USB_HEADSET -> 0
        AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER, AudioDeviceInfo.TYPE_HEARING_AID -> 1
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> 2
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> 9
        else -> 5
    }

    fun release() {
        if (!started) return
        started = false
        audioManager.unregisterAudioDeviceCallback(deviceCallback)
        runCatching { appContext.unregisterReceiver(noisyReceiver) }
        runCatching { audioManager.removeOnModeChangedListener(modeListener) }
    }

    /** Bluetooth output devices currently connected (names only), for the "approve device" setting. */
    fun connectedBluetoothNames(): List<String> = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        .filter { kindOf(it.type).let { k -> k == OutputKind.BLUETOOTH_A2DP || k == OutputKind.BLUETOOTH_SCO || k == OutputKind.BLE_HEADSET } }
        .mapNotNull { it.productName?.toString()?.takeIf { n -> n.isNotBlank() } }
        .distinct()

    companion object {
        fun isCallMode(mode: Int) = mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION ||
            mode == AudioManager.MODE_RINGTONE || mode == AudioManager.MODE_CALL_SCREENING

        fun kindOf(type: Int): OutputKind = when (type) {
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BUILTIN_EARPIECE, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE -> OutputKind.SPEAKER
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> OutputKind.WIRED_HEADSET
            AudioDeviceInfo.TYPE_USB_HEADSET -> OutputKind.USB_HEADSET
            AudioDeviceInfo.TYPE_BLE_HEADSET -> OutputKind.BLE_HEADSET
            AudioDeviceInfo.TYPE_BLE_SPEAKER, AudioDeviceInfo.TYPE_BLE_BROADCAST -> OutputKind.BLE_SPEAKER
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> OutputKind.BLUETOOTH_A2DP
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> OutputKind.BLUETOOTH_SCO
            AudioDeviceInfo.TYPE_HEARING_AID -> OutputKind.HEARING_AID
            else -> OutputKind.OTHER
        }
    }
}
