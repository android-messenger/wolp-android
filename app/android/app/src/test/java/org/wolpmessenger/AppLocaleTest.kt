package org.wolpmessenger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppLocaleTest {
    @Test fun `supported languages and regional tags`() {
        assertEquals("es", AppLocale.normalize("es"))
        assertEquals("es", AppLocale.normalize("es-ES"))
        assertEquals("en", AppLocale.normalize(" EN_us "))
    }

    @Test fun `anything else is refused`() {
        assertNull(AppLocale.normalize(null))
        assertNull(AppLocale.normalize(""))
        assertNull(AppLocale.normalize("ru"))
        assertNull(AppLocale.normalize("../es"))
    }
}
