package com.liquidmusicglass.data.wave

import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeadTrackRegistryTest {

    @BeforeTest
    fun reset() {
        DeadTrackRegistry.clearForTest()
    }

    @Test
    fun `marks and reports a dead track`() {
        assertFalse(DeadTrackRegistry.isDead("367614920"))
        DeadTrackRegistry.markDead("367614920")
        assertTrue(DeadTrackRegistry.isDead("367614920"))
    }

    @Test
    fun `ignores blank and null ids`() {
        DeadTrackRegistry.markDead(null)
        DeadTrackRegistry.markDead("")
        DeadTrackRegistry.markDead("   ")
        assertEquals(0, DeadTrackRegistry.size())
        assertFalse(DeadTrackRegistry.isDead(null))
        assertFalse(DeadTrackRegistry.isDead(""))
    }

    @Test
    fun `keys are trimmed so padded lookups still match`() {
        DeadTrackRegistry.markDead("  880774576  ")
        assertTrue(DeadTrackRegistry.isDead("880774576"))
        assertEquals(1, DeadTrackRegistry.size())
    }

    @Test
    fun `re-marking the same id does not grow the set`() {
        DeadTrackRegistry.markDead("42")
        DeadTrackRegistry.markDead("42")
        assertEquals(1, DeadTrackRegistry.size())
    }

    @Test
    fun `snapshot is an independent copy`() {
        DeadTrackRegistry.markDead("1")
        val snap = DeadTrackRegistry.snapshot()
        DeadTrackRegistry.markDead("2")
        // Snapshot taken before the second mark must not see it.
        assertEquals(setOf("1"), snap)
    }

    @Test
    fun `bounded size evicts the oldest ids`() {
        // MAX_ENTRIES is 500; push past it and confirm the earliest id is gone
        // while the newest survives.
        for (i in 0 until 600) {
            DeadTrackRegistry.markDead("id_$i")
        }
        assertEquals(500, DeadTrackRegistry.size())
        assertFalse(DeadTrackRegistry.isDead("id_0"))
        assertTrue(DeadTrackRegistry.isDead("id_599"))
    }
}
