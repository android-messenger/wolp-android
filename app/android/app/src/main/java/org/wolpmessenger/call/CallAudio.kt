package org.wolpmessenger.call

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.ToneGenerator
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

    private var tone: ToneGenerator? = null

    /**
     * The ringback of an outgoing call ("it is ringing there"), played here and not by the web app: once
     * [start] has taken the audio for the call, the WebView's own player is paused by the system, and it
     * played the tone only after the call was dropped (owner's report, build 10098). The tone goes to the
     * call stream, so it follows the call's route — earpiece, loudspeaker, headset.
     */
    fun ringback(on: Boolean) {
        if (!on || !started) {
            tone?.let { t -> runCatching { t.stopTone() }; runCatching { t.release() } }
            tone = null
            return
        }
        if (tone != null) return
        tone = runCatching {
            ToneGenerator(AudioManager.STREAM_VOICE_CALL, RINGBACK_VOLUME).also { it.startTone(ToneGenerator.TONE_SUP_RINGTONE) }
        }.onFailure { Log.w(WolpApp.TAG, "call: no ringback tone (${it.javaClass.simpleName})") }.getOrNull()
    }

    fun start(video: Boolean) {
        this.video = video
        if (started) return
        started = true
        prevMode = am.mode
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            // Focus changes are only logged: the WebView itself takes media focus for the call's playback
            // right after the start (seen on A37), which looks exactly like a loss to us. A GSM call is
            // recognised by the audio mode instead (modeListener).
            .setOnAudioFocusChangeListener({ change -> Log.i(WolpApp.TAG, "call: audio focus change $change") }, main)
            .build()
            .also { am.requestAudioFocus(it) }
        am.addOnModeChangedListener(mainExecutor, modeListener)
        am.addOnCommunicationDeviceChangedListener(mainExecutor, deviceListener)
        am.registerAudioDeviceCallback(deviceCallback, main)
        known = available()
        set(CallRules.defaultRoute(known, video))
    }

    /** v1 (§4.5): a GSM call rings or is answered during our call → tell the web app; ConnectionService is v2. */
    private val modeListener = AudioManager.OnModeChangedListener { mode ->
        if (mode == AudioManager.MODE_RINGTONE || mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_CALL_SCREENING) {
            Log.i(WolpApp.TAG, "call: phone call took the audio (mode $mode)")
            CallState.emit(CallState.EVENT_FOCUS_LOST, mapOf("transient" to (mode != AudioManager.MODE_IN_CALL)))
        }
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
        ringback(false)
        started = false
        runCatching { am.unregisterAudioDeviceCallback(deviceCallback) }
        runCatching { am.removeOnCommunicationDeviceChangedListener(deviceListener) }
        runCatching { am.removeOnModeChangedListener(modeListener) }
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
        private const val RINGBACK_VOLUME = 80 // of 100, relative to the call volume the user set

        fun routeOf(type: Int): AudioRoute? = when (type) {
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> AudioRoute.EARPIECE
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> AudioRoute.SPEAKER
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET -> AudioRoute.BLUETOOTH
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_USB_HEADSET -> AudioRoute.WIRED
            else -> null
        }
    }
}
