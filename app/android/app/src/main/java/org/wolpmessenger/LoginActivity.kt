package org.wolpmessenger

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.util.Log
import android.webkit.ClientCertRequest
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * S1 §3 step 6 and §3.1: Kratos login on enroll.<host> in a plain WebView — no Capacitor bridge
 * exists here (plan A6), so no plugin is reachable from enroll. The login is an SPA (pushState, maybe
 * no onPageFinished): the session is detected by doUpdateVisitedHistory, onPageFinished and a 1 s
 * cookie poll, each confirmed by a native `whoami`. Finishes with RESULT_OK once a session exists.
 */
class LoginActivity : ComponentActivity() {
    private lateinit var web: WebView
    private var checking: Job? = null
    private val enroll = Uri.parse(AppConfig.enrollUrl)

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        secureWindow()
        if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true) // chrome://inspect on the stand
        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            webViewClient = Client()
        }
        setContentView(web)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if (web.canGoBack()) web.goBack() else finish() }
        })
        if (savedInstanceState == null) web.loadUrl(AppConfig.enrollUrl + LOGIN_PATH) else web.restoreState(savedInstanceState)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) { checkSession(); delay(1000) }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }

    private fun checkSession() {
        if (checking?.isActive == true || AppConfig.kratosCookie() == null) return
        checking = lifecycleScope.launch {
            if (Gateway.hasSession()) {
                Log.i(WolpApp.TAG, "login: Kratos session confirmed")
                setResult(Activity.RESULT_OK)
                finish()
            }
        }
    }

    private inner class Client : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
            !request.url.isSameOrigin(enroll) // only enroll.<host>; everything else stays out

        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) = checkSession()
        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) = Unit
        override fun onPageFinished(view: WebView, url: String?) = checkSession()

        override fun onReceivedClientCertRequest(view: WebView, request: ClientCertRequest) = request.ignore()

        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
            Log.w(WolpApp.TAG, "login: TLS error ${error.primaryError} — refused")
            handler.cancel()
        }
    }

    companion object {
        /** messenger-ui's login page (S1 §3 step 6). */
        const val LOGIN_PATH = "/messenger-ui/login"
    }
}
