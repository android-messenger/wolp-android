package org.wolpmessenger.plugins

import android.util.Log
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import org.wolpmessenger.AppConfig
import org.wolpmessenger.AppLocale
import org.wolpmessenger.WolpApp
import org.wolpmessenger.web.BridgePolicy
import org.wolpmessenger.web.CallOrigins
import org.wolpmessenger.web.JsDeviceTrust

/** `AppSettings` plugin (S1 §5.5): app-wide settings from the web UI. Same origin rule as `DeviceTrust` (S1 §5). */
@CapacitorPlugin(name = "AppSettings")
class AppSettingsPlugin : Plugin() {

    private fun admitted(call: PluginCall): Boolean {
        if (CallOrigins.allowed(call.callbackId, AppConfig.uiOrigin)) return true
        Log.w(WolpApp.TAG, "AppSettings.${call.methodName}: refused (not the app's main frame)")
        call.reject("plugin call refused", BridgePolicy.PERMISSION_DENIED)
        return false
    }

    /** `{lang: "en" | "es"}` — the web UI's language for native screens and notifications. */
    @PluginMethod
    fun setLanguage(call: PluginCall) {
        if (!admitted(call)) return
        val lang = AppLocale.normalize(call.getString("lang"))
            ?: return call.reject("lang: one of ${AppLocale.SUPPORTED}", JsDeviceTrust.INVALID_ARGUMENT)
        AppLocale.apply(context, lang)
        call.resolve()
    }
}
