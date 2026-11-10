package org.wolpmessenger.call

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.wolpmessenger.WolpApp
import java.util.concurrent.Executor

/**
 * S1 §4.5 audio of an active call: `MODE_IN_COMMUNICATION`, focus `USAGE_VOICE_COMMUNICATION`, the
 * route through `setCommunicationDevice` (default by [CallRules.defaultRoute], a new headset takes
 * over), restored when the call ends. Bluetooth needs BLUETOOTH_CONNECT; without it the system does
 * not offer the Bluetooth device and the other routes still work. Main thread only.
 */
class CallAudio(context: Context, private val onRoute: (AudioRoute) -> Unit) {
    private val am = context.getSystemService(AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val mainExecutor = Executor { main.post(it) }
    private var prevMode = AudioManager.MODE_NORMAL
    private var focus: AudioFocusRequest? = null
    private var video = false
    private var known: Set<AudioRoute> = emptySet()
    var current: AudioRoute? = null
        private set
    var started = false
        private set

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) = devicesChanged()
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) = devicesChanged()
    }
    private val deviceListener = AudioManager.OnCommunicationDeviceChangedListener { d ->
        val r = d?.let { routeOf(it.type) } ?: return@OnCommunicationDeviceChangedListener
        if (r != current) { current = r; onRoute(r) }
    }

    fun start(video: Boolean) {
        this.video = video
        if (started) return
        started = true
        prevMode = am.mode
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setOnAudioFocusChangeListener({ change ->
                // v1 (§4.5): a GSM call or another app took the audio — tell the web app; ConnectionService is v2
                if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                    Log.i(WolpApp.TAG, "call: audio focus lost ($change)")
                    CallState.emit(CallState.EVENT_FOCUS_LOST, mapOf("transient" to (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)))
                }
            }, main)
            .build()
            .also { am.requestAudioFocus(it) }
        am.addOnCommunicationDeviceChangedListener(mainExecutor, deviceListener)
        am.registerAudioDeviceCallback(deviceCallback, main)
        known = available()
        set(CallRules.defaultRoute(known, video))
    }

    fun mediaChanged(video: Boolean) {
        if (this.video == video) return
        this.video = video
        // turning the camera on moves the earpiece to the loudspeaker and back; a headset stays
        val headset = current == AudioRoute.BLUETOOTH || current == AudioRoute.WIRED
        if (!headset) set(CallRules.defaultRoute(available(), video))
    }

    fun stop() {
        if (!started) return
        started = false
        runCatching { am.unregisterAudioDeviceCallback(deviceCallback) }
        runCatching { am.removeOnCommunicationDeviceChangedListener(deviceListener) }
        am.clearCommunicationDevice()
        focus?.let { am.abandonAudioFocusRequest(it) }
        focus = null
        am.mode = prevMode
        current = null
    }

    fun routes(): Set<AudioRoute> = available()

    /** `Call.setAudioRoute`. False — the route is not available now. */
    fun set(route: AudioRoute): Boolean {
        val device = am.availableCommunicationDevices.firstOrNull { routeOf(it.type) == route } ?: return false
        if (!am.setCommunicationDevice(device)) return false
        if (route != current) { current = route; onRoute(route) }
        return true
    }

    private fun devicesChanged() {
        if (!started) return
        val now = available()
        val next = CallRules.routeAfterDevicesChanged(known, now, current, video)
        known = now
        if (next != current) set(next)
    }

    private fun available(): Set<AudioRoute> = am.availableCommunicationDevices.mapNotNull { routeOf(it.type) }.toSet()

    companion object {
        fun routeOf(type: Int): AudioRoute? = when (type) {
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> AudioRoute.EARPIECE
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> AudioRoute.SPEAKER
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET -> AudioRoute.BLUETOOTH
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_USB_HEADSET -> AudioRoute.WIRED
            else -> null
        }
    }
}
