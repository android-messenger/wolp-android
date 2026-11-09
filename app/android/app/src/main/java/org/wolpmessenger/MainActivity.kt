package org.wolpmessenger

import android.os.Bundle
import com.getcapacitor.BridgeActivity
import com.getcapacitor.CapConfig

/**
 * The messenger (variant A, ADR-0001). Not exported: SplashActivity starts it only after the S1 §3
 * checks end in "to the messenger", because the Bridge created in onCreate loads app.<host> at once.
 * The Capacitor config is built here from BuildConfig rather than capacitor.config.json.
 */
class MainActivity : BridgeActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        config = CapConfig.Builder(this)
            .setServerUrl(AppConfig.appUrl)          // no path (ADR-0001 п. 1, P1-b)
            .setStartPath(AppConfig.START_PATH)
            .setAndroidScheme("https")
            .setAllowNavigation(arrayOf())           // enroll is allowed by WolpWebViewClient, not here
            .setAllowMixedContent(false)
            .setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
            .setLoggingEnabled(BuildConfig.DEBUG)
            .create()
        secureWindow()
        super.onCreate(savedInstanceState)
        // Installed synchronously after Bridge.create(): the first load's callbacks arrive later on the main looper.
        bridge.webViewClient = WolpWebViewClient(bridge)
    }
}
