package org.wolpmessenger.web

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.wolpmessenger.devicetrust.api.BlockReason
import org.wolpmessenger.devicetrust.api.E2eeException
import org.wolpmessenger.devicetrust.api.E2eeFailure
import org.wolpmessenger.devicetrust.api.E2eePeerState
import org.wolpmessenger.devicetrust.api.E2eePeerTrust
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

    @Test fun `a peer is an account id and nothing else`() {
        assertEquals("56db2baa-bf11-48d3-814c-5c5a2e840973", JsDeviceTrust.peer("56db2baa-bf11-48d3-814c-5c5a2e840973"))
        listOf(null, "", " ", "a b", "../x", "a/b", "x".repeat(65), "имя").forEach { assertNull("$it", JsDeviceTrust.peer(it)) }
    }

    @Test fun `secret-chat refusals reach the web app with the library's code`() {
        // the closed list of specs/002 FR-032: the web app's texts are keyed by these strings
        assertEquals(
            listOf("locked", "no_passport", "bad_passport", "reverify", "multi_device", "no_device", "no_network", "duplicate", "undecryptable", "not_enrolled"),
            E2eeFailure.entries.map { JsDeviceTrust.e2eeErrorCode(E2eeException(it)) },
        )
        assertEquals(JsDeviceTrust.UNAVAILABLE, JsDeviceTrust.e2eeErrorCode(IllegalStateException("not configured")))
    }

    @Test fun `peer state carries the trust code and the change mark`() {
        assertEquals(mapOf("trust" to "passport", "keyChanged" to false), JsDeviceTrust.peerState(E2eePeerState(E2eePeerTrust.PASSPORT, false)))
        assertEquals(mapOf("trust" to "reverify", "keyChanged" to true), JsDeviceTrust.peerState(E2eePeerState(E2eePeerTrust.REVERIFY, true)))
        assertEquals(listOf("unverified", "passport", "verified", "reverify"), E2eePeerTrust.entries.map { it.code })
    }

    @Test fun `vault errors map to codes`() {
        assertEquals(JsDeviceTrust.VAULT_KEY_GONE, JsDeviceTrust.errorCode(VaultException.KeyGone()))
        assertEquals(JsDeviceTrust.VAULT_LOCKED, JsDeviceTrust.errorCode(VaultException.Locked()))
        assertEquals(JsDeviceTrust.VAULT_CORRUPT, JsDeviceTrust.errorCode(VaultException.Corrupt()))
        assertEquals(JsDeviceTrust.UNAVAILABLE, JsDeviceTrust.errorCode(IllegalStateException()))
    }
}
