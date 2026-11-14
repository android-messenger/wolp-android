package org.wolpmessenger

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.addCallback
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import com.getcapacitor.BridgeActivity
import com.getcapacitor.CapConfig
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import org.wolpmessenger.devicetrust.DeviceTrust
import org.wolpmessenger.devicetrust.api.DenySource
import org.wolpmessenger.devicetrust.api.GatewayCode
import org.wolpmessenger.devicetrust.api.SessionBinding
import org.wolpmessenger.devicetrust.api.TrustState
import org.wolpmessenger.launch.MinVersion
import org.wolpmessenger.launch.AndroidProbes
import org.wolpmessenger.launch.LaunchGate
import org.wolpmessenger.launch.LaunchHandoff
import org.wolpmessenger.launch.Screen
import org.wolpmessenger.call.CallService
import org.wolpmessenger.plugins.AppSettingsPlugin
import org.wolpmessenger.plugins.CallPlugin
import org.wolpmessenger.plugins.DeviceTrustPlugin
import org.wolpmessenger.plugins.FilesPlugin
import org.wolpmessenger.plugins.PushPlugin
import org.wolpmessenger.push.BackgroundController
import org.wolpmessenger.push.NotificationTap
import org.wolpmessenger.web.DenyAction
import org.wolpmessenger.web.WebData

/**
 * The messenger (variant A, ADR-0001). Not exported: SplashActivity starts it only after the S1 §3
 * checks end in "to the messenger", because the Bridge created in onCreate loads app.<host> at once.
 * The Capacitor config is built here from BuildConfig rather than capacitor.config.json.
 *
 * S1-3: whenever the §3 order no longer ends in [Screen.Messenger] (state change, deny, renderer
 * crash, long background) this activity hands over to SplashActivity and finishes; when it still
 * does, the WebView is not reloaded (§3) — certificate changes go through §4.1 instead.
 *
 * The Bridge is created only with SplashActivity's clearance ([LaunchHandoff]). An instance the system
 * restores after the process died, or one started by anything else (the ongoing-call notification after
 * this activity was destroyed), has none: it creates no Bridge and goes back to the order.
 */
class MainActivity : BridgeActivity(), WebHost {
    private val gate by lazy { LaunchGate(AndroidProbes(applicationContext)) }
    private var evaluation: Job? = null
    private var leaving = false
    /** The S1 §3 order cleared this instance: only then a Bridge exists. */
    private var gated = false
    private var stoppedAt = 0L
    private var lastCertReload = 0L
    private var vaultResetScript: ScriptHandler? = null
    private val pushPlugin: PushPlugin? get() = bridge?.getPlugin("Push")?.instance as? PushPlugin
    private val background = BackgroundController(
        plugin = { pushPlugin },
        park = { bridge?.webView?.loadUrl("about:blank") },
        unpark = { bridge?.webView?.loadUrl(AppConfig.uiUrl) },
    )

    /** BridgeActivity.onCreate calls this to create the Bridge, which loads the UI at once. */
    override fun load() {
        if (gated) super.load()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        gated = LaunchHandoff.takeClearance()
        config = CapConfig.Builder(this)
            // security/09: the UI comes from the APK (assets/public) as https://<uiHost>/, no server.url
            .setHostname(AppConfig.uiHost)
            .setAndroidScheme("https")
            .setAllowNavigation(arrayOf())           // enroll is allowed by WolpWebViewClient, not here
            .setAllowMixedContent(false)
            .setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
            .setLoggingEnabled(BuildConfig.DEBUG)
            .create()
        secureWindow()
        registerPlugin(DeviceTrustPlugin::class.java) // its load() installs the bridge guard (S1 §5)
        registerPlugin(FilesPlugin::class.java)       // S1 §5.4: saving attachments
        registerPlugin(PushPlugin::class.java)        // S1 §5.2, §7.0
        registerPlugin(CallPlugin::class.java)        // S1 §5.3, §4.5
        registerPlugin(AppSettingsPlugin::class.java) // S1 §5.5: language of native texts
        // not cleared: no saved state either — nothing of the previous instance is restored
        super.onCreate(if (gated) savedInstanceState else null)
        if (!gated) {
            Log.i(WolpApp.TAG, "messenger started without the launch order: back to it")
            leave()
            return
        }
        // Installed synchronously after Bridge.create(): the first load's callbacks arrive later on the main looper.
        bridge.webViewClient = WolpWebViewClient(bridge, this)
        // S1 §4.5 (P1-h): with Capacitor's KeepRunning (default true) Bridge.onPause never calls
        // webView.onPause()/pauseTimers(), so JS and media go on during a call in the background.
        if (!bridge.shouldKeepRunning()) Log.e(WolpApp.TAG, "KeepRunning is off: a call would stop when minimised")
        bridge.webView.webChromeClient = WolpWebChromeClient(bridge) // registers its launchers: before STARTED
        if (WebData.vaultResetPending(this)) applyVaultReset()
        pushPlugin?.onWsClosed = background::onWsClosed
        background.attach()
        deliverTap(intent)

        // S1 §4.4 «Назад»: history.back(), at the root — to the background
        onBackPressedDispatcher.addCallback(this) {
            val wv = bridge?.webView
            if (wv != null && wv.canGoBack()) wv.goBack() else moveTaskToBack(true)
        }

        // S1 §4.1: every credentialsVersion change (and a difference from the last handled value at start)
        lifecycleScope.launch {
            DeviceTrust.credentialsVersion.collect { WebData.onCredentialsVersion(this@MainActivity, it) }
        }
        // S1 §3: any S2 state change re-runs the order; still Messenger → nothing, otherwise hand over
        lifecycleScope.launch {
            DeviceTrust.state.drop(1).collect { reevaluate("state $it") }
        }
        // S1 §4.2: ping on every resume and every 5 min in the foreground; a long background re-runs §3
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                val away = if (stoppedAt == 0L) 0L else SystemClock.elapsedRealtime() - stoppedAt
                stoppedAt = 0L // a later pause/resume without onStop (permission dialog) is not "background"
                if (away > BACKGROUND_RECHECK_MS) reevaluate("back after ${away / 1000} s") else ping()
                while (true) {
                    delay(PING_INTERVAL_MS)
                    ping()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        LaunchHandoff.takeClearance() // SplashActivity cleared a start that reached this instance: used up
        if (gated) deliverTap(intent)
    }

    override fun onDestroy() {
        if (gated) {
            background.detach()
            CallService.webViewGone(this) // §4.5: without Call.ended() within 10 s the call service stops itself
            // re-created in place (a configuration change in the same process): the order has been passed
            if (isChangingConfigurations && !leaving) LaunchHandoff.clearMessenger()
        }
        super.onDestroy()
    }

    /** A notification tap (via SplashActivity) → `notificationTap`, retained until the web app listens. */
    private fun deliverTap(intent: Intent?) {
        val tap = NotificationTap.from(intent?.data) ?: return
        intent?.data = null
        pushPlugin?.emitTap(tap)
    }

    override fun onStart() {
        if (gated) background.mainStarted() // before super: ProcessLifecycleOwner's ON_START follows our onStart
        super.onStart()
    }

    override fun onStop() {
        super.onStop()
        if (!gated) return
        background.mainStopped()
        stoppedAt = SystemClock.elapsedRealtime()
    }

    private suspend fun ping() {
        when (Gateway.ping()) {
            Gateway.Ping.ServerUnverified -> leave(SplashActivity.REASON_SERVER_UNVERIFIED)
            // the gateway raised the minimum version: show «update» at once (S1 §3 step 4)
            Gateway.Ping.Ok -> if (MinVersion.outdated()) leave()
            // offline: the web app shows its own state; denials already went to S2 and come back as a state change
            Gateway.Ping.Offline -> Unit
        }
    }

    private fun reevaluate(why: String) {
        if (leaving) return
        evaluation?.cancel()
        evaluation = lifecycleScope.launch {
            val s = gate.evaluate()
            Log.i(WolpApp.TAG, "messenger re-check ($why) → $s")
            if (s != Screen.Messenger) leave()
        }
    }

    /** Back to the S1 §3 order in SplashActivity (it shows the screen); this WebView goes with the activity. */
    private fun leave(reason: String? = null) {
        if (leaving) return
        leaving = true
        if (reason != null) LaunchHandoff.offerReason(reason) // not an Intent extra: SplashActivity is exported
        startActivity(Intent(this, SplashActivity::class.java))
        finish()
    }

    // ---------------------------------------------------------------- WebHost (S1 §4)

    override fun onNoCredentials() {
        lifecycleScope.launch {
            val s = DeviceTrust.ensureFresh()
            val now = SystemClock.elapsedRealtime()
            // reload only if a certificate is there now, at most once per 30 s (no reload loop); else §3 decides
            if ((s is TrustState.Active || s is TrustState.RenewalDue) && now - lastCertReload > CERT_RELOAD_GAP_MS) {
                lastCertReload = now
                bridge.webView.reload()
            } else reevaluate("no client certificate, state $s")
        }
    }

    override fun onDeny(code: GatewayCode, action: DenyAction) {
        when (action) {
            DenyAction.NONE -> Unit
            DenyAction.RELOGIN -> leave() // §3 step 6 decides (whoami); the device state is untouched
            DenyAction.REFRESH -> lifecycleScope.launch { DeviceTrust.ensureFresh() } // a change re-runs §3
            DenyAction.VERIFY -> lifecycleScope.launch { DeviceTrust.onGatewayDenied(code, DenySource.WEBVIEW) }
            DenyAction.CHECK_BINDING -> lifecycleScope.launch { checkBinding() }
            DenyAction.CHECK_SESSION -> lifecycleScope.launch {
                // Kratos itself, not the gateway: its whoami answers from the same check cache
                if (Gateway.hasSession()) Log.i(WolpApp.TAG, "401 without X-Device-Deny, but the Kratos session is alive")
                else leave()
            }
        }
    }

    /** S1 §4.2 / §9: USER_MISMATCH acts only when the pinned `whoami` confirms it. */
    private suspend fun checkBinding() {
        val cookie = AppConfig.kratosCookie() ?: return leave()
        when (val b = DeviceTrust.checkSessionBinding(cookie)) {
            SessionBinding.Mismatch -> {
                Log.w(WolpApp.TAG, "session belongs to another user (confirmed): logging out")
                Gateway.logoutKratos()
                WebData.clearSession()
                leave(SplashActivity.REASON_WRONG_USER)
            }
            SessionBinding.NoSession -> leave()
            SessionBinding.Match, is SessionBinding.Failed -> Log.i(WolpApp.TAG, "USER_MISMATCH not confirmed: $b")
        }
    }

    override fun onServerUnverified() = leave(SplashActivity.REASON_SERVER_UNVERIFIED)

    /**
     * S1 §3.1 (v2.13): the web client logged out or lost its session and shows its own login page.
     * No session in Kratos → §3 (LoginActivity), and the WebView forgets its client-certificate choice
     * as on any logout (§4.1). Session still alive → back to the web client's start page.
     */
    override fun onWebLoginRoute() {
        if (leaving) return
        lifecycleScope.launch {
            when (Gateway.session()) {
                Gateway.Session.ALIVE -> bridge.webView.loadUrl(AppConfig.uiUrl)
                Gateway.Session.NONE -> {
                    WebView.clearClientCertPreferences(null)
                    // the session is gone without the web client's own logout (which calls Push.unregister first):
                    // no pushes for a user who is no longer signed in, no names or payload key left behind
                    (application as WolpApp).clearPush()
                    leave()
                }
                // Kratos did not answer: this is not a sign-out. Nothing of push is erased (spec 002 §3.4 —
                // a network failure here used to stop every notification); the start screen asks again.
                Gateway.Session.UNKNOWN -> {
                    Log.i(WolpApp.TAG, "login route, session unknown: push state kept")
                    leave()
                }
            }
        }
    }

    override fun onRendererGone(view: WebView) {
        // the dead WebView must leave the hierarchy and be destroyed before anything touches it again
        (view.parent as? ViewGroup)?.removeView(view)
        view.destroy()
        leave()
    }

    /** S1 §3 VaultReset: drop the web app's E2EE stores before its first script on the UI origin. */
    private fun applyVaultReset() {
        Log.i(WolpApp.TAG, "vault reset: clearing ${WebData.E2EE_DATABASES} before the web app loads")
        val wv = bridge.webView
        val origins = setOf(AppConfig.uiOrigin)
        WebViewCompat.addWebMessageListener(wv, WebData.VAULT_RESET_LISTENER, origins) { _, message, _, isMainFrame, _ ->
            if (!isMainFrame || message.data != "done" || vaultResetScript == null) return@addWebMessageListener
            vaultResetScript?.remove()
            vaultResetScript = null
            WebViewCompat.removeWebMessageListener(wv, WebData.VAULT_RESET_LISTENER)
            WebData.vaultResetDone(this)
            Log.i(WolpApp.TAG, "vault reset: web app E2EE stores cleared")
        }
        vaultResetScript = WebViewCompat.addDocumentStartJavaScript(wv, WebData.vaultResetScript, origins)
        // the Bridge's first load may already be under way without the script: start it over
        wv.loadUrl(AppConfig.uiUrl)
    }

    private companion object {
        const val PING_INTERVAL_MS = 5 * 60_000L
        const val BACKGROUND_RECHECK_MS = 5 * 60_000L
        const val CERT_RELOAD_GAP_MS = 30_000L
    }
}
