package org.wolpmessenger.web

import android.util.Log
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.getcapacitor.Bridge
import com.getcapacitor.MessageHandler
import org.json.JSONObject
import org.wolpmessenger.WolpApp

/**
 * Replaces Capacitor's `androidBridge` WebMessageListener with one that applies [BridgePolicy]
 * (S1 §5). Installed from a plugin's `load()`: Capacitor builds its MessageHandler, then loads
 * plugins, and only then loads the UI — so no document ever sees Capacitor's listener.
 *
 * Capacitor keeps the reply channel in a private field of MessageHandler; the guard sets it before
 * passing an admitted message on, as Capacitor's own listener does (both fields are kept for R8 in
 * proguard-rules.pro). If any of this is impossible the guard is not installed and guarded plugins
 * refuse every call (no [CallOrigins] record) — fail closed.
 */
object BridgeGuard {
    private const val NAME = "androidBridge"

    /** Plugins whose calls are recorded for their own origin check (S1 §5: every plugin of ours). */
    val GUARDED = setOf("DeviceTrust", "Files")

    @Volatile private var installedOn: Bridge? = null

    fun install(bridge: Bridge, appOrigin: String): Boolean {
        if (installedOn === bridge) return true
        val webView = bridge.webView
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) || bridge.config.isUsingLegacyBridge) {
            Log.e(WolpApp.TAG, "bridge guard: no WebMessageListener, plugins stay closed")
            return false
        }
        return try {
            val handler = Bridge::class.java.getDeclaredField("msgHandler").apply { isAccessible = true }.get(bridge) as MessageHandler
            val replyField = MessageHandler::class.java.getDeclaredField("javaScriptReplyProxy").apply { isAccessible = true }
            WebViewCompat.removeWebMessageListener(webView, NAME)
            WebViewCompat.addWebMessageListener(webView, NAME, setOf(appOrigin)) { _, message, sourceOrigin, isMainFrame, reply ->
                onMessage(handler, replyField, appOrigin, message.data, sourceOrigin.toString(), isMainFrame, reply)
            }
            installedOn = bridge
            Log.i(WolpApp.TAG, "bridge guard: installed for $appOrigin")
            true
        } catch (e: Exception) {
            // The listener may be gone already: nothing reaches plugins then, which is the safe side.
            Log.e(WolpApp.TAG, "bridge guard: not installed, plugins stay closed", e)
            false
        }
    }

    private fun onMessage(
        handler: MessageHandler,
        replyField: java.lang.reflect.Field,
        appOrigin: String,
        data: String?,
        sourceOrigin: String,
        isMainFrame: Boolean,
        reply: JavaScriptReplyProxy,
    ) {
        data ?: return
        val msg = runCatching { JSONObject(data) }.getOrNull() ?: return
        val callbackId = msg.optString("callbackId").takeIf { it.isNotEmpty() }
        val pluginId = msg.optString("pluginId").takeIf { it.isNotEmpty() }
        if (!BridgePolicy.admit(sourceOrigin, isMainFrame, appOrigin)) {
            Log.w(WolpApp.TAG, "bridge guard: refused $pluginId.${msg.optString("methodName")} from $sourceOrigin (mainFrame=$isMainFrame)")
            if (callbackId != null && msg.optString("type") != "js.error") {
                runCatching { reply.postMessage(BridgePolicy.deniedReply(callbackId, pluginId, msg.optString("methodName"))) }
            }
            return
        }
        if (callbackId != null && pluginId in GUARDED) {
            CallOrigins.record(callbackId, CallOrigins.Origin(sourceOrigin, isMainFrame))
        }
        replyField.set(handler, reply)
        handler.postMessage(data)
    }
}
