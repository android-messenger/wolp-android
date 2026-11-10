package org.wolpmessenger.push

import android.content.Context
import org.json.JSONObject
import org.wolpmessenger.devicetrust.DeviceTrust
import org.wolpmessenger.devicetrust.api.VaultPurpose
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * The payload key `pk` (S1 §7.1): 32 random bytes, one per FCM token, kept wrapped by the Vault
 * (`purpose = push-key`) in noBackupFilesDir together with a hash of the token it belongs to.
 */
class PushKeys(context: Context) {
    private val file = File(context.noBackupFilesDir, "push-key.json")

    /** The key for [token]: the stored one if it was made for this token, else a new one (stored). */
    suspend fun keyFor(token: String): ByteArray {
        val h = hash(token)
        load()?.let { (th, key) -> if (th == h) return key else key.fill(0) }
        val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val blob = DeviceTrust.vault.wrap(key, VaultPurpose.PUSH_KEY)
        val json = JSONObject().put("token", h).put("blob", Base64.getEncoder().encodeToString(blob)).toString()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json)
        check(tmp.renameTo(file)) { "cannot store the push key" }
        return key
    }

    /** The current key, or null (none stored; Vault errors propagate as VaultException). */
    suspend fun current(): ByteArray? = load()?.second

    fun clear() {
        file.delete()
    }

    private suspend fun load(): Pair<String, ByteArray>? {
        val o = runCatching { JSONObject(file.readText()) }.getOrNull() ?: return null
        val blob = runCatching { Base64.getDecoder().decode(o.getString("blob")) }.getOrNull() ?: return null
        return o.optString("token") to DeviceTrust.vault.unwrap(blob, VaultPurpose.PUSH_KEY)
    }

    private fun hash(token: String): String =
        MessageDigest.getInstance("SHA-256").digest(token.toByteArray()).joinToString("") { "%02x".format(it) }
}
