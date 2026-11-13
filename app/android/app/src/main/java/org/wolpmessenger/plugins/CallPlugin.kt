package org.wolpmessenger.plugins

import android.Manifest
import android.util.Log
import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import com.getcapacitor.PermissionState
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import com.getcapacitor.annotation.Permission
import com.getcapacitor.annotation.PermissionCallback
import org.wolpmessenger.AppConfig
import org.wolpmessenger.WolpApp
import org.wolpmessenger.call.AudioRoute
import org.wolpmessenger.call.CallService
import org.wolpmessenger.call.CallState
import org.wolpmessenger.push.JsPush
import org.wolpmessenger.push.PushState
import org.wolpmessenger.web.BridgePolicy
import org.wolpmessenger.web.CallOrigins
import org.wolpmessenger.web.JsDeviceTrust

/**
 * `Call` plugin (S1 §5.3, §4.5). Same origin rule as `DeviceTrust` (S1 §5).
 *
 * Events: `audioRouteChanged {route}`, `hangupFromNotification`, `audioFocusLost {transient}`,
 * `callDecline {conversationId, callerId}` (§7.3: the native screen was declined while the web app's
 * WebSocket may be open — the web app sends its own `call:end`).
 */
@CapacitorPlugin(
    name = "Call",
    permissions = [Permission(alias = CallPlugin.BLUETOOTH, strings = [Manifest.permission.BLUETOOTH_CONNECT])],
)
class CallPlugin : Plugin(), CallState.Sink {

    override fun load() {
        CallState.sink = this
    }

    override fun handleOnDestroy() {
        if (CallState.sink === this) CallState.sink = null
    }

    override fun emit(event: String, data: Map<String, Any?>) {
        val o = JSObject()
        data.forEach { (k, v) -> o.put(k, v) }
        notifyListeners(event, o)
    }

    override fun listens(event: String): Boolean = hasListeners(event)

    private fun admitted(call: PluginCall): Boolean {
        if (CallOrigins.allowed(call.callbackId, AppConfig.uiOrigin)) return true
        Log.w(WolpApp.TAG, "Call.${call.methodName}: refused (not the app's main frame)")
        call.reject("plugin call refused", BridgePolicy.PERMISSION_DENIED)
        return false
    }

    private fun media(call: PluginCall): String? = call.getString("media")?.takeIf { it == "audio" || it == "video" }

    /** FGS + audio mode. BLUETOOTH_CONNECT is asked on the first call (§4.5); the call does not wait for the answer to start. */
    @PluginMethod
    fun started(call: PluginCall) {
        if (!admitted(call)) return
        val media = media(call) ?: return call.reject("media: 'audio' | 'video'", JsDeviceTrust.INVALID_ARGUMENT)
        JsPush.id(call.getString("conversationId")) ?: return call.reject("conversationId expected", JsDeviceTrust.INVALID_ARGUMENT)
        val peerName = call.getString("peerName")?.take(MAX_NAME)
        try {
            CallService.start(context, media, peerName)
        } catch (e: Exception) {
            Log.w(WolpApp.TAG, "call: FGS not started (${e.javaClass.simpleName})")
            return call.reject("call service not started", JsDeviceTrust.UNAVAILABLE)
        }
        if (getPermissionState(BLUETOOTH) == PermissionState.PROMPT) {
            PushState.hold(PushState.HOLD_PERMISSION, 60_000)
            requestPermissionForAlias(BLUETOOTH, call, "afterBluetooth")
        } else call.resolve()
    }

    @PermissionCallback
    private fun afterBluetooth(call: PluginCall) {
        PushState.release(PushState.HOLD_PERMISSION)
        Log.i(WolpApp.TAG, "call: bluetooth ${getPermissionState(BLUETOOTH)}")
        call.resolve()
    }

    @PluginMethod
    fun mediaChanged(call: PluginCall) {
        if (!admitted(call)) return
        val media = media(call) ?: return call.reject("media: 'audio' | 'video'", JsDeviceTrust.INVALID_ARGUMENT)
        CallService.mediaChanged(context, media)
        call.resolve()
    }

    /** The ringback tone while an outgoing call rings at the other side: `{on: true}` after `started()`, `{on: false}` when answered or dropped. */
    @PluginMethod
    fun ringback(call: PluginCall) {
        if (!admitted(call)) return
        val on = call.getBoolean("on") ?: return call.reject("on: true | false", JsDeviceTrust.INVALID_ARGUMENT)
        activity.runOnUiThread { CallService.ringback(on) }
        call.resolve()
    }

    @PluginMethod
    fun ended(call: PluginCall) {
        if (!admitted(call)) return
        activity.runOnUiThread { CallService.ringback(false) }
        CallService.stop(context)
        call.resolve()
    }

    @PluginMethod
    fun getAudioRoutes(call: PluginCall) {
        if (!admitted(call)) return
        val audio = CallService.audioOrNull
        val routes = audio?.routes() ?: emptySet()
        call.resolve(
            JSObject()
                .put("routes", JSArray(AudioRoute.entries.filter { it in routes }.map { it.js }))
                .put("current", audio?.current?.js ?: ""),
        )
    }

    /** Opening the route choice asks for Bluetooth if it was never asked (§4.5). */
    @PluginMethod
    fun setAudioRoute(call: PluginCall) {
        if (!admitted(call)) return
        val route = AudioRoute.fromJs(call.getString("route"))
            ?: return call.reject("route: earpiece | speaker | bluetooth | wired", JsDeviceTrust.INVALID_ARGUMENT)
        val audio = CallService.audioOrNull ?: return call.reject("no active call", JsDeviceTrust.UNAVAILABLE)
        if (audio.set(route)) call.resolve() else call.reject("route not available", JsDeviceTrust.UNAVAILABLE)
    }

    companion object {
        const val BLUETOOTH = "bluetooth"
        private const val MAX_NAME = 120
    }
}
