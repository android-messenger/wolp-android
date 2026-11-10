package org.wolpmessenger.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JsFilesTest {
    private val app = "https://localhost:9444"

    @Test fun `only https URLs on exactly the app origin`() {
        val ok = "https://localhost:9444/messenger-attachments/01M?X-Amz-Signature=ab"
        assertEquals(ok, JsFiles.url(ok, app))
        assertEquals("https://app.example.org:443/x", JsFiles.url("https://app.example.org:443/x", "https://app.example.org"))
        assertNull(JsFiles.url("https://localhost/x", app))             // the UI origin
        assertNull(JsFiles.url("https://localhost:9443/x", app))        // enroll
        assertNull(JsFiles.url("http://localhost:9444/x", app))
        assertNull(JsFiles.url("https://evil.test/x", app))
        assertNull(JsFiles.url("https://user@localhost:9444/x", app))
        assertNull(JsFiles.url("javascript:alert(1)", app))
        assertNull(JsFiles.url("not a url", app))
        assertNull(JsFiles.url(null, app))
    }

    @Test fun `file names lose paths and control characters`() {
        assertEquals("report.pdf", JsFiles.fileName("report.pdf"))
        assertEquals("passwd", JsFiles.fileName("../../etc/passwd"))
        assertEquals("b.txt", JsFiles.fileName("a\\b.txt"))
        assertEquals("ab.txt", JsFiles.fileName("a\u0000b.txt"))
        assertEquals("hidden", JsFiles.fileName(".hidden"))
        assertEquals(JsFiles.DEFAULT_NAME, JsFiles.fileName(""))
        assertEquals(JsFiles.DEFAULT_NAME, JsFiles.fileName(null))
        assertEquals(120, JsFiles.fileName("x".repeat(300)).length)
    }

    @Test fun `mime types are type-subtype or generic`() {
        assertEquals("application/pdf", JsFiles.mimeType("application/pdf"))
        assertEquals("text/plain", JsFiles.mimeType("Text/Plain; charset=utf-8"))
        assertEquals(JsFiles.DEFAULT_MIME, JsFiles.mimeType("pdf"))
        assertEquals(JsFiles.DEFAULT_MIME, JsFiles.mimeType("a/b c"))
        assertEquals(JsFiles.DEFAULT_MIME, JsFiles.mimeType(null))
        assertTrue(JsFiles.viewable("application/pdf"))
        assertFalse(JsFiles.viewable("application/zip"))
    }
}
