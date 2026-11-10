package org.wolpmessenger.launch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MinVersionTest {
    @Test fun `no header means no minimum`() = assertEquals(0L, MinVersion.parse(null))
    @Test fun `numeric header is taken`() = assertEquals(9991L, MinVersion.parse(" 9991 "))
    @Test fun `malformed header keeps the previous value`() {
        assertNull(MinVersion.parse("abc"))
        assertNull(MinVersion.parse("-1"))
        assertNull(MinVersion.parse(""))
    }
}
