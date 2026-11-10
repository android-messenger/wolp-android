package org.wolpmessenger.call

import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide call state shared by the push service, the ring/call services, MainActivity and the
 * `Call` plugin (S1 §4.5, §7.3).
 */
object CallState {
    /** The push call that is ringing now (RingService), or null. */
    val ringing: MutableStateFlow<IncomingCall?> = MutableStateFlow(null)

    private val _webWs = MutableStateFlow(WebWs.CLOSED)
    val webWs: StateFlow<WebWs> = _webWs.asStateFlow()
    fun setWebWs(s: WebWs) { _webWs.value = s }

    /** A native decline socket is open (≤ 5 s): the web app must not reconnect meanwhile (§7.3 P1-i). */
    private val _nativeDecline = MutableStateFlow(false)
    val nativeDecline: StateFlow<Boolean> = _nativeDecline.asStateFlow()

    private val main = Handler(Looper.getMainLooper())
    private val afterDecline = ArrayList<() -> Unit>()

    fun nativeDeclineStarted() { _nativeDecline.value = true }

    fun nativeDeclineDone() {
        main.post {
            _nativeDecline.value = false
            val run = afterDecline.toList()
            afterDecline.clear()
            run.forEach { it() }
        }
    }

    /** Runs [action] on the main thread now, or once the native decline socket has closed. */
    fun afterNativeDecline(action: () -> Unit) {
        main.post { if (_nativeDecline.value) afterDecline += action else action() }
    }

    /** Events for the web app; the `Call` plugin sets itself as the sink while it is loaded. */
    interface Sink {
        fun emit(event: String, data: Map<String, Any?>)
        fun listens(event: String): Boolean
    }

    @Volatile var sink: Sink? = null

    fun emit(event: String, data: Map<String, Any?> = emptyMap()): Boolean {
        val s = sink ?: return false
        if (!s.listens(event)) return false
        main.post { s.emit(event, data) }
        return true
    }

    const val EVENT_DECLINE = "callDecline"
    const val EVENT_HANGUP = "hangupFromNotification"
    const val EVENT_ROUTE = "audioRouteChanged"
    const val EVENT_FOCUS_LOST = "audioFocusLost"
}
