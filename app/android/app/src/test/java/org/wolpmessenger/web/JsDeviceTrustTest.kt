package org.wolpmessenger.web

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.wolpmessenger.devicetrust.api.BlockReason
import org.wolpmessenger.devicetrust.api.SecurityLevel
import org.wolpmessenger.devicetrust.api.TrustState
import org.wolpmessenger.devicetrust.api.VaultException
import org.wolpmessenger.devicetrust.api.VaultPurpose
import java.time.Instant

class JsDeviceTrustTest {
    @Test fun `only the JS purposes are accepted`() {
        assertEquals(VaultPurpose.E2EE, JsDeviceTrust.purpose("e2ee"))
        assertEquals(VaultPurpose.E2EE_PLAINTEXT, JsDeviceTrust.purpose("e2ee-plaintext"))
        listOf("names", "push-key", "E2EE", "e2ee ", "", null).forEach { assertNull("$it", JsDeviceTrust.purpose(it)) }
    }

    @Test fun `base64 round trip and garbage`() {
        val b = byteArrayOf(0, 1, -1, 127, -128)
        assertArrayEquals(b, JsDeviceTrust.decode(JsDeviceTrust.encode(b)))
        assertArrayEquals(ByteArray(0), JsDeviceTrust.decode(""))
        assertNull(JsDeviceTrust.decode("%%%"))
        assertNull(JsDeviceTrust.decode(null))
    }

    @Test fun `state carries notAfter only while a certificate is valid`() {
        val t = Instant.parse("2026-11-10T12:00:00Z")
        assertEquals(mapOf("state" to "ACTIVE", "notAfter" to "2026-11-10T12:00:00Z"), JsDeviceTrust.state(TrustState.Active(t, t)))
        assertEquals(mapOf("state" to "RENEWAL_DUE", "notAfter" to "2026-11-10T12:00:00Z"), JsDeviceTrust.state(TrustState.RenewalDue(t)))
        assertEquals(mapOf("state" to "BLOCKED"), JsDeviceTrust.state(TrustState.Blocked(BlockReason.RASP)))
        assertEquals(mapOf("state" to "NOT_ENROLLED"), JsDeviceTrust.state(TrustState.NotEnrolled))
    }

    @Test fun `only accepted security levels are reported`() {
        assertEquals("TEE", JsDeviceTrust.securityLevel(SecurityLevel.TRUSTED_ENVIRONMENT))
        assertEquals("STRONGBOX", JsDeviceTrust.securityLevel(SecurityLevel.STRONGBOX))
        assertNull(JsDeviceTrust.securityLevel(SecurityLevel.SOFTWARE))
        assertNull(JsDeviceTrust.securityLevel(null))
    }

    @Test fun `vault errors map to codes`() {
        assertEquals(JsDeviceTrust.VAULT_KEY_GONE, JsDeviceTrust.errorCode(VaultException.KeyGone()))
        assertEquals(JsDeviceTrust.VAULT_LOCKED, JsDeviceTrust.errorCode(VaultException.Locked()))
        assertEquals(JsDeviceTrust.VAULT_CORRUPT, JsDeviceTrust.errorCode(VaultException.Corrupt()))
        assertEquals(JsDeviceTrust.UNAVAILABLE, JsDeviceTrust.errorCode(IllegalStateException()))
    }
}
