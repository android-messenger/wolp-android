package org.wolpmessenger.web

import java.util.concurrent.ConcurrentHashMap

/**
 * S1 §5: who may call native plugins. Pure (no Android), JVM-tested; [BridgeGuard] applies it.
 *
 * Capacitor 8.6 checks only `isMainFrame` and never tells a plugin where a call came from (P1-b),
 * so the guard replaces its `androidBridge` listener, admits only the main frame of `app.<host>`
 * and records each admitted call here by its `callbackId`. A plugin that finds no record refuses
 * the call: that also covers the bridge falling back to `addJavascriptInterface` (no origin at all).
 */
object BridgePolicy {
    /** Error code of a refused call (S1 §5). */
    const val PERMISSION_DENIED = "PERMISSION_DENIED"

    /** Only the top frame of exactly the app origin (scheme, host and port; `enroll` and iframes are out). */
    fun admit(sourceOrigin: String?, isMainFrame: Boolean, appOrigin: String): Boolean =
        isMainFrame && sourceOrigin != null && normalize(sourceOrigin) == normalize(appOrigin)

    /** `https://host:443` and `https://host/` name the same origin as `https://host`. */
    fun normalize(origin: String): String {
        var o = origin.trim().trimEnd('/').lowercase()
        if (o.startsWith("https://") && o.endsWith(":443")) o = o.removeSuffix(":443")
        if (o.startsWith("http://") && o.endsWith(":80")) o = o.removeSuffix(":80")
        return o
    }

    /**
     * Capacitor's reply to a call, so a refused call's promise rejects instead of hanging (P1-b: with
     * Capacitor's own listener an iframe call never completed). Format of `MessageHandler.sendResponseMessage`.
     */
    fun deniedReply(callbackId: String, pluginId: String?, methodName: String?): String =
        """{"save":false,"callbackId":${q(callbackId)},"pluginId":${q(pluginId ?: "")},"methodName":${q(methodName ?: "")},""" +
            """"success":false,"error":{"message":"plugin call refused","code":"$PERMISSION_DENIED"}}"""

    private fun q(s: String): String = buildString {
        append('"')
        for (c in s) when {
            c == '"' -> append("\\\"")
            c == '\\' -> append("\\\\")
            c < ' ' || c == ' ' || c == ' ' -> append("\\u%04x".format(c.code))
            else -> append(c)
        }
        append('"')
    }
}

/** Verified origin of each admitted call to a guarded plugin, consumed once by the plugin. */
object CallOrigins {
    data class Origin(val origin: String, val mainFrame: Boolean)

    private const val MAX = 256
    private val calls = ConcurrentHashMap<String, Origin>()

    fun record(callbackId: String, origin: Origin) {
        if (calls.size >= MAX) calls.clear() // only on misuse (calls that never reach the plugin); refusals follow, not leaks
        calls[callbackId] = origin
    }

    /** The record of this call, removed; `null` — the call did not come through the guard. */
    fun take(callbackId: String?): Origin? = callbackId?.let { calls.remove(it) }

    /** S1 §5 check every plugin method starts with. */
    fun allowed(callbackId: String?, appOrigin: String): Boolean {
        val o = take(callbackId) ?: return false
        return BridgePolicy.admit(o.origin, o.mainFrame, appOrigin)
    }

    internal fun clear() = calls.clear()
}
