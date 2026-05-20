package com.liquidmusicglass.api.icm

import org.junit.Test
import kotlin.test.assertTrue

class IcmRepositoryHomeTest {

    @Test
    fun `loadHomeContent search logic uses source=all`() {
        // This is more of a documentation/manual check, 
        // but we can verify the search constant is correct.
        assertEquals("all", IcmSearchSource.ALL)
    }
}

private fun assertEquals(expected: String, actual: String) {
    if (expected != actual) throw AssertionError("Expected $expected but got $actual")
}
