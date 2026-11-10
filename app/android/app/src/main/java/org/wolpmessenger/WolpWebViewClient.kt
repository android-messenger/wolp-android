package org.wolpmessenger

import android.net.Uri
import android.net.http.SslError
import android.util.Log
import android.webkit.ClientCertRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import com.getcapacitor.Bridge
import com.getcapacitor.BridgeWebViewClient
import org.wolpmessenger.devicetrust.DeviceTrust
import org.wolpmessenger.devicetrust.api.GatewayCode
import org.wolpmessenger.web.DenyAction
import org.wolpmessenger.web.DenyPolicy
import org.wolpmessenger.web.WebRoutes

/** What the WebView reports to the activity that owns it (S1 §4). Called on the main thread. */
interface WebHost {
    /** A client certificate was asked for on app.<host> and S2 has none: the request was `ignore()`d. */
    fun onNoCredentials()
    fun onDeny(code: GatewayCode, action: DenyAction)
    /** TLS to our own host failed verification (chain or NSC pin, S1 §4.3); never proceeded. */
    fun onServerUnverified()
    /** The renderer died or was killed; the WebView is unusable and must be destroyed (S1 §4.4). */
    fun onRendererGone(view: WebView)
    /** The web client went to its own login route (logout or lost session): login is native (S1 §3.1). */
    fun onWebLoginRoute()
}

/** WebView side of S1 §4.1–4.4 (native rules; what to do about them is the activity's job). */
class WolpWebViewClient(bridge: Bridge, private val host: WebHost) : BridgeWebViewClient(bridge) {
    private val app = Uri.parse(AppConfig.appUrl)
    private val enroll = Uri.parse(AppConfig.enrollUrl)
    private val ui = Uri.parse(AppConfig.uiOrigin)

    override fun onReceivedClientCertRequest(view: WebView, request: ClientCertRequest) {
        if (request.host != app.host || request.port != app.portOrDefault()) {
            request.ignore()
            return
        }
        val creds = DeviceTrust.clientCredentials()
        if (creds != null) {
            request.proceed(creds.privateKey, creds.chain)
        } else {
            // ignore(), not cancel(): WebView remembers proceed/cancel until clearClientCertPreferences (S1 §4.1)
            request.ignore()
            host.onNoCredentials()
        }
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        // the packaged UI and enroll stay in the WebView (enroll without being in allowNavigation, which
        // would also make it an allowed bridge origin, ADR-0001 п. 2)
        if (request.url.isSameOrigin(ui) || request.url.isSameOrigin(enroll)) return false
        // security/09: the API host never gets a document of its own in the app's WebView
        if (request.url.isSameOrigin(app)) {
            Log.w(WolpApp.TAG, "navigation to the API host refused: ${request.url.path}")
            return true
        }
        return super.shouldOverrideUrlLoading(view, request)
    }

    /** SPA navigation (pushState) included: the web client's login route → native login. */
    override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
        super.doUpdateVisitedHistory(view, url, isReload)
        val u = Uri.parse(url ?: return)
        if (u.isSameOrigin(ui) && WebRoutes.isLoginRoute(u.path, AppConfig.START_PATH)) {
            Log.i(WolpApp.TAG, "web client went to ${u.path}: native login")
            host.onWebLoginRoute()
        }
    }

    /**
     * S1 §4.2: only the `X-Device-Deny` header counts, and only from app.<host>. With the UI in the APK
     * these are XHR/fetch responses (never the main frame): the header must be in the gateway's
     * `Access-Control-Expose-Headers` only for the web app; the WebView reports it here regardless.
     */
    override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
        super.onReceivedHttpError(view, request, errorResponse)
        if (!request.url.isSameOrigin(app)) return
        val header = errorResponse.responseHeaders?.entries?.firstOrNull { it.key.equals(DENY_HEADER, ignoreCase = true) }?.value
        if (header == null) {
            val action = DenyPolicy.forUnmarked(errorResponse.statusCode, request.isForMainFrame)
            if (action != DenyAction.NONE) {
                Log.i(WolpApp.TAG, "webview ${errorResponse.statusCode} without $DENY_HEADER on the document → $action")
                host.onDeny(GatewayCode.UNKNOWN, action)
            }
            return
        }
        val code = GatewayCode.parse(header.trim())
        val action = DenyPolicy.forWebView(code, request.isForMainFrame)
        Log.i(WolpApp.TAG, "webview deny $code (${errorResponse.statusCode}, mainFrame=${request.isForMainFrame}) → $action")
        if (action != DenyAction.NONE) host.onDeny(code, action)
    }

    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
        handler.cancel() // never proceed (S1 §4.3)
        val url = Uri.parse(error.url ?: "")
        Log.w(WolpApp.TAG, "webview TLS error ${error.primaryError} on ${url.host}:${url.port}")
        if (url.isSameOrigin(app) || url.isSameOrigin(enroll)) host.onServerUnverified()
    }

    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        super.onRenderProcessGone(view, detail)
        Log.w(WolpApp.TAG, "webview renderer gone (crashed=${detail.didCrash()}, priority=${detail.rendererPriorityAtExit()})")
        host.onRendererGone(view)
        return true // the app survives; the WebView is replaced (S1 §4.4)
    }

    companion object {
        const val DENY_HEADER = "X-Device-Deny"
    }
}

internal fun Uri.portOrDefault(): Int = if (port != -1) port else if (scheme == "https") 443 else 80
internal fun Uri.isSameOrigin(o: Uri): Boolean = scheme == o.scheme && host == o.host && portOrDefault() == o.portOrDefault()
