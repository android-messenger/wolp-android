package org.wolpmessenger.call

import org.json.JSONObject
import org.wolpmessenger.push.PushRules
import java.time.Duration
import java.time.Instant

/** An incoming call announced by push (S1 §7.3). The name is only from the local cache; shown on the lock screen too (D16, owner 2026-11-11). */
data class IncomingCall(
    val conversationId: String,
    val callerId: String,
    val media: String?,
    val callerName: String?,
)

/** State of the web app's WebSocket as the native side sees it (S1 §7.0). */
enum class WebWs {
    /** No WebView or it closed its socket (`wsClosed`, `about:blank`): only here may a native socket open (P1-i). */
    CLOSED,
    /** `appBackground` sent, `wsClosed` not yet back (≤ 3 s, then the WebView is parked). */
    CLOSING,
    /** The web app may hold its socket: a second one from us would evict it with `4409`. */
    OPEN,
}

enum class DeclinePath { WEB, NATIVE, WAIT }

enum class AudioRoute(val js: String) {
    EARPIECE("earpiece"), SPEAKER("speaker"), BLUETOOTH("bluetooth"), WIRED("wired");

    companion object {
        fun fromJs(s: String?): AudioRoute? = entries.firstOrNull { it.js == s }
    }
}

/** Pure rules of S1 §4.5 / §7.3, JVM-tested. */
object CallRules {
    /**
     * §7.3 P1-i: decline through the web app while its socket may be open (`callDecline` → its own
     * `call:end`), natively only when it is closed; while it is closing — wait for the outcome.
     * Without a JS listener there is nobody to send it, so the native path is the only one left.
     */
    fun declinePath(ws: WebWs, webListens: Boolean): DeclinePath = when (ws) {
        WebWs.CLOSED -> DeclinePath.NATIVE
        WebWs.CLOSING -> DeclinePath.WAIT
        WebWs.OPEN -> if (webListens) DeclinePath.WEB else DeclinePath.NATIVE
    }

    /**
     * §4.5 default route: a headset (Bluetooth, then wired) wins; otherwise an audio call goes to the
     * earpiece and a video call to the loudspeaker. A device without an earpiece uses the loudspeaker.
     */
    fun defaultRoute(available: Set<AudioRoute>, video: Boolean): AudioRoute = when {
        AudioRoute.BLUETOOTH in available -> AudioRoute.BLUETOOTH
        AudioRoute.WIRED in available -> AudioRoute.WIRED
        !video && AudioRoute.EARPIECE in available -> AudioRoute.EARPIECE
        else -> AudioRoute.SPEAKER
    }

    /**
     * The route after the set of devices changed: a newly connected headset takes over; a vanished
     * current route falls back to the default; otherwise the current (possibly user-chosen) route stays.
     */
    fun routeAfterDevicesChanged(before: Set<AudioRoute>, now: Set<AudioRoute>, current: AudioRoute?, video: Boolean): AudioRoute {
        val added = now - before
        if (AudioRoute.BLUETOOTH in added) return AudioRoute.BLUETOOTH
        if (AudioRoute.WIRED in added) return AudioRoute.WIRED
        if (current != null && current in now) return current
        return defaultRoute(now, video)
    }

    /** Proximity sensor only for an audio call on the earpiece (§4.5). */
    fun proximity(video: Boolean, route: AudioRoute?): Boolean = !video && route == AudioRoute.EARPIECE

    /** How long to ring: CALL_RING_TIMEOUT minus the age of the push (by server time when known), 5…45 s. */
    fun ringFor(ts: String?, serverNow: Instant?, deviceNow: Instant): Duration {
        val full = PushRules.CALL_RING_TIMEOUT
        val at = ts?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return full
        val age = Duration.between(at, serverNow ?: deviceNow).coerceAtLeast(Duration.ZERO)
        return (full - age).coerceIn(Duration.ofSeconds(5), full)
    }

    /**
     * §7.3 native decline: one `SIGNAL_IN` frame carrying `{type:"call:end"}` addressed to the caller,
     * the shape `frameBridge.ts` `toWire` sends (backend `MessageValidator`: ids, timestamp + IANA zone,
     * `payload.kind = event`). `call:end` is not encrypted.
     */
    fun declineFrame(callerId: String, conversationId: String, messageId: String, nowMillis: Long, timezone: String): String =
        JSONObject()
            .put("type", "SIGNAL_IN")
            .put("messageId", messageId)
            .put("recipientId", callerId)
            .put("conversationId", conversationId)
            .put("senderTimestamp", nowMillis)
            .put("senderTimezone", timezone)
            .put("payload", JSONObject().put("kind", "event").put("body", JSONObject().put("type", "call:end").toString()))
            .toString()

    /** `https://host:port` of the API → the messenger WebSocket (`messengerWsUrl()` of the web app). */
    fun wsUrl(appUrl: String): String = appUrl.trimEnd('/').replaceFirst(Regex("^https://"), "wss://") + MESSENGER_WS_PATH

    const val MESSENGER_WS_PATH = "/messenger/ws"
}
