package org.wolpmessenger.call

import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.wolpmessenger.AppConfig
import org.wolpmessenger.WolpApp
import org.wolpmessenger.devicetrust.DeviceTrust
import java.util.TimeZone
import java.util.UUID

/**
 * Declining an incoming push call (S1 §7.3). Through the web app while its WebSocket may be open
 * (`callDecline` → its existing `call:end`), otherwise natively: a short WebSocket over S2's mTLS
 * client with the Kratos cookie, one `call:end` frame, close. 5 s budget; on failure the caller hears
 * the end by its own timeout.
 */
object NativeDecline {
    private const val BUDGET_MS = 5_000L
    private const val SETTLE_MS = 3_500L    // BackgroundController parks the WebView after 3 s
    private const val FLUSH_MS = 400L       // let the server read the frame before the close

    suspend fun decline(call: IncomingCall) {
        var path = CallRules.declinePath(CallState.webWs.value, CallState.sink?.listens(CallState.EVENT_DECLINE) == true)
        if (path == DeclinePath.WAIT) {
            withTimeoutOrNull(SETTLE_MS) { CallState.webWs.first { it != WebWs.CLOSING } }
            path = CallRules.declinePath(CallState.webWs.value, CallState.sink?.listens(CallState.EVENT_DECLINE) == true)
            if (path == DeclinePath.WAIT) path = DeclinePath.NATIVE // still closing: it will be parked any moment
        }
        Log.i(WolpApp.TAG, "call: decline via ${path.name.lowercase()}")
        when (path) {
            DeclinePath.WEB -> CallState.emit(CallState.EVENT_DECLINE, mapOf("conversationId" to call.conversationId, "callerId" to call.callerId))
            else -> native(call)
        }
    }

    private suspend fun native(call: IncomingCall) {
        val cookie = withContext(Dispatchers.Main) { AppConfig.kratosCookie() }
        if (cookie == null) {
            Log.w(WolpApp.TAG, "call: native decline impossible, no Kratos session")
            return
        }
        CallState.nativeDeclineStarted()
        try {
            val ok = withTimeoutOrNull(BUDGET_MS) { send(call, cookie) } ?: false
            Log.i(WolpApp.TAG, "call: native decline ${if (ok) "sent" else "failed"}")
        } finally {
            CallState.nativeDeclineDone()
        }
    }

    private suspend fun send(call: IncomingCall, cookie: String): Boolean {
        val opened = CompletableDeferred<WebSocket?>()
        val closed = CompletableDeferred<Unit>()
        val request = Request.Builder()
            .url(CallRules.wsUrl(AppConfig.appUrl))
            .header("Cookie", cookie)
            .header("Origin", AppConfig.uiOrigin) // the same origin the web app connects from
            .build()
        val ws = DeviceTrust.okHttpClient().newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { opened.complete(webSocket) }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.i(WolpApp.TAG, "call: decline socket failed (${t.javaClass.simpleName}, HTTP ${response?.code})")
                opened.complete(null); closed.complete(Unit)
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { closed.complete(Unit) }
        })
        try {
            val socket = opened.await() ?: return false
            val frame = CallRules.declineFrame(
                callerId = call.callerId,
                conversationId = call.conversationId,
                messageId = UUID.randomUUID().toString(),
                nowMillis = System.currentTimeMillis(),
                timezone = TimeZone.getDefault().id,
            )
            if (!socket.send(frame)) return false
            delay(FLUSH_MS)
            socket.close(1000, null)
            withTimeoutOrNull(1_000) { closed.await() }
            return true
        } finally {
            ws.cancel() // no-op after a clean close; frees the socket on timeout
        }
    }
}
