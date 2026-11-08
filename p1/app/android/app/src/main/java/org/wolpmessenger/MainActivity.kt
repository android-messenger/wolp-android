package org.wolpmessenger

import android.os.Bundle
import android.webkit.WebView
import com.getcapacitor.BridgeActivity
import org.wolpmessenger.p1.P1Log
import org.wolpmessenger.p1.P1ProbePlugin
import org.wolpmessenger.p1.Timings
import org.wolpmessenger.p1.WolpWebViewClient

/**
 * Variant A (Capacitor). Capacitor's Bridge must be created in onCreate (it registers ActivityResult
 * launchers) and loads server.url immediately, so the S1 §3 gate lives in SplashActivity:
 * MainActivity is only started once credentials exist.
 */
class MainActivity : BridgeActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        Timings.mark("main.onCreate")
        registerPlugin(P1ProbePlugin::class.java)
        if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)
        super.onCreate(savedInstanceState)
        // Installed synchronously after Bridge.create(): the first load's callbacks arrive later on the main looper.
        bridge.setWebViewClient(WolpWebViewClient(bridge, BuildConfig.WOLP_HOST))
        Timings.mark("bridgeCreated")
        P1Log.i("bridge: allowedOriginRules=${bridge.allowedOriginRules} serverUrl=${bridge.serverUrl}")
    }
}
