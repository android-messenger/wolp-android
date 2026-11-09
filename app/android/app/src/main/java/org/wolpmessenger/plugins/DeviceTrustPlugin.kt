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
 * through [BridgeGuard] from the main frame of `app.<host>` (S1 §5); `enroll`, iframes and calls
 * that bypassed the guard get `PERMISSION_DENIED`. No method returns key material.
 */
@CapacitorPlugin(name = "DeviceTrust")
class DeviceTrustPlugin : Plugin() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun load() {
        // Runs inside Bridge construction, before server.url is loaded (see BridgeGuard).
        BridgeGuard.install(bridge, AppConfig.appOrigin)
    }

    override fun handleOnDestroy() {
        scope.cancel()
    }

    private fun admitted(call: PluginCall): Boolean {
        if (CallOrigins.allowed(call.callbackId, AppConfig.appOrigin)) return true
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

    private fun purposeOf(call: PluginCall): VaultPurpose? =
        JsDeviceTrust.purpose(call.getString("purpose")).also {
            if (it == null) call.reject("purpose: e2ee or e2ee-plaintext expected", JsDeviceTrust.INVALID_ARGUMENT)
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
