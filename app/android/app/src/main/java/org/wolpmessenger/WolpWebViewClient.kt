package org.wolpmessenger

import android.net.Uri
import android.net.http.SslError
import android.webkit.ClientCertRequest
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebView
import com.getcapacitor.Bridge
import com.getcapacitor.BridgeWebViewClient
import org.wolpmessenger.devicetrust.DeviceTrust

/** WebView side of S1 §4 (the client certificate part; deny handling and ping arrive in S1-3). */
class WolpWebViewClient(bridge: Bridge) : BridgeWebViewClient(bridge) {
    private val app = Uri.parse(AppConfig.appUrl)
    private val enroll = Uri.parse(AppConfig.enrollUrl)

    override fun onReceivedClientCertRequest(view: WebView, request: ClientCertRequest) {
        val creds = DeviceTrust.clientCredentials()
        if (request.host == app.host && request.port == app.portOrDefault() && creds != null) {
            request.proceed(creds.privateKey, creds.chain)
        } else {
            // ignore(), not cancel(): WebView remembers proceed/cancel until clearClientCertPreferences (S1 §4.1)
            request.ignore()
        }
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        // enroll stays in the WebView without being in allowNavigation, which would also make it an
        // allowed bridge origin (ADR-0001 п. 2)
        if (request.url.isSameOrigin(app) || request.url.isSameOrigin(enroll)) return false
        return super.shouldOverrideUrlLoading(view, request)
    }

    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
        handler.cancel() // never proceed (S1 §4.3); the offline screen comes with S1-3
    }
}

internal fun Uri.portOrDefault(): Int = if (port != -1) port else if (scheme == "https") 443 else 80
internal fun Uri.isSameOrigin(o: Uri): Boolean = scheme == o.scheme && host == o.host && portOrDefault() == o.portOrDefault()
