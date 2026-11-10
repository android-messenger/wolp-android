package org.wolpmessenger.web

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgePolicyTest {
    private val app = "https://app.example.org"
    private val stand = "https://localhost:9444"

    @After fun clear() = CallOrigins.clear()

    @Test fun `main frame of the app origin is admitted`() {
        assertTrue(BridgePolicy.admit(app, true, app))
        assertTrue(BridgePolicy.admit("https://app.example.org:443", true, app))
        assertTrue(BridgePolicy.admit("https://APP.example.org/", true, app))
        assertTrue(BridgePolicy.admit(stand, true, stand))
    }

    @Test fun `iframe of the app origin is refused`() = assertFalse(BridgePolicy.admit(app, false, app))

    @Test fun `enroll and other origins are refused`() {
        assertFalse(BridgePolicy.admit("https://enroll.example.org", true, app))
        assertFalse(BridgePolicy.admit("https://localhost:9443", true, stand)) // stand enroll: same host, other port
        assertFalse(BridgePolicy.admit("https://localhost", true, stand))      // Capacitor's own extra rule
        assertFalse(BridgePolicy.admit("http://app.example.org", true, app))
        assertFalse(BridgePolicy.admit("https://app.example.org.evil.test", true, app))
        assertFalse(BridgePolicy.admit("null", true, app))
        assertFalse(BridgePolicy.admit(null, true, app))
    }

    @Test fun `packaged UI origin is admitted, the API host is not`() {
        // security/09: plugins answer the UI from the APK (https://<uiHost>), never a document of the server
        val ui = "https://localhost"
        assertTrue(BridgePolicy.admit("https://localhost:443", true, ui))
        assertFalse(BridgePolicy.admit("https://localhost:9444", true, ui)) // stand app (API) host
        assertFalse(BridgePolicy.admit("https://localhost:9443", true, ui)) // stand enroll
        val prodUi = "https://ui.example.org"
        assertFalse(BridgePolicy.admit("https://example.org:8443", true, prodUi))
        assertFalse(BridgePolicy.admit("https://ui.example.org:8443", true, prodUi))
    }

    @Test fun `a call without a guard record is refused`() = assertFalse(CallOrigins.allowed("42", app))

    @Test fun `a recorded call is allowed once`() {
        CallOrigins.record("7", CallOrigins.Origin(app, true))
        assertTrue(CallOrigins.allowed("7", app))
        assertFalse("the record is consumed", CallOrigins.allowed("7", app))
    }

    @Test fun `a record from another origin or frame does not pass the plugin check`() {
        CallOrigins.record("1", CallOrigins.Origin("https://enroll.example.org", true))
        CallOrigins.record("2", CallOrigins.Origin(app, false))
        assertFalse(CallOrigins.allowed("1", app))
        assertFalse(CallOrigins.allowed("2", app))
        assertNull(CallOrigins.take(null))
    }

    @Test fun `denied reply is Capacitor's error result and escapes ids`() {
        assertEquals(
            """{"save":false,"callbackId":"5","pluginId":"DeviceTrust","methodName":"vaultUnwrap",""" +
                """"success":false,"error":{"message":"plugin call refused","code":"PERMISSION_DENIED"}}""",
            BridgePolicy.deniedReply("5", "DeviceTrust", "vaultUnwrap"),
        )
        val r = BridgePolicy.deniedReply("a\"b\\c\n</script> ", null, null)
        assertTrue(r, r.contains("\"callbackId\":\"a\\\"b\\\\c\\u000a</script>\\u2028\","))
        assertTrue(r.contains(""""pluginId":"""""))
    }
}
