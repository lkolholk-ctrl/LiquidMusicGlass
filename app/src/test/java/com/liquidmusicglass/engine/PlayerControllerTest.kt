package com.liquidmusicglass.engine

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Unit-тесты PlayerController
 */
class PlayerControllerTest {

    @Test
    fun `initial state is correct`() = runBlocking {
        // Проверяем начальное состояние плеера
        val isPlaying = PlayerController.isPlaying.first()
        val currentTrack = PlayerController.currentTrack.first()
        
        assertFalse(isPlaying, "Player should not be playing initially")
        assertEquals(null, currentTrack, "No track should be loaded initially")
    }

    @Test
    fun `volume is in valid range`() = runBlocking {
        val volume = PlayerController.volume.first()
        
        assertTrue(volume in 0f..1f, "Volume should be between 0 and 1")
    }

    @Test
    fun `autoMix default state`() = runBlocking {
        val autoMix = PlayerController.autoMixEnabled.first()
        
        // По умолчанию autoMix может быть включен или выключен
        // Просто проверяем, что значение не null
        assertNotNull(autoMix, "AutoMix state should not be null")
    }

    @Test
    fun `position does not exceed duration`() = runBlocking {
        val position = PlayerController.currentPositionMs.first()
        val duration = PlayerController.durationMs.first()
        
        if (duration > 0) {
            assertTrue(position <= duration, "Position should not exceed duration")
        }
    }

    @Test
    fun `theme mode is valid`() = runBlocking {
        val themeMode = PlayerController.themeMode.first()
        
        // ThemeMode должен быть 0, 1 или 2
        assertTrue(themeMode in 0..2, "Theme mode should be 0 (system), 1 (light), or 2 (dark)")
    }
}
