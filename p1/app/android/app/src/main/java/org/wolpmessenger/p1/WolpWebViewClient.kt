package org.wolpmessenger.p1

import android.graphics.Bitmap
import android.net.http.SslError
import android.webkit.ClientCertRequest
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import com.getcapacitor.Bridge
import com.getcapacitor.BridgeWebViewClient

/**
 * S1 §4.1 client: mTLS via onReceivedClientCertRequest, plus P1 instrumentation.
 * P1-a evidence: every request to *.<host> is logged with whether Capacitor's local server answered it
 * (CAPACITOR-HANDLED = bypasses the WebView network stack) or it went to the network (WebView).
 */
class WolpWebViewClient(bridge: Bridge, private val host: String) : BridgeWebViewClient(bridge) {
    private val appHost = "app.$host"
    private val enrollHost = "enroll.$host"

    override fun onReceivedClientCertRequest(view: WebView, request: ClientCertRequest) {
        val c = ClientCreds.current()
        val issuers = request.principals?.joinToString { it.name }
        if (request.host == appHost && c != null) {
            P1Log.i("clientCertRequest ${request.host}:${request.port} keyTypes=${request.keyTypes?.joinToString()} issuers=[$issuers] -> proceed serial=${c.serial}")
            request.proceed(c.key, c.chain)
        } else {
            // ignore(), not cancel(): WebView remembers proceed/cancel until clearClientCertPreferences (S1 §4.1)
            P1Log.i("clientCertRequest ${request.host}:${request.port} -> ignore (creds=${c != null})")
            request.ignore()
        }
    }

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        val r = super.shouldInterceptRequest(view, request)
        val h = request.url.host ?: ""
        if (h.endsWith(host)) {
            P1Log.i("request ${request.method} ${request.url} main=${request.isForMainFrame} -> ${if (r == null) "network(WebView)" else "CAPACITOR-HANDLED"}")
        }
        return r
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val h = request.url.host ?: ""
        // Navigation between app/enroll stays in the WebView without putting enroll into Capacitor's
        // allowNavigation (which would also add it to the bridge's allowed origins, see README P1-b).
        if (h == appHost || h == enrollHost || h == "no-sct.badssl.com") {
            P1Log.i("navigate ${request.url} (in WebView)")
            return false
        }
        return super.shouldOverrideUrlLoading(view, request)
    }

    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        if (url?.contains(appHost) == true) Timings.mark("pageStarted(app)")
        P1Log.i("pageStarted $url")
    }

    override fun onPageFinished(view: WebView, url: String?) {
        super.onPageFinished(view, url)
        if (url?.contains(appHost) == true) Timings.mark("pageFinished(app)")
        P1Log.i("pageFinished $url")
    }

    override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
        super.doUpdateVisitedHistory(view, url, isReload)
        // §3.1: SPA login on enroll -> pushState; detect the session cookie natively
        val cookie = CookieManager.getInstance().getCookie("https://$enrollHost") ?: ""
        P1Log.i("visitedHistory $url session-cookie-visible=${cookie.contains("ory_kratos_session")}")
    }

    override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
        super.onReceivedHttpError(view, request, errorResponse)
        P1Log.i("httpError ${errorResponse.statusCode} ${request.url} main=${request.isForMainFrame} X-Device-Deny=${errorResponse.responseHeaders?.get("X-Device-Deny")}")
    }

    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
        super.onReceivedError(view, request, error)
        P1Log.i("error ${error.errorCode} '${error.description}' ${request.url} main=${request.isForMainFrame}")
    }

    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
        P1Log.i("sslError primary=${error.primaryError} ${error.url} -> cancel")
        handler.cancel()
    }
}
