package org.wolpmessenger.push

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Instant
import java.util.Base64

class PushPayloadTest {
    /** The vector from firebase-push internal/domain/testdata/payload-vector.json (Go seals, Kotlin opens). */
    private val vector = JSONObject(javaClass.getResource("/push-payload-vector.json")!!.readText())
    private val key = Base64.getDecoder().decode(vector.getString("key"))

    @Test fun opensServerVector() {
        val e = PushPayload.open(key, mapOf("v" to "2", "c" to vector.getString("sealed")))!!
        assertEquals(PushPayload.Type.CALL, e.type)
        assertEquals("e-1", e.eventId)
        assertEquals("c-1", e.conversationId)
        assertEquals("m-1", e.messageId)
        assertEquals("s-1", e.senderId)
        assertEquals("video", e.media)
        assertEquals("2026-11-10T12:00:00Z", e.ts)
    }

    @Test fun rejectsWrongKeyVersionAndGarbage() {
        val c = vector.getString("sealed")
        assertNull(PushPayload.open(ByteArray(32), mapOf("v" to "2", "c" to c)))
        assertNull(PushPayload.open(key, mapOf("v" to "1", "c" to c)))
        assertNull(PushPayload.open(key, mapOf("v" to "2", "c" to "%%%")))
        assertNull(PushPayload.open(key, mapOf("v" to "2", "c" to "AAAA")))
        assertNull(PushPayload.open(key, mapOf("v" to "2")))
        val tampered = Base64.getDecoder().decode(c).also { it[20] = (it[20].toInt() xor 1).toByte() }
        assertNull(PushPayload.open(key, mapOf("v" to "2", "c" to Base64.getEncoder().encodeToString(tampered))))
    }

    @Test fun parseRejectsUnknownTypeAndBadMedia() {
        assertNull(PushPayload.parse("""{"type":"other"}"""))
        assertNull(PushPayload.parse("nope"))
        assertNull(PushPayload.parse("""{"type":"call","media":"hologram"}""")!!.media)
    }
}

class PushRulesTest {
    private val now = Instant.parse("2026-11-10T12:00:00Z")
    private fun env(type: PushPayload.Type, conv: String? = "c1", sender: String? = "s1", ts: String? = null, id: String? = "e1") =
        PushPayload.Envelope(type, id, conv, "m1", sender, null, ts)

    private fun decide(e: PushPayload.Envelope?, blocked: Boolean = false, fg: String? = null, seen: Boolean = false, server: Instant? = now, signedOut: Boolean = false) =
        PushRules.decide(blocked, e, { !seen }, fg, server, now, signedOut)

    @Test fun blockedDropsFirst() = assertEquals(PushAction.Drop, decide(env(PushPayload.Type.MSG), blocked = true))
    @Test fun undecryptableIsGeneric() = assertEquals(PushAction.Generic, decide(null))
    /** Docs F5, Т1: after a sign-out nothing is shown — not a message, not a call, not the notification without details. */
    @Test fun signedOutDropsEverything() {
        assertEquals(PushAction.Drop, decide(null, signedOut = true))
        assertEquals(PushAction.Drop, decide(env(PushPayload.Type.MSG), signedOut = true))
        assertEquals(PushAction.Drop, decide(env(PushPayload.Type.CALL, ts = "2026-11-10T11:59:59Z"), signedOut = true))
        assertEquals(PushAction.Drop, decide(env(PushPayload.Type.CALL_CANCEL), signedOut = true))
    }
    @Test fun duplicateDropped() = assertEquals(PushAction.Drop, decide(env(PushPayload.Type.MSG), seen = true))
    @Test fun message() = assertEquals(PushAction.Message("c1", "s1"), decide(env(PushPayload.Type.MSG)))
    @Test fun openConversationSuppressed() = assertEquals(PushAction.Drop, decide(env(PushPayload.Type.MSG), fg = "c1"))
    @Test fun otherConversationShown() = assertEquals(PushAction.Message("c1", "s1"), decide(env(PushPayload.Type.MSG), fg = "c2"))
    @Test fun cancel() = assertEquals(PushAction.CancelCall("c1"), decide(env(PushPayload.Type.CALL_CANCEL)))
    @Test fun callNeedsConversationAndCaller() {
        assertEquals(PushAction.Drop, decide(env(PushPayload.Type.CALL, conv = null)))
        assertEquals(PushAction.Drop, decide(env(PushPayload.Type.CALL, sender = null)))
    }

    @Test fun callAge() {
        // limit with server time: 45 − 5 + 3 = 43 s
        assertEquals(PushAction.Call("c1", "s1", null, "2026-11-10T11:59:18Z"), decide(env(PushPayload.Type.CALL, ts = "2026-11-10T11:59:18Z")))
        assertEquals(PushAction.Drop, decide(env(PushPayload.Type.CALL, ts = "2026-11-10T11:59:16Z")))
        // without server time: 45 − 5 + 30 = 70 s
        assertEquals(PushAction.Call("c1", "s1", null, "2026-11-10T11:59:00Z"), decide(env(PushPayload.Type.CALL, ts = "2026-11-10T11:59:00Z"), server = null))
        assertEquals(PushAction.Drop, decide(env(PushPayload.Type.CALL, ts = "2026-11-10T11:58:40Z"), server = null))
        // unparsable ts → not dropped (the server already shortened the TTL)
        assertFalse(PushRules.callExpired("garbage", now, now))
    }
}

/** Docs F5, part 1: the mark a sign-out leaves. */
class SignedOutMarkTest {
    @get:Rule val tmp = TemporaryFolder()
    private fun mark() = SignedOutMark(java.io.File(tmp.root, "push-signed-out")) // a new object = a restart of the app

    @Test fun noMarkUntilSignOut() {
        assertFalse(mark().signedOut)
        assertFalse(mark().cleanupPending)
    }

    @Test fun signOutWithoutNetworkOwesTheCleanUpAcrossRestarts() {
        mark().set(tokenDeleted = false, serverTold = false)
        assertTrue(mark().signedOut)
        assertTrue(mark().tokenPending)
        assertTrue(mark().noticePending)
        mark().tokenDeleted()
        assertTrue("still signed out: pushes stay hidden", mark().signedOut)
        assertFalse(mark().tokenPending)
        assertTrue("the two debts are paid apart", mark().noticePending)
        assertTrue(mark().cleanupPending)
        mark().serverTold()
        assertTrue(mark().signedOut)
        assertFalse(mark().cleanupPending)
    }

    @Test fun theNoticeCanBePaidFirst() {
        mark().set(tokenDeleted = false, serverTold = false)
        mark().serverTold()
        assertTrue(mark().tokenPending)
        assertFalse(mark().noticePending)
    }

    @Test fun signOutWithNetworkOwesNothing() {
        mark().set(tokenDeleted = true, serverTold = true)
        assertTrue(mark().signedOut)
        assertFalse(mark().cleanupPending)
    }

    @Test fun serverNotToldOwesOnlyTheNotice() {
        mark().set(tokenDeleted = true, serverTold = false)
        assertFalse(mark().tokenPending)
        assertTrue(mark().noticePending)
    }

    @Test fun signInCancelsTheCleanUp() {
        mark().set(tokenDeleted = false, serverTold = false)
        mark().clear()                 // a successful push registration
        assertFalse(mark().signedOut)
        assertFalse(mark().cleanupPending)
        mark().tokenDeleted()          // a clean-up that finished late must not bring the mark back
        mark().serverTold()
        assertFalse(mark().signedOut)
    }

    @Test fun markOfAnOlderBuildOwesTheToken() {
        java.io.File(tmp.root, "push-signed-out").writeText("pending")
        assertTrue(mark().tokenPending)
        assertFalse(mark().noticePending)
        mark().tokenDeleted()
        assertTrue(mark().signedOut)
        assertFalse(mark().cleanupPending)
        java.io.File(tmp.root, "push-signed-out").writeText("clean")
        assertTrue(mark().signedOut)
        assertFalse(mark().cleanupPending)
    }
}

class EventDedupTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun remembersAcrossInstancesAndEvicts() {
        val f = tmp.newFile("events.txt")
        val d = EventDedup(f, capacity = 3)
        assertTrue(d.firstSeen("a"))
        assertFalse(d.firstSeen("a"))
        assertTrue(d.firstSeen("b"))
        assertFalse(EventDedup(f, 3).firstSeen("b"))
        d.firstSeen("c"); d.firstSeen("d") // evicts "a"
        assertTrue(EventDedup(f, 3).firstSeen("a"))
        d.clear()
        assertTrue(EventDedup(f, 3).firstSeen("b"))
    }
}

class JsPushTest {
    @Test fun ids() {
        assertEquals("c1", JsPush.id(" c1 "))
        assertNull(JsPush.id(""))
        assertNull(JsPush.id("a\nb"))
        assertNull(JsPush.id("x".repeat(201)))
    }

    @Test fun names() {
        assertEquals(mapOf("u1" to "Ann"), JsPush.names(JSONObject("""{"u1":" Ann\n ","u2":""}""")))
        assertNull(JsPush.names(JSONObject("""{"u1":5}""")))
        assertNull(JsPush.names(null))
        assertEquals(120, JsPush.names(JSONObject(mapOf("u" to "y".repeat(500))))!!["u"]!!.length)
    }
}
