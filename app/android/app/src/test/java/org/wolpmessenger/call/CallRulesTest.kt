package org.wolpmessenger.call

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.wolpmessenger.call.AudioRoute.BLUETOOTH
import org.wolpmessenger.call.AudioRoute.EARPIECE
import org.wolpmessenger.call.AudioRoute.SPEAKER
import org.wolpmessenger.call.AudioRoute.WIRED
import java.time.Duration
import java.time.Instant

class CallRulesTest {
    // ---------------------------------------------------------------- §7.3 P1-i: which way to decline

    @Test fun declineNativelyOnlyWhenTheWebSocketIsClosed() {
        assertEquals(DeclinePath.NATIVE, CallRules.declinePath(WebWs.CLOSED, webListens = true))
        assertEquals(DeclinePath.NATIVE, CallRules.declinePath(WebWs.CLOSED, webListens = false))
        assertEquals(DeclinePath.WEB, CallRules.declinePath(WebWs.OPEN, webListens = true))
        assertEquals(DeclinePath.WAIT, CallRules.declinePath(WebWs.CLOSING, webListens = true))
    }

    @Test fun openSocketWithoutListenerFallsBackToNative() {
        assertEquals(DeclinePath.NATIVE, CallRules.declinePath(WebWs.OPEN, webListens = false))
    }

    // ---------------------------------------------------------------- §4.5 routes

    @Test fun defaultRoutePrefersHeadsetsThenEarpieceForAudio() {
        val phone = setOf(EARPIECE, SPEAKER)
        assertEquals(EARPIECE, CallRules.defaultRoute(phone, video = false))
        assertEquals(SPEAKER, CallRules.defaultRoute(phone, video = true))
        assertEquals(WIRED, CallRules.defaultRoute(phone + WIRED, video = true))
        assertEquals(BLUETOOTH, CallRules.defaultRoute(phone + WIRED + BLUETOOTH, video = false))
        assertEquals(SPEAKER, CallRules.defaultRoute(setOf(SPEAKER), video = false)) // tablet: no earpiece
    }

    @Test fun newHeadsetTakesOverAndVanishedRouteFallsBack() {
        val phone = setOf(EARPIECE, SPEAKER)
        assertEquals(BLUETOOTH, CallRules.routeAfterDevicesChanged(phone, phone + BLUETOOTH, SPEAKER, video = false))
        assertEquals(WIRED, CallRules.routeAfterDevicesChanged(phone, phone + WIRED, EARPIECE, video = false))
        assertEquals(EARPIECE, CallRules.routeAfterDevicesChanged(phone + BLUETOOTH, phone, BLUETOOTH, video = false))
        assertEquals(SPEAKER, CallRules.routeAfterDevicesChanged(phone + WIRED, phone, WIRED, video = true))
    }

    @Test fun userChoiceSurvivesUnrelatedChanges() {
        val phone = setOf(EARPIECE, SPEAKER)
        assertEquals(SPEAKER, CallRules.routeAfterDevicesChanged(phone + WIRED, phone, SPEAKER, video = false))
        assertEquals(SPEAKER, CallRules.routeAfterDevicesChanged(phone, phone, SPEAKER, video = false))
    }

    @Test fun proximityOnlyForAudioOnEarpiece() {
        assertTrue(CallRules.proximity(video = false, route = EARPIECE))
        assertFalse(CallRules.proximity(video = false, route = SPEAKER))
        assertFalse(CallRules.proximity(video = true, route = EARPIECE))
        assertFalse(CallRules.proximity(video = false, route = null))
    }

    // ---------------------------------------------------------------- §7.3 ring time

    @Test fun ringTimeIsWhatIsLeftOfTheTimeout() {
        val now = Instant.parse("2026-11-10T12:00:00Z")
        assertEquals(Duration.ofSeconds(45), CallRules.ringFor(null, now, now))
        assertEquals(Duration.ofSeconds(45), CallRules.ringFor("garbage", now, now))
        assertEquals(Duration.ofSeconds(35), CallRules.ringFor("2026-11-10T11:59:50Z", now, now))
        assertEquals(Duration.ofSeconds(5), CallRules.ringFor("2026-11-10T11:59:00Z", now, now))      // floor
        assertEquals(Duration.ofSeconds(45), CallRules.ringFor("2026-11-10T12:00:10Z", now, now))     // future ts: full
        assertEquals(Duration.ofSeconds(25), CallRules.ringFor("2026-11-10T11:59:50Z", null, now.plusSeconds(10))) // device clock
    }

    // ---------------------------------------------------------------- §7.3 native decline frame

    @Test fun declineFrameHasTheShapeOfFrameBridge() {
        val f = JSONObject(CallRules.declineFrame("caller-1", "conv-1", "m-1", 1_762_776_000_000, "Europe/Berlin"))
        assertEquals("SIGNAL_IN", f.getString("type"))
        assertEquals("m-1", f.getString("messageId"))
        assertEquals("caller-1", f.getString("recipientId"))
        assertEquals("conv-1", f.getString("conversationId"))
        assertEquals(1_762_776_000_000, f.getLong("senderTimestamp"))
        assertEquals("Europe/Berlin", f.getString("senderTimezone"))
        val p = f.getJSONObject("payload")
        assertEquals("event", p.getString("kind"))
        val body = JSONObject(p.getString("body"))
        assertEquals("call:end", body.getString("type"))
        assertEquals(1, body.length()) // nothing else: `to` travels as recipientId, as in toWire()
    }

    @Test fun wsUrlFollowsMessengerWsUrl() {
        assertEquals("wss://m.wolpertinger.work:8443/messenger/ws", CallRules.wsUrl("https://m.wolpertinger.work:8443"))
        assertEquals("wss://app.example/messenger/ws", CallRules.wsUrl("https://app.example/"))
    }
}
