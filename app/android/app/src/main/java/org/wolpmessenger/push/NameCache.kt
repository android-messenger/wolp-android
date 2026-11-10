package org.wolpmessenger.push

import android.content.Context
import org.json.JSONObject
import org.wolpmessenger.devicetrust.DeviceTrust
import org.wolpmessenger.devicetrust.api.VaultPurpose
import java.io.File

/**
 * Contact names for notifications (S1 §7.1): pushed by the web app (`Push.updateContactNames`),
 * kept wrapped by the Vault (`purpose = names`). Push payloads carry no names.
 */
class NameCache(context: Context) {
    private val file = File(context.noBackupFilesDir, "push-names.bin")

    suspend fun merge(names: Map<String, String>) {
        val all = (runCatching { load() }.getOrNull() ?: emptyMap()) + names
        val trimmed = if (all.size > JsPush.MAX_NAMES) all.entries.toList().takeLast(JsPush.MAX_NAMES).associate { it.toPair() } else all
        val plain = JSONObject(trimmed).toString().toByteArray()
        val blob = try { DeviceTrust.vault.wrap(plain, VaultPurpose.NAMES) } finally { plain.fill(0) }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeBytes(blob)
        check(tmp.renameTo(file)) { "cannot store names" }
    }

    /** null when unknown or the Vault is unavailable. */
    suspend fun name(id: String?): String? = id?.let { runCatching { load()[it] }.getOrNull() }

    fun clear() {
        file.delete()
    }

    private suspend fun load(): Map<String, String> {
        if (!file.isFile) return emptyMap()
        val plain = DeviceTrust.vault.unwrap(file.readBytes(), VaultPurpose.NAMES)
        try {
            val o = JSONObject(String(plain, Charsets.UTF_8))
            return o.keys().asSequence().associateWith { o.optString(it) }
        } finally {
            plain.fill(0)
        }
    }
}
