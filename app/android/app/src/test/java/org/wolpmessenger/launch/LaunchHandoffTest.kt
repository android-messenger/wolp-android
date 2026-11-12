package org.wolpmessenger.launch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.wolpmessenger.push.NotificationTap
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class LaunchHandoffTest {
    private val call = NotificationTap(null, "c-1", "u-1", "video", accept = true)

    @Before fun reset() = LaunchHandoff.reset()

    @Test fun tapIsHandedOverOnce() {
        assertNull(LaunchHandoff.takeTap())
        LaunchHandoff.offerTap(call, now = 1_000)
        assertEquals(call, LaunchHandoff.takeTap(now = 2_000))
        assertNull(LaunchHandoff.takeTap(now = 2_001))
    }

    @Test fun staleTapIsDropped() {
        LaunchHandoff.offerTap(call, now = 0)
        assertNull(LaunchHandoff.takeTap(now = LaunchHandoff.TAP_TTL_MS + 1))
        // dropped for good, not kept for a later take
        assertNull(LaunchHandoff.takeTap(now = 1))
    }

    @Test fun newerTapReplacesTheOlder() {
        val message = NotificationTap("c-2", null, null, null)
        LaunchHandoff.offerTap(call, now = 0)
        LaunchHandoff.offerTap(message, now = 10)
        assertEquals(message, LaunchHandoff.takeTap(now = 20))
    }

    @Test fun reasonIsHandedOverOnce() {
        assertNull(LaunchHandoff.takeReason())
        LaunchHandoff.offerReason("wrong_user")
        assertEquals("wrong_user", LaunchHandoff.takeReason())
        assertNull(LaunchHandoff.takeReason())
    }

    /** ADR-0001 п. 3: MainActivity loads the UI only when the order cleared exactly this start. */
    @Test fun clearanceIsSingleUse() {
        assertFalse(LaunchHandoff.takeClearance()) // restored by the system, started by anything else
        LaunchHandoff.clearMessenger()
        assertTrue(LaunchHandoff.takeClearance())
        assertFalse(LaunchHandoff.takeClearance())
    }
}

/**
 * The manifest side of the same rule: the launcher is the only component of ours another app can start,
 * so a tap (TapActivity) and the messenger (MainActivity) cannot be reached from outside.
 */
class ExportedComponentsTest {
    private val android = "http://schemas.android.com/apk/res/android"

    private fun components(): Map<String, Boolean> {
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(File("src/main/AndroidManifest.xml"))
        val out = LinkedHashMap<String, Boolean>()
        for (tag in listOf("activity", "service", "receiver", "provider")) {
            val nodes = doc.getElementsByTagName(tag)
            for (i in 0 until nodes.length) {
                val e = nodes.item(i) as org.w3c.dom.Element
                if (e.getAttributeNS("http://schemas.android.com/tools", "node") == "remove") continue
                val exported = e.getAttributeNS(android, "exported")
                assertTrue("${e.getAttributeNS(android, "name")}: android:exported must be explicit", exported.isNotEmpty())
                out[e.getAttributeNS(android, "name")] = exported == "true"
            }
        }
        return out
    }

    @Test fun onlyTheLauncherIsExported() {
        val c = components()
        assertEquals(listOf(".SplashActivity"), c.filterValues { it }.keys.toList())
        assertEquals(false, c[".push.TapActivity"])
        assertEquals(false, c[".MainActivity"])
        assertEquals(false, c[".call.IncomingCallActivity"])
    }
}
