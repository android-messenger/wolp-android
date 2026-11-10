package org.wolpmessenger.plugins

import android.Manifest
import android.util.Log
import com.getcapacitor.JSObject
import com.getcapacitor.PermissionState
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import com.getcapacitor.annotation.Permission
import com.getcapacitor.annotation.PermissionCallback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.wolpmessenger.AppConfig
import org.wolpmessenger.WolpApp
import org.wolpmessenger.push.JsPush
import org.wolpmessenger.push.NameCache
import org.wolpmessenger.push.NotificationTap
import org.wolpmessenger.push.Notifier
import org.wolpmessenger.push.PushRegistrar
import org.wolpmessenger.push.PushState
import org.wolpmessenger.web.BridgePolicy
import org.wolpmessenger.web.CallOrigins
import org.wolpmessenger.web.JsDeviceTrust

/**
 * `Push` plugin (S1 §5.2, §7.0). Same origin rule as `DeviceTrust` (S1 §5).
 *
 * Events: `notificationTap` (retained until a listener takes it), `appBackground` / `appForeground`
 * (§7.0: the web app closes its WebSocket and answers [wsClosed], then reconnects on foreground).
 */
@CapacitorPlugin(
    name = "Push",
    permissions = [Permission(alias = PushPlugin.NOTIFICATIONS, strings = [Manifest.permission.POST_NOTIFICATIONS])],
)
class PushPlugin : Plugin() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val registrar by lazy { PushRegistrar(context) }
    private val names by lazy { NameCache(context) }

    /** Set by MainActivity (§7.0). */
    var onWsClosed: (() -> Unit)? = null

    override fun handleOnDestroy() {
        scope.cancel()
    }

    private fun admitted(call: PluginCall): Boolean {
        if (CallOrigins.allowed(call.callbackId, AppConfig.uiOrigin)) return true
        Log.w(WolpApp.TAG, "Push.${call.methodName}: refused (not the app's main frame)")
        call.reject("plugin call refused", BridgePolicy.PERMISSION_DENIED)
        return false
    }

    /** Token + payload key → firebase-push. POST_NOTIFICATIONS is asked here, i.e. after enrollment (§7.1). */
    @PluginMethod
    fun register(call: PluginCall) {
        if (!admitted(call)) return
        if (getPermissionState(NOTIFICATIONS) == PermissionState.PROMPT || getPermissionState(NOTIFICATIONS) == PermissionState.PROMPT_WITH_RATIONALE) {
            PushState.hold(PushState.HOLD_PERMISSION, 60_000)
            requestPermissionForAlias(NOTIFICATIONS, call, "afterNotificationPermission")
        } else doRegister(call)
    }

    @PermissionCallback
    private fun afterNotificationPermission(call: PluginCall) {
        PushState.release(PushState.HOLD_PERMISSION)
        Log.i(WolpApp.TAG, "push: notifications ${getPermissionState(NOTIFICATIONS)}")
        doRegister(call) // registration does not depend on the answer; without it nothing is shown
    }

    private fun doRegister(call: PluginCall) {
        scope.launch {
            val r = registrar.register()
            call.resolve(JSObject().put("result", r.name.lowercase()))
        }
    }

    /** Must run before the Kratos logout (S1 §9): the request needs the session. */
    @PluginMethod
    fun unregister(call: PluginCall) {
        if (!admitted(call)) return
        scope.launch {
            registrar.unregister()
            Notifier(context).cancelAll()
            call.resolve()
        }
    }

    @PluginMethod
    fun updateContactNames(call: PluginCall) {
        if (!admitted(call)) return
        val map = JsPush.names(call.getObject("names"))
            ?: return call.reject("names: {id: name} expected (≤ ${JsPush.MAX_NAMES})", JsDeviceTrust.INVALID_ARGUMENT)
        scope.launch {
            runCatching { names.merge(map) }
                .onSuccess { call.resolve() }
                .onFailure {
                    Log.w(WolpApp.TAG, "push: names not stored: ${it.javaClass.simpleName}")
                    call.reject("names not stored", JsDeviceTrust.errorCode(it as? Exception ?: RuntimeException(it)))
                }
        }
    }

    @PluginMethod
    fun setForegroundConversation(call: PluginCall) {
        if (!admitted(call)) return
        val raw = call.data.opt("conversationId")
        val id = if (raw == null || raw == org.json.JSONObject.NULL) null
        else JsPush.id(raw as? String) ?: return call.reject("conversationId: string or null", JsDeviceTrust.INVALID_ARGUMENT)
        PushState.foregroundConversation = id
        id?.let { Notifier(context).cancelConversation(it) }
        call.resolve()
    }

    /** §7.0: the web app has closed its WebSocket after `appBackground`. */
    @PluginMethod
    fun wsClosed(call: PluginCall) {
        if (!admitted(call)) return
        onWsClosed?.invoke()
        call.resolve()
    }

    fun emitBackground() = notifyListeners(EVENT_BACKGROUND, JSObject())
    fun emitForeground() = notifyListeners(EVENT_FOREGROUND, JSObject())
    fun hasBackgroundListener(): Boolean = hasListeners(EVENT_BACKGROUND)

    fun emitTap(t: NotificationTap) {
        val o = JSObject()
        t.conversationId?.let { o.put("conversationId", it) }
        if (t.callConversationId != null && t.callerId != null) {
            o.put("call", JSObject().put("conversationId", t.callConversationId).put("callerId", t.callerId).apply { t.media?.let { put("media", it) } })
        }
        notifyListeners(EVENT_TAP, o, true)
    }

    companion object {
        const val NOTIFICATIONS = "notifications"
        const val EVENT_TAP = "notificationTap"
        const val EVENT_BACKGROUND = "appBackground"
        const val EVENT_FOREGROUND = "appForeground"
    }
}
