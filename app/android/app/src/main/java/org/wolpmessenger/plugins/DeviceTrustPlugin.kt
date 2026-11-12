package org.wolpmessenger.plugins

import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.wolpmessenger.AppConfig
import org.wolpmessenger.WolpApp
import org.wolpmessenger.devicetrust.DeviceTrust
import org.wolpmessenger.devicetrust.api.VaultPurpose
import org.wolpmessenger.web.BridgeGuard
import org.wolpmessenger.web.BridgePolicy
import org.wolpmessenger.web.CallOrigins
import org.wolpmessenger.web.JsDeviceTrust

/**
 * `DeviceTrust` plugin (S1 §5.1): JS ↔ S2. Every method first checks that this very call came
 * through [BridgeGuard] from the main frame of the packaged UI (S1 §5, security/09); the API host,
 * `enroll`, iframes and calls
 * that bypassed the guard get `PERMISSION_DENIED`. No method returns key material.
 *
 * Secret chats (wolp-device-trust specs/002, story 4): exactly six `e2ee*` methods over
 * `DeviceTrust.e2ee`. The keys and the sessions stay in the library; the web app passes text and
 * envelopes and gets a refusal code from the library's closed list.
 */
@CapacitorPlugin(name = "DeviceTrust")
class DeviceTrustPlugin : Plugin() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun load() {
        // Runs inside Bridge construction, before the UI is loaded (see BridgeGuard).
        BridgeGuard.install(bridge, AppConfig.uiOrigin)
    }

    override fun handleOnDestroy() {
        scope.cancel()
    }

    private fun admitted(call: PluginCall): Boolean {
        if (CallOrigins.allowed(call.callbackId, AppConfig.uiOrigin)) return true
        Log.w(WolpApp.TAG, "DeviceTrust.${call.methodName}: refused (not the app's main frame)")
        call.reject("plugin call refused", BridgePolicy.PERMISSION_DENIED)
        return false
    }

    @PluginMethod
    fun getState(call: PluginCall) {
        if (!admitted(call)) return
        call.resolve(JSObject().apply { JsDeviceTrust.state(DeviceTrust.state.value).forEach { (k, v) -> put(k, v) } })
    }

    @PluginMethod
    fun getDeviceInfo(call: PluginCall) {
        if (!admitted(call)) return
        // S2 knows the Keystore level only from a precheck in this process (before enrollment);
        // otherwise securityLevel is left out rather than guessed.
        val info = DeviceTrust.deviceInfo()
        call.resolve(JSObject().apply {
            JsDeviceTrust.securityLevel(info?.keystoreLevel)?.let { put("securityLevel", it) }
            put("strongBox", info?.strongBoxAvailable ?: context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE))
            put("sdkInt", Build.VERSION.SDK_INT)
            put("securityPatch", Build.VERSION.SECURITY_PATCH)
        })
    }

    @PluginMethod
    fun vaultWrap(call: PluginCall) {
        if (!admitted(call)) return
        val purpose = purposeOf(call) ?: return
        val data = JsDeviceTrust.decode(call.getString("data")) ?: return call.reject("data: base64 expected", JsDeviceTrust.INVALID_ARGUMENT)
        vault(call, "wrap") {
            try {
                mapOf("blob" to JsDeviceTrust.encode(DeviceTrust.vault.wrap(data, purpose)))
            } finally {
                data.fill(0)
            }
        }
    }

    @PluginMethod
    fun vaultUnwrap(call: PluginCall) {
        if (!admitted(call)) return
        val purpose = purposeOf(call) ?: return
        val blob = JsDeviceTrust.decode(call.getString("blob")) ?: return call.reject("blob: base64 expected", JsDeviceTrust.INVALID_ARGUMENT)
        vault(call, "unwrap") {
            val plain = DeviceTrust.vault.unwrap(blob, purpose)
            try {
                mapOf("data" to JsDeviceTrust.encode(plain))
            } finally {
                plain.fill(0)
            }
        }
    }

    // ---------------------------------------------------------------- secret chats

    @PluginMethod
    fun e2eeEncrypt(call: PluginCall) {
        val peer = peerOf(call) ?: return
        val text = call.getString("text") ?: return call.reject("text expected", JsDeviceTrust.INVALID_ARGUMENT)
        e2ee(call, "encrypt") { mapOf("envelope" to DeviceTrust.e2ee.encrypt(peer, text)) }
    }

    @PluginMethod
    fun e2eeDecrypt(call: PluginCall) {
        val peer = peerOf(call) ?: return
        val envelope = call.getString("envelope") ?: return call.reject("envelope expected", JsDeviceTrust.INVALID_ARGUMENT)
        e2ee(call, "decrypt") { mapOf("text" to DeviceTrust.e2ee.decrypt(peer, envelope)) }
    }

    @PluginMethod
    fun e2eePeerState(call: PluginCall) {
        val peer = peerOf(call) ?: return
        e2ee(call, "peerState") { JsDeviceTrust.peerState(DeviceTrust.e2ee.peerState(peer)) }
    }

    /** `{digits}` — 60 digits; no field while no key of the peer is accepted. */
    @PluginMethod
    fun e2eeSafetyNumber(call: PluginCall) {
        val peer = peerOf(call) ?: return
        e2ee(call, "safetyNumber") { DeviceTrust.e2ee.safetyNumber(peer)?.let { mapOf("digits" to it) } ?: emptyMap() }
    }

    @PluginMethod
    fun e2eeSetVerified(call: PluginCall) {
        val peer = peerOf(call) ?: return
        val verified = call.getBoolean("verified") ?: return call.reject("verified: boolean expected", JsDeviceTrust.INVALID_ARGUMENT)
        e2ee(call, "setVerified") { DeviceTrust.e2ee.setVerified(peer, verified); emptyMap() }
    }

    @PluginMethod
    fun e2eeResetSession(call: PluginCall) {
        val peer = peerOf(call) ?: return
        e2ee(call, "resetSession") { DeviceTrust.e2ee.resetSession(peer); emptyMap() }
    }

    /** The origin check and the peer of an `e2ee*` call; null — the call is already answered. */
    private fun peerOf(call: PluginCall): String? {
        if (!admitted(call)) return null
        if (!AppConfig.e2eeEnabled) {
            call.reject("secret chats are not configured in this build", JsDeviceTrust.UNAVAILABLE)
            return null
        }
        return JsDeviceTrust.peer(call.getString("peer")).also {
            if (it == null) call.reject("peer: account id expected", JsDeviceTrust.INVALID_ARGUMENT)
        }
    }

    /** The library's calls are suspend and main-safe; the log gets the operation and the code, never text or a peer. */
    private fun e2ee(call: PluginCall, op: String, block: suspend () -> Map<String, Any>) {
        scope.launch {
            try {
                val r = block()
                call.resolve(JSObject().apply { r.forEach { (k, v) -> put(k, v) } })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val code = JsDeviceTrust.e2eeErrorCode(e)
                Log.w(WolpApp.TAG, "DeviceTrust.e2ee $op refused: $code")
                call.reject("e2ee $op refused", code)
            }
        }
    }

    private fun purposeOf(call: PluginCall): VaultPurpose? =
        JsDeviceTrust.purpose(call.getString("purpose")).also {
            if (it == null) call.reject("purpose: e2ee-plaintext expected", JsDeviceTrust.INVALID_ARGUMENT)
        }

    /** Vault calls are suspend and main-safe (S2 §4); the reply is posted from the main thread. */
    private fun vault(call: PluginCall, op: String, block: suspend () -> Map<String, String>) {
        scope.launch {
            try {
                val r = block()
                call.resolve(JSObject().apply { r.forEach { (k, v) -> put(k, v) } })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val code = JsDeviceTrust.errorCode(e)
                Log.w(WolpApp.TAG, "DeviceTrust.vault $op failed: $code (${e.javaClass.simpleName})")
                call.reject("vault $op failed", code)
            }
        }
    }
}
