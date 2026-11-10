package org.wolpmessenger.launch

import android.content.Context
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.wolpmessenger.BuildConfig
import org.wolpmessenger.Gateway
import org.wolpmessenger.devicetrust.DeviceTrust
import org.wolpmessenger.devicetrust.api.PrecheckResult
import org.wolpmessenger.devicetrust.api.TrustState

class AndroidProbes(private val context: Context) : LaunchProbes {
    override fun trustState(): TrustState = DeviceTrust.state.value

    override fun webViewUsable(): Boolean = webViewUsable(context)

    override suspend fun precheck(): PrecheckResult = DeviceTrust.precheck()

    /**
     * Until S1-8 (update manifests): the gateway's minimum from the last ping ([MinVersion]). When it
     * says «outdated», ask once more — the minimum may have been lowered (needs the certificate).
     */
    override suspend fun mandatoryUpdate(): Boolean {
        if (!MinVersion.outdated()) return false
        val s = trustState()
        if (s !is TrustState.NotEnrolled && s !is TrustState.ReEnrollRequired) Gateway.ping()
        return MinVersion.outdated()
    }

    override suspend fun reachable(enrolled: Boolean): Reachability =
        if (!enrolled) {
            if (Gateway.enrollReachable()) Reachability.OK else Reachability.OFFLINE
        } else when (Gateway.ping()) {
            Gateway.Ping.Ok -> Reachability.OK
            Gateway.Ping.Offline -> Reachability.OFFLINE
            Gateway.Ping.ServerUnverified -> Reachability.SERVER_UNVERIFIED
        }

    override suspend fun hasSession(): Boolean = Gateway.hasSession()

    override suspend fun ensureFresh(): TrustState = DeviceTrust.ensureFresh()

    companion object {
        /** S1 §3 step 1 and ADR-0001 п. 4: without DOCUMENT_START_SCRIPT Capacitor proxies the document (mTLS breaks). */
        fun webViewUsable(context: Context): Boolean {
            val pkg = WebViewCompat.getCurrentWebViewPackage(context) ?: return false
            val major = pkg.versionName?.substringBefore('.')?.toIntOrNull() ?: return false
            return major >= BuildConfig.MIN_WEBVIEW_MAJOR &&
                WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) &&
                WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)
        }
    }
}
