package org.wolpmessenger.push

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PushStateTest {
    private var released = 0

    @Before fun setUp() {
        PushState.clear()
        PushState.onAllReleased = { released++ }
    }

    @After fun tearDown() = PushState.clear()

    @Test fun lastReleaseNotifies() {
        PushState.hold(PushState.HOLD_CALL, now = 0)
        PushState.hold(PushState.HOLD_PERMISSION, 60_000, now = 0)
        PushState.release(PushState.HOLD_PERMISSION, now = 10)
        assertEquals(0, released) // the call still holds the connection
        PushState.release(PushState.HOLD_CALL, now = 20)
        assertEquals(1, released)
        assertFalse(PushState.keepConnection(now = 20))
    }

    @Test fun releasingAnExpiredLastHoldNotifies() {
        PushState.hold(PushState.HOLD_CALL, now = 0)
        PushState.hold(PushState.HOLD_FILE_CHOOSER, 1_000, now = 0)
        PushState.release(PushState.HOLD_CALL, now = 2_000) // the chooser hold ran out meanwhile
        assertEquals(1, released)
    }

    @Test fun releasingAnUnknownHoldIsSilent() {
        PushState.release(PushState.HOLD_CALL, now = 0)
        assertEquals(0, released)
    }

    @Test fun nextExpiryIgnoresOpenEndedHolds() {
        PushState.hold(PushState.HOLD_CALL, now = 0)
        assertNull(PushState.msUntilNextExpiry(now = 0))
        PushState.hold(PushState.HOLD_FILE_CHOOSER, 600_000, now = 0)
        PushState.hold(PushState.HOLD_PERMISSION, 60_000, now = 0)
        assertEquals(50_000L, PushState.msUntilNextExpiry(now = 10_000))
        assertTrue(PushState.keepConnection(now = 700_000)) // the call is open-ended
        assertNull(PushState.msUntilNextExpiry(now = 700_000))
    }

    @Test fun timedHoldRunsOut() {
        PushState.hold(PushState.HOLD_SAVE_DOCUMENT, 1_000, now = 0)
        assertTrue(PushState.keepConnection(now = 999))
        assertFalse(PushState.keepConnection(now = 1_000))
    }
}
