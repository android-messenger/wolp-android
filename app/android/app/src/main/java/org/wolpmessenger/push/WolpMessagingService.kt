package org.wolpmessenger.push

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.wolpmessenger.WolpApp
import org.wolpmessenger.call.CallRules
import org.wolpmessenger.call.IncomingCall
import org.wolpmessenger.call.RingService
import org.wolpmessenger.devicetrust.DeviceTrust
import org.wolpmessenger.devicetrust.api.TrustState
import java.io.File
import java.time.Instant

/**
 * FCM entry point (S1 §7.1). Not exported; the library's exported receiver delivers to it. Both
 * callbacks run on a background thread with a time budget, hence runBlocking with timeouts.
 */
class WolpMessagingService : FirebaseMessagingService() {
    private val dedup by lazy { EventDedup(File(noBackupFilesDir, "push-events.txt")) }

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        if (!PushPayload.isOurs(data)) return
        // S2 Blocked: drop before touching any key (security/11 §3)
        val blocked = DeviceTrust.state.value is TrustState.Blocked
        val envelope = if (blocked) null else runBlocking {
            withTimeoutOrNull(5_000) {
                runCatching { PushKeys(this@WolpMessagingService).current() }.getOrNull()?.let { key ->
                    try { PushPayload.open(key, data) } finally { key.fill(0) }
                }
            }
        }
        val action = PushRules.decide(
            blocked = blocked,
            envelope = envelope,
            firstSeen = dedup::firstSeen,
            foregroundConversation = PushState.suppressedConversation(),
            serverNow = DeviceTrust.trustNow(),
            deviceNow = Instant.now(),
        )
        // stable names: class names are obfuscated by R8 in release builds
        val label = when (action) {
            PushAction.Drop -> "drop"
            PushAction.Generic -> "generic"
            is PushAction.Message -> "message"
            is PushAction.Call -> "call"
            is PushAction.CancelCall -> "call_cancel"
        }
        Log.i(WolpApp.TAG, "push: $label")
        val notifier = Notifier(this)
        when (action) {
            PushAction.Drop -> Unit
            PushAction.Generic -> notifier.message(null, null)
            is PushAction.Message -> notifier.message(action.conversationId, name(action.senderId))
            is PushAction.Call -> {
                // §7.3: ring through the FGS; if it may not start — the insistent notification as before
                val call = IncomingCall(action.conversationId, action.callerId, action.media, name(action.callerId))
                val ringMs = CallRules.ringFor(action.ts, DeviceTrust.trustNow(), Instant.now()).toMillis()
                if (!RingService.start(this, call, ringMs)) notifier.call(call.conversationId, call.callerId, call.media, call.callerName)
            }
            is PushAction.CancelCall -> {
                RingService.cancel(this, action.conversationId)
                notifier.cancelCall(action.conversationId)
            }
        }
    }

    override fun onNewToken(token: String) {
        Log.i(WolpApp.TAG, "push: new FCM token")
        runBlocking { withTimeoutOrNull(20_000) { PushRegistrar(this@WolpMessagingService).register(token) } }
    }

    private fun name(id: String?): String? = runBlocking { withTimeoutOrNull(2_000) { NameCache(this@WolpMessagingService).name(id) } }
}
