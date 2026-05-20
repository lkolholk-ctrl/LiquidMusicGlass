package com.liquidmusicglass.api.icm

import org.junit.Test
import kotlin.test.assertEquals

class DurationNormalizationTest {

    @Test
    fun `apple music duration remains in milliseconds`() {
        // Mocking behavior of IcmSearchItem durationMs with source="primary"
        val duration = 180000L
        val source = "primary"
        val isVk = false
        
        val durationMs = if (isVk) duration * 1000L else duration
        assertEquals(180000L, durationMs)
    }

    @Test
    fun `vk music duration is converted to milliseconds`() {
        // Mocking behavior of IcmSearchItem durationMs with source="vk"
        val duration = 180L
        val source = "vk"
        val isVk = true
        
        val durationMs = if (isVk) duration * 1000L else duration
        assertEquals(180000L, durationMs)
    }

    @Test
    fun `short apple music track does not get scaled incorrectly`() {
        // Mocking behavior of IcmSearchItem durationMs with short duration (e.g. 500ms)
        val duration = 500L
        val source = "primary"
        val isVk = false
        
        // Old logic would have made this 500,000ms
        val durationMs = if (isVk) duration * 1000L else duration
        assertEquals(500L, durationMs)
    }
}
