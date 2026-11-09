package org.wolpmessenger.web

import org.wolpmessenger.devicetrust.api.SecurityLevel
import org.wolpmessenger.devicetrust.api.TrustState
import org.wolpmessenger.devicetrust.api.VaultException
import org.wolpmessenger.devicetrust.api.VaultPurpose
import java.util.Base64

/** Pure JS ↔ S2 mapping of the `DeviceTrust` plugin (S1 §5.1), JVM-tested. */
object JsDeviceTrust {
    const val INVALID_ARGUMENT = "INVALID_ARGUMENT"
    const val VAULT_KEY_GONE = "VAULT_KEY_GONE"
    const val VAULT_LOCKED = "VAULT_LOCKED"
    const val VAULT_CORRUPT = "VAULT_CORRUPT"
    const val UNAVAILABLE = "UNAVAILABLE"

    /** The JS whitelist (S1 §5.1): native purposes (`names`, `push-key`) and anything else → null. */
    fun purpose(s: String?): VaultPurpose? = when (s) {
        VaultPurpose.E2EE.tag -> VaultPurpose.E2EE
        VaultPurpose.E2EE_PLAINTEXT.tag -> VaultPurpose.E2EE_PLAINTEXT
        else -> null
    }

    /** Standard base64 (what `btoa` gives); null on anything else, including an absent value. */
    fun decode(b64: String?): ByteArray? = b64?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }

    fun encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    /** `getState()`: `{state, notAfter?}`; no secrets in it (S2 TrustState carries none). */
    fun state(s: TrustState): Map<String, String> = when (s) {
        TrustState.Uninitialized -> mapOf("state" to "UNINITIALIZED")
        TrustState.NotEnrolled -> mapOf("state" to "NOT_ENROLLED")
        is TrustState.Active -> mapOf("state" to "ACTIVE", "notAfter" to s.notAfter.toString())
        is TrustState.RenewalDue -> mapOf("state" to "RENEWAL_DUE", "notAfter" to s.notAfter.toString())
        is TrustState.ExpiredGrace -> mapOf("state" to "EXPIRED_GRACE")
        TrustState.ReEnrollRequired -> mapOf("state" to "RE_ENROLL_REQUIRED")
        is TrustState.UpdateRequired -> mapOf("state" to "UPDATE_REQUIRED")
        is TrustState.Blocked -> mapOf("state" to "BLOCKED")
    }

    /** `getDeviceInfo().securityLevel`: only the accepted levels; anything else is not reported. */
    fun securityLevel(l: SecurityLevel?): String? = when (l) {
        SecurityLevel.TRUSTED_ENVIRONMENT -> "TEE"
        SecurityLevel.STRONGBOX -> "STRONGBOX"
        else -> null
    }

    /** Error code for a failed vault call; messages never carry data. */
    fun errorCode(e: Throwable): String = when (e) {
        is VaultException.KeyGone -> VAULT_KEY_GONE
        is VaultException.Locked -> VAULT_LOCKED
        is VaultException.Corrupt -> VAULT_CORRUPT
        else -> UNAVAILABLE
    }
}
