package org.wolpmessenger.web

import org.wolpmessenger.devicetrust.api.GatewayCode

/** What the native side does with an `X-Device-Deny` seen in the WebView (S1 §4.2). */
enum class DenyAction {
    /** Nothing natively (sub-resource NO_SESSION: the web app's authErrorMiddleware handles it; unknown codes). */
    NONE,
    /** NO_SESSION on the app document: the web app is not loaded, so the native side goes to step 6 itself. */
    RELOGIN,
    /** USER_MISMATCH: confirm over the pinned channel (`checkSessionBinding`), never act on the header alone. */
    CHECK_BINDING,
    /** CERT_*: `ensureFresh()`. */
    REFRESH,
    /**
     * Device codes (and UPDATE_REQUIRED, which `check` never sends): `onGatewayDenied(code, WEBVIEW)` —
     * S2 only verifies by a pinned ping (S2 §5.3); nothing is deleted on the header's word.
     */
    VERIFY,
    /**
     * 401 on the app document **without** `X-Device-Deny` (HANDOFF question 8): Oathkeeper rejected a
     * Kratos session the gateway's check cache still held (`DEVICES_CHECK_CACHE_TTL`). The native side
     * asks Kratos directly and goes to login only if the session is really gone.
     */
    CHECK_SESSION,
}

/** Pure mapping, JVM-tested (the header may come from a spoofed server: S1 §10 «Подмена сервера»). */
object DenyPolicy {
    fun forWebView(code: GatewayCode, mainFrame: Boolean): DenyAction = when (code) {
        GatewayCode.NO_SESSION -> if (mainFrame) DenyAction.RELOGIN else DenyAction.NONE
        GatewayCode.USER_MISMATCH -> DenyAction.CHECK_BINDING
        GatewayCode.CERT_INVALID, GatewayCode.CERT_UNKNOWN, GatewayCode.CERT_SUPERSEDED, GatewayCode.CERT_EXPIRED -> DenyAction.REFRESH
        GatewayCode.DEVICE_REVOKED, GatewayCode.DEVICE_REPLACED, GatewayCode.IDENTITY_REVOKED, GatewayCode.DEVICE_POLICY,
        GatewayCode.UPDATE_REQUIRED -> DenyAction.VERIFY
        else -> DenyAction.NONE
    }

    /** An app.<host> error response with no `X-Device-Deny`: only a 401 on the document matters. */
    fun forUnmarked(status: Int, mainFrame: Boolean): DenyAction =
        if (status == 401 && mainFrame) DenyAction.CHECK_SESSION else DenyAction.NONE
}
