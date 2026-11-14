package org.wolpmessenger.push

import java.time.Duration
import java.time.Instant

/** What WolpMessagingService does with one push (S1 §7.1, §7.3). Pure: unit-tested. */
sealed interface PushAction {
    data object Drop : PushAction
    /** Could not decrypt (Vault unavailable, key replaced): a notification without details. */
    data object Generic : PushAction
    data class Message(val conversationId: String?, val senderId: String?) : PushAction
    data class Call(val conversationId: String, val callerId: String, val media: String?, val ts: String? = null) : PushAction
    data class CancelCall(val conversationId: String) : PushAction
}

object PushRules {
    val CALL_RING_TIMEOUT: Duration = Duration.ofSeconds(45)

    /**
     * @param blocked S2 state is Blocked: drop before decrypting
     * @param signedOut the user signed out and has not registered since (docs F5): nothing is shown
     * @param envelope null = did not decrypt
     * @param firstSeen dedup by eventId (false = already handled)
     * @param foregroundConversation the conversation open on screen (no notification for it)
     * @param serverNow DeviceTrust.trustNow(); null → device clock with a wider tolerance
     */
    fun decide(
        blocked: Boolean,
        envelope: PushPayload.Envelope?,
        firstSeen: (String) -> Boolean,
        foregroundConversation: String?,
        serverNow: Instant?,
        deviceNow: Instant,
        signedOut: Boolean = false,
    ): PushAction {
        if (blocked || signedOut) return PushAction.Drop
        val e = envelope ?: return PushAction.Generic
        e.eventId?.let { if (!firstSeen(it)) return PushAction.Drop }
        return when (e.type) {
            PushPayload.Type.MSG -> {
                // the same message already came over the socket (spec 002 §3.3): shown or seen there
                e.messageId?.let { if (!firstSeen(EventDedup.messageKey(it))) return PushAction.Drop }
                if (e.conversationId != null && e.conversationId == foregroundConversation) PushAction.Drop
                else PushAction.Message(e.conversationId, e.senderId)
            }
            PushPayload.Type.CALL -> {
                val conv = e.conversationId ?: return PushAction.Drop
                val caller = e.senderId ?: return PushAction.Drop
                if (callExpired(e.ts, serverNow, deviceNow)) PushAction.Drop else PushAction.Call(conv, caller, e.media, e.ts)
            }
            PushPayload.Type.CALL_CANCEL -> e.conversationId?.let { PushAction.CancelCall(it) } ?: PushAction.Drop
        }
    }

    /**
     * Spec 002 §3.3: a chat message the web app got over its socket. A notification is due when the user is
     * not looking at the app — the page says it is hidden, or the app as a whole is not on screen (the
     * socket stays open for a moment after leaving, and for the whole of a call or a file choice) — and
     * this message was not shown already by a push.
     * @param first the message id was not in the list of handled ones
     */
    fun socketMessage(first: Boolean, signedOut: Boolean, uiHidden: Boolean, appInForeground: Boolean): Boolean =
        first && !signedOut && (uiHidden || !appInForeground)

    /** §7.3: a call older than CALL_RING_TIMEOUT − 5 s is dropped; ±3 s with server time, ±30 s without. */
    fun callExpired(ts: String?, serverNow: Instant?, deviceNow: Instant): Boolean {
        val at = ts?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return false
        val now = serverNow ?: deviceNow
        val tolerance = if (serverNow != null) Duration.ofSeconds(3) else Duration.ofSeconds(30)
        return Duration.between(at, now) > CALL_RING_TIMEOUT.minusSeconds(5).plus(tolerance)
    }
}
