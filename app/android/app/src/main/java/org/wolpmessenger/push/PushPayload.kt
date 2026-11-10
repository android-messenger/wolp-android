package org.wolpmessenger.push

import org.json.JSONObject
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The FCM data message of `firebase-push` (S1 §7.1): `{"v":"2","c":"<b64(iv‖ct‖tag)>"}`, AES-256-GCM
 * with the device payload key, aad `wolp-push-v2`. Reference vector: firebase-push
 * `internal/domain/testdata/payload-vector.json` (PushPayloadTest).
 */
object PushPayload {
    enum class Type { MSG, CALL, CALL_CANCEL }

    data class Envelope(
        val type: Type,
        val eventId: String?,
        val conversationId: String?,
        val messageId: String?,
        val senderId: String?,
        val media: String?,
        val ts: String?,
    )

    private val AAD = "wolp-push-v2".toByteArray()
    private const val IV_LEN = 12
    private const val TAG_BITS = 128

    /** null when the message is not ours, malformed, or does not decrypt with [key]. */
    fun open(key: ByteArray, data: Map<String, String>): Envelope? {
        if (data["v"] != "2") return null
        val raw = runCatching { Base64.getDecoder().decode(data["c"] ?: return null) }.getOrNull() ?: return null
        if (raw.size < IV_LEN + TAG_BITS / 8) return null
        val plain = try {
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, raw, 0, IV_LEN))
                updateAAD(AAD)
                doFinal(raw, IV_LEN, raw.size - IV_LEN)
            }
        } catch (e: java.security.GeneralSecurityException) {
            return null
        }
        return parse(String(plain, Charsets.UTF_8))
    }

    internal fun parse(json: String): Envelope? = runCatching {
        val o = JSONObject(json)
        val type = when (o.optString("type")) {
            "msg" -> Type.MSG
            "call" -> Type.CALL
            "call_cancel" -> Type.CALL_CANCEL
            else -> return null
        }
        fun s(k: String) = o.optString(k, "").takeIf { it.isNotEmpty() && it.length <= 200 }
        Envelope(type, s("eventId"), s("conversationId"), s("messageId"), s("senderId"), s("media")?.takeIf { it == "audio" || it == "video" }, s("ts"))
    }.getOrNull()

    /** Data-message keys that identify a push of ours (anything else is ignored). */
    fun isOurs(data: Map<String, String>): Boolean = data["v"] == "2" && data.containsKey("c")
}
