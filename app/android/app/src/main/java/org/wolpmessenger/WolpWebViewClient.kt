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

/** What the WebView reports to the activity that owns it (S1 §4). Called on the main thread. */
interface WebHost {
    /** A client certificate was asked for on app.<host> and S2 has none: the request was `ignore()`d. */
    fun onNoCredentials()
    fun onDeny(code: GatewayCode, action: DenyAction)
    /** TLS to our own host failed verification (chain or NSC pin, S1 §4.3); never proceeded. */
    fun onServerUnverified()
    /** The renderer died or was killed; the WebView is unusable and must be destroyed (S1 §4.4). */
    fun onRendererGone(view: WebView)
}

/** WebView side of S1 §4.1–4.4 (native rules; what to do about them is the activity's job). */
class WolpWebViewClient(bridge: Bridge, private val host: WebHost) : BridgeWebViewClient(bridge) {
    private val app = Uri.parse(AppConfig.appUrl)
    private val enroll = Uri.parse(AppConfig.enrollUrl)

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
        // enroll stays in the WebView without being in allowNavigation, which would also make it an
        // allowed bridge origin (ADR-0001 п. 2)
        if (request.url.isSameOrigin(app) || request.url.isSameOrigin(enroll)) return false
        return super.shouldOverrideUrlLoading(view, request)
    }

    /** S1 §4.2: only the `X-Device-Deny` header counts, and only from app.<host>. */
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
