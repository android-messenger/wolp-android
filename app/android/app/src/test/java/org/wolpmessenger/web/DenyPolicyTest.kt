package org.wolpmessenger.web

import org.junit.Assert.assertEquals
import org.junit.Test
import org.wolpmessenger.devicetrust.api.GatewayCode

class DenyPolicyTest {
    private fun both(code: GatewayCode, expected: DenyAction) {
        assertEquals("$code main frame", expected, DenyPolicy.forWebView(code, mainFrame = true))
        assertEquals("$code sub-resource", expected, DenyPolicy.forWebView(code, mainFrame = false))
    }

    @Test fun `NO_SESSION on the document goes to login`() =
        assertEquals(DenyAction.RELOGIN, DenyPolicy.forWebView(GatewayCode.NO_SESSION, mainFrame = true))

    @Test fun `NO_SESSION inside the web app is left to authErrorMiddleware`() =
        assertEquals(DenyAction.NONE, DenyPolicy.forWebView(GatewayCode.NO_SESSION, mainFrame = false))

    @Test fun `USER_MISMATCH is only checked over the pinned channel`() = both(GatewayCode.USER_MISMATCH, DenyAction.CHECK_BINDING)

    @Test fun `certificate codes refresh`() = listOf(
        GatewayCode.CERT_INVALID, GatewayCode.CERT_UNKNOWN, GatewayCode.CERT_SUPERSEDED, GatewayCode.CERT_EXPIRED,
    ).forEach { both(it, DenyAction.REFRESH) }

    @Test fun `device codes are verified by S2, never acted upon directly`() = listOf(
        GatewayCode.DEVICE_REVOKED, GatewayCode.DEVICE_REPLACED, GatewayCode.IDENTITY_REVOKED, GatewayCode.DEVICE_POLICY,
        GatewayCode.UPDATE_REQUIRED,
    ).forEach { both(it, DenyAction.VERIFY) }

    @Test fun `other codes do nothing`() = listOf(
        GatewayCode.RATE_LIMITED, GatewayCode.UNKNOWN, GatewayCode.NONCE_INVALID, GatewayCode.WRONG_PASSWORD,
    ).forEach { both(it, DenyAction.NONE) }

    @Test fun `every code has a decision`() = GatewayCode.entries.forEach { DenyPolicy.forWebView(it, true) }
}
