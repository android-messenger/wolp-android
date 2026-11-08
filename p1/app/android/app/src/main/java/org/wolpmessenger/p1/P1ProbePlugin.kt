package org.wolpmessenger.p1

import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.webkit.CookieManager
import android.webkit.WebView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.webkit.WebViewCompat
import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import okhttp3.Request
import org.wolpmessenger.BuildConfig
import kotlin.concurrent.thread

/** Probe plugin for the P1 checks. Not part of the product API (§5). */
@CapacitorPlugin(name = "P1Probe")
class P1ProbePlugin : Plugin() {
    private val host = BuildConfig.WOLP_HOST

    // P1-h tick bookkeeping
    private var lastTick = 0L
    private var ticks = 0
    private var maxLevel = 0
    private val gaps = ArrayList<String>()

    private fun obj(vararg kv: Pair<String, Any?>) = JSObject().apply { kv.forEach { (k, v) -> put(k, v) } }

    @PluginMethod fun info(call: PluginCall) {
        val wv = bridge.webView
        call.resolve(obj(
            "webViewUrl" to wv.url,
            "webViewPackage" to WebViewCompat.getCurrentWebViewPackage(context)?.versionName,
            "sdk" to Build.VERSION.SDK_INT,
            "allowedOriginRules" to JSArray(bridge.allowedOriginRules.toList()),
            "serial" to ClientCreds.current()?.serial,
            "credentialsVersion" to ClientCreds.credentialsVersion,
        ))
    }

    @PluginMethod fun log(call: PluginCall) { P1Log.i("js: ${call.getString("line")}"); call.resolve() }

    @PluginMethod fun markJsReady(call: PluginCall) { Timings.mark("jsReady(app)"); call.resolve() }

    @PluginMethod fun timings(call: PluginCall) {
        val o = JSObject(); Timings.snapshot().forEach { (k, v) -> o.put(k, v) }; call.resolve(o)
    }

    /** P1-c: is the HttpOnly Kratos cookie visible to CookieManager, and is it shared between hosts? */
    @PluginMethod fun cookies(call: PluginCall) {
        val cm = CookieManager.getInstance()
        val app = cm.getCookie("https://app.$host") ?: ""
        val enroll = cm.getCookie("https://enroll.$host") ?: ""
        call.resolve(obj(
            "appHasSession" to app.contains("ory_kratos_session="),
            "enrollHasSession" to enroll.contains("ory_kratos_session="),
            "app" to app.replace(Regex("ory_kratos_session=([0-9a-f]{6})[0-9a-f]+"), "ory_kratos_session=$1…"),
        ))
    }

    /** P1-e: new key + cert, then clearClientCertPreferences; the page re-runs requests and watches its open WS. */
    @PluginMethod fun rotateCert(call: PluginCall) {
        val old = ClientCreds.current()?.serial
        thread {
            try {
                val c = ClientCreds.rotate(context, host)
                activity.runOnUiThread {
                    WebView.clearClientCertPreferences {
                        P1Log.i("clearClientCertPreferences done (v=${ClientCreds.credentialsVersion})")
                        call.resolve(obj("old" to old, "new" to c.serial, "credentialsVersion" to ClientCreds.credentialsVersion))
                    }
                }
            } catch (e: Exception) { P1Log.w("rotate failed", e); call.reject(e.toString()) }
        }
    }

    /** §4.2 native ping through mTLS OkHttp (pinbogus flavor: must fail). */
    @PluginMethod fun nativePing(call: PluginCall) {
        thread {
            try {
                val r = ClientCreds.mtlsClient().newCall(
                    Request.Builder().url("https://app.$host/v1/device/ping").header("User-Agent", "WolpNative/p1").build()
                ).execute()
                val body = r.use { "${it.code} ${it.body?.string()}" }
                P1Log.i("nativePing -> $body")
                call.resolve(obj("result" to body))
            } catch (e: Exception) {
                P1Log.i("nativePing failed: $e")
                call.resolve(obj("error" to e.toString()))
            }
        }
    }

    /** P1-h: JS calls this every second; gaps > 2.5 s mean JS timers were paused/throttled. */
    @PluginMethod fun tick(call: PluginCall) {
        val now = SystemClock.elapsedRealtime()
        ticks++
        maxLevel = maxOf(maxLevel, call.getInt("level") ?: 0)
        if (lastTick != 0L && now - lastTick > 2500) {
            val pm = context.getSystemService(PowerManager::class.java)
            val fg = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            val g = "gap ${now - lastTick} ms (fg=$fg screenOn=${pm.isInteractive} callService=${CallService.active} ws=${call.getInt("ws")})"
            gaps.add(g); P1Log.i("tick $g")
        }
        lastTick = now
        call.resolve()
    }

    @PluginMethod fun tickReport(call: PluginCall) {
        call.resolve(obj("ticks" to ticks, "maxMicLevel" to maxLevel, "gaps" to JSArray(gaps.takeLast(20))))
        maxLevel = 0
    }

    @PluginMethod fun callStarted(call: PluginCall) { CallService.start(context); call.resolve(obj("started" to true)) }
    @PluginMethod fun callEnded(call: PluginCall) { CallService.stop(context); call.resolve(obj("stopped" to true)) }
}
