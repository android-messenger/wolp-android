package org.wolpmessenger.launch

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.wolpmessenger.devicetrust.api.BlockReason
import org.wolpmessenger.devicetrust.api.DeviceSecurityInfo
import org.wolpmessenger.devicetrust.api.SecurityLevel
import org.wolpmessenger.devicetrust.api.PrecheckResult
import org.wolpmessenger.devicetrust.api.RaspReport
import org.wolpmessenger.devicetrust.api.TrustState
import org.wolpmessenger.devicetrust.api.UnsupportedReason
import org.wolpmessenger.devicetrust.api.UpdateKind
import java.time.Instant

/** S1 §3 / §10 «Запуск»: every step on its own, and the order between them. */
class LaunchGateTest {
    private val t = Instant.parse("2026-11-09T12:00:00Z")
    private val active = TrustState.Active(t.plusSeconds(72 * 3600), t.plusSeconds(24 * 3600))

    private class Fake(
        var state: TrustState,
        var webView: Boolean = true,
        var precheck: PrecheckResult = PrecheckResult.Supported(DeviceSecurityInfo(SecurityLevel.TRUSTED_ENVIRONMENT, false, false, 36, "2026-08-01", true), emptyList()),
        var update: Boolean = false,
        var reach: Reachability = Reachability.OK,
        var session: Boolean = true,
        var afterFresh: TrustState? = null,
        var stateAfterPrecheck: TrustState? = null,
        var updateAfterReach: Boolean? = null,
    ) : LaunchProbes {
        val calls = mutableListOf<String>()
        override fun trustState() = state
        override fun webViewUsable() = webView.also { calls += "webView" }
        override suspend fun precheck() = precheck.also { calls += "precheck"; stateAfterPrecheck?.let { state = it } }
        override suspend fun mandatoryUpdate() = update.also { calls += "update" }
        override suspend fun reachable(enrolled: Boolean) = reach.also { calls += "reach(enrolled=$enrolled)"; updateAfterReach?.let { update = it } }
        override suspend fun hasSession() = session.also { calls += "session" }
        override suspend fun ensureFresh(): TrustState { calls += "ensureFresh"; afterFresh?.let { state = it }; return state }
    }

    private fun gate(f: Fake) = LaunchGate(f)

    @Test fun `0 uninitialized waits and probes nothing`() = runTest {
        val f = Fake(TrustState.Uninitialized)
        assertEquals(Screen.Wait, gate(f).evaluate())
        assertEquals(emptyList<String>(), f.calls)
    }

    @Test fun `1 outdated WebView comes before blocked`() = runTest {
        val f = Fake(TrustState.Blocked(BlockReason.RASP), webView = false)
        assertEquals(Screen.WebViewOutdated, gate(f).evaluate())
    }

    @Test fun `2 blocked comes before update and network`() = runTest {
        val f = Fake(TrustState.Blocked(BlockReason.REPLACED), update = true, reach = Reachability.OFFLINE)
        assertEquals(Screen.Blocked(BlockReason.REPLACED), gate(f).evaluate())
        assertEquals(listOf("webView"), f.calls)
    }

    @Test fun `2 compromised precheck blocks before enrollment`() = runTest {
        val f = Fake(TrustState.NotEnrolled, precheck = PrecheckResult.Compromised(RaspReport(emptyList())))
        assertEquals(Screen.Blocked(null), gate(f).evaluate())
    }

    @Test fun `2 precheck that blocked the device shows the library's reason`() = runTest {
        val f = Fake(TrustState.NotEnrolled, stateAfterPrecheck = TrustState.Blocked(BlockReason.RASP))
        assertEquals(Screen.Blocked(BlockReason.RASP), gate(f).evaluate())
    }

    @Test fun `3 unsupported before update`() = runTest {
        val f = Fake(TrustState.NotEnrolled, precheck = PrecheckResult.Unsupported(listOf(UnsupportedReason.SOFTWARE_KEYSTORE), null), update = true)
        assertEquals(Screen.Unsupported(listOf("SOFTWARE_KEYSTORE")), gate(f).evaluate())
    }

    @Test fun `2-3 enrolled device is not prechecked`() = runTest {
        val f = Fake(active)
        assertEquals(Screen.Messenger, gate(f).evaluate())
        assert("precheck" !in f.calls)
    }

    @Test fun `4 mandatory update comes before ensureFresh and the network`() = runTest {
        val f = Fake(TrustState.ExpiredGrace(t), update = true, reach = Reachability.OFFLINE)
        assertEquals(Screen.Update(null), gate(f).evaluate())
        assertEquals(listOf("webView", "update"), f.calls)
    }

    @Test fun `5a minimum version brought by the ping of step 5`() = runTest {
        val f = Fake(active, updateAfterReach = true)
        assertEquals(Screen.Update(null), gate(f).evaluate())
        assertEquals(listOf("webView", "update", "reach(enrolled=true)", "update"), f.calls)
    }

    @Test fun `5a not before enrollment`() = runTest {
        val f = Fake(TrustState.NotEnrolled, updateAfterReach = true)
        assertEquals(Screen.Onboarding(reEnroll = false), gate(f).evaluate())
    }

    @Test fun `4a UpdateRequired from the gateway`() = runTest {
        val f = Fake(TrustState.UpdateRequired(UpdateKind.OS, "PATCH_TOO_OLD"), reach = Reachability.OFFLINE)
        assertEquals(Screen.Update(UpdateKind.OS), gate(f).evaluate())
    }

    @Test fun `5 offline before enrollment probes the enroll host`() = runTest {
        val f = Fake(TrustState.NotEnrolled, reach = Reachability.OFFLINE, session = false)
        assertEquals(Screen.Offline(), gate(f).evaluate())
        assert("reach(enrolled=false)" in f.calls && "session" !in f.calls)
    }

    @Test fun `5 pin failure is a distinct offline screen`() = runTest {
        val f = Fake(active, reach = Reachability.SERVER_UNVERIFIED)
        assertEquals(Screen.Offline(serverUnverified = true), gate(f).evaluate())
    }

    @Test fun `6 no session before onboarding`() = runTest {
        val f = Fake(TrustState.NotEnrolled, session = false)
        assertEquals(Screen.Login, gate(f).evaluate())
    }

    @Test fun `6 ReEnrollRequired without a session logs in first`() = runTest {
        val f = Fake(TrustState.ReEnrollRequired, session = false)
        assertEquals(Screen.Login, gate(f).evaluate())
    }

    @Test fun `7 onboarding and re-enrollment`() = runTest {
        assertEquals(Screen.Onboarding(reEnroll = false), gate(Fake(TrustState.NotEnrolled)).evaluate())
        assertEquals(Screen.Onboarding(reEnroll = true), gate(Fake(TrustState.ReEnrollRequired)).evaluate())
    }

    @Test fun `8 ExpiredGrace refreshes once and opens the messenger when Active`() = runTest {
        val f = Fake(TrustState.ExpiredGrace(t), afterFresh = active)
        assertEquals(Screen.Messenger, gate(f).evaluate())
        assertEquals(1, f.calls.count { it == "ensureFresh" })
    }

    @Test fun `8 ExpiredGrace that stays expired does not loop`() = runTest {
        val f = Fake(TrustState.ExpiredGrace(t))
        assertEquals(Screen.Offline(), gate(f).evaluate())
        assertEquals(1, f.calls.count { it == "ensureFresh" })
    }

    @Test fun `8 refresh ending in ReEnrollRequired goes to onboarding`() = runTest {
        val f = Fake(TrustState.ExpiredGrace(t), afterFresh = TrustState.ReEnrollRequired)
        assertEquals(Screen.Onboarding(reEnroll = true), gate(f).evaluate())
    }

    @Test fun `8 refresh ending in Blocked shows the block`() = runTest {
        val f = Fake(TrustState.ExpiredGrace(t), afterFresh = TrustState.Blocked(BlockReason.POLICY))
        assertEquals(Screen.Blocked(BlockReason.POLICY), gate(f).evaluate())
    }

    @Test fun `8 RenewalDue keeps working when renewal fails`() = runTest {
        val f = Fake(TrustState.RenewalDue(t.plusSeconds(3600)))
        assertEquals(Screen.Messenger, gate(f).evaluate())
        assertEquals(1, f.calls.count { it == "ensureFresh" })
    }

    @Test fun `9 Active goes straight to the messenger in order`() = runTest {
        val f = Fake(active)
        assertEquals(Screen.Messenger, gate(f).evaluate())
        assertEquals(listOf("webView", "update", "reach(enrolled=true)", "update", "session"), f.calls)
    }
}
