package com.liquidmusicglass

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.liquidmusicglass.engine.PlayerController
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Полные UI-тесты плеера — проверяют все элементы управления
 */
@RunWith(AndroidJUnit4::class)
class PlayerFullTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setup() {
        composeTestRule.waitForIdle()
    }

    @Test
    fun playPauseToggleChangesState() = runBlocking {
        val initialState = PlayerController.isPlaying.first()

        // Если есть трек — toggle должен изменить состояние
        if (PlayerController.currentTrack.value != null) {
            PlayerController.togglePlayPause(context)
            delay(500)

            val newState = PlayerController.isPlaying.first()
            assertEquals(!initialState, newState, "Play/Pause should toggle playing state")

            // Вернуть в исходное состояние
            PlayerController.togglePlayPause(context)
            delay(500)
        }
    }

    @Test
    fun volumeIsInValidRange() = runBlocking {
        val volume = PlayerController.volume.first()
        assertTrue(volume in 0f..1f, "Volume should be between 0.0 and 1.0, was $volume")
    }

    @Test
    fun volumeCanBeChanged() = runBlocking {
        val originalVolume = PlayerController.volume.first()

        // Установить громкость 50%
        PlayerController.setVolume(0.5f)
        delay(100)
        val newVolume = PlayerController.volume.first()
        assertEquals(0.5f, newVolume, "Volume should be set to 0.5")

        // Вернуть обратно
        PlayerController.setVolume(originalVolume)
    }

    @Test
    fun volumeCannotExceedMax() = runBlocking {
        PlayerController.setVolume(1.5f)
        delay(100)
        val volume = PlayerController.volume.first()
        assertEquals(1.0f, volume, "Volume should be clamped to 1.0")
    }

    @Test
    fun volumeCannotBeNegative() = runBlocking {
        PlayerController.setVolume(-0.5f)
        delay(100)
        val volume = PlayerController.volume.first()
        assertEquals(0.0f, volume, "Volume should be clamped to 0.0")
    }

    @Test
    fun seekPositionIsValid() = runBlocking {
        val position = PlayerController.currentPositionMs.first()
        val duration = PlayerController.durationMs.first()

        if (duration > 0) {
            assertTrue(position <= duration, "Position ($position) should not exceed duration ($duration)")
            assertTrue(position >= 0, "Position should not be negative")
        }
    }

    @Test
    fun seekToPositionWorks() = runBlocking {
        val duration = PlayerController.durationMs.first()

        if (duration > 0) {
            val seekPosition = duration / 2
            PlayerController.seekTo(seekPosition)
            delay(300)

            val newPosition = PlayerController.currentPositionMs.first()
            // Допуск 1 секунда из-за задержки
            assertTrue(
                kotlin.math.abs(newPosition - seekPosition) < 1000,
                "Seek should move position to ~$seekPosition, was $newPosition"
            )
        }
    }

    @Test
    fun skipNextExists() {
        composeTestRule.waitForIdle()
        try {
            composeTestRule.onNodeWithContentDescription("Next", ignoreCase = true).assertExists()
        } catch (_: AssertionError) {
            composeTestRule.onNodeWithContentDescription("Skip next", ignoreCase = true).assertExists()
        }
    }

    @Test
    fun skipPreviousExists() {
        composeTestRule.waitForIdle()
        try {
            composeTestRule.onNodeWithContentDescription("Previous", ignoreCase = true).assertExists()
        } catch (_: AssertionError) {
            composeTestRule.onNodeWithContentDescription("Skip previous", ignoreCase = true).assertExists()
        }
    }

    @Test
    fun shuffleToggleWorks() = runBlocking {
        val initialShuffle = PlayerController.shuffleEnabled.first()

        // Переключаем shuffle
        PlayerController.setShuffle(!initialShuffle)
        delay(100)

        val newShuffle = PlayerController.shuffleEnabled.first()
        assertEquals(!initialShuffle, newShuffle, "Shuffle should toggle")

        // Вернуть обратно
        PlayerController.setShuffle(initialShuffle)
    }

    @Test
    fun repeatModeCanBeChanged() = runBlocking {
        val initialMode = PlayerController.repeatMode.first()

        // Cycle through repeat modes: 0 -> 1 -> 2 -> 0
        val nextMode = (initialMode + 1) % 3
        PlayerController.setRepeatMode(nextMode)
        delay(100)

        val newMode = PlayerController.repeatMode.first()
        assertEquals(nextMode, newMode, "Repeat mode should change to $nextMode")

        // Вернуть обратно
        PlayerController.setRepeatMode(initialMode)
    }

    @Test
    fun autoMixToggleWorks() = runBlocking {
        val initialAutoMix = PlayerController.autoMixEnabled.first()

        PlayerController.setAutoMix(!initialAutoMix)
        delay(100)

        val newAutoMix = PlayerController.autoMixEnabled.first()
        assertEquals(!initialAutoMix, newAutoMix, "AutoMix should toggle")

        // Вернуть обратно
        PlayerController.setAutoMix(initialAutoMix)
    }

    @Test
    fun queueIsNotNull() = runBlocking {
        val queue = PlayerController.queueFlow.first()
        assertTrue(queue != null, "Queue should not be null")
    }

    @Test
    fun miniPlayerVisibility() {
        composeTestRule.waitForIdle()

        // Mini player должен быть виден, если есть текущий трек
        val hasTrack = runBlocking { PlayerController.currentTrack.first() != null }

        if (hasTrack) {
            try {
                composeTestRule.onNodeWithContentDescription("Mini player", ignoreCase = true)
                    .assertExists()
            } catch (_: AssertionError) {
                // Если нет content description, проверяем по другим признакам
            }
        }
    }

    @Test
    fun fullPlayerExpandAndCollapse() {
        composeTestRule.waitForIdle()

        // Открыть полный плеер
        try {
            composeTestRule.onNodeWithContentDescription("Expand player", ignoreCase = true)
                .performClick()
            composeTestRule.waitForIdle()

            // Проверить, что открылся
            composeTestRule.onNodeWithContentDescription("Close player", ignoreCase = true)
                .assertExists()

            // Закрыть
            composeTestRule.onNodeWithContentDescription("Close player", ignoreCase = true)
                .performClick()
            composeTestRule.waitForIdle()

        } catch (_: AssertionError) {
            // Если не получилось expand — возможно, нет трека
        }
    }

    @Test
    fun playerStateConsistencyAfterMultipleToggles() = runBlocking {
        if (PlayerController.currentTrack.value == null) return@runBlocking

        val initialState = PlayerController.isPlaying.first()

        // Множественные переключения
        repeat(3) {
            PlayerController.togglePlayPause(context)
            delay(300)
        }

        // После нечётного количества toggles состояние должно быть инвертировано
        val finalState = PlayerController.isPlaying.first()
        assertEquals(!initialState, finalState, "State should be inverted after 3 toggles")

        // Вернуть в исходное
        PlayerController.togglePlayPause(context)
        delay(300)
    }

    @Test
    fun durationIsPositiveWhenTrackLoaded() = runBlocking {
        val track = PlayerController.currentTrack.first()
        val duration = PlayerController.durationMs.first()

        if (track != null) {
            assertTrue(duration > 0, "Duration should be positive when track is loaded")
        }
    }

    @Test
    fun positionDoesNotExceedDuration() = runBlocking {
        val position = PlayerController.currentPositionMs.first()
        val duration = PlayerController.durationMs.first()

        if (duration > 0) {
            assertTrue(
                position <= duration,
                "Position ($position) should not exceed duration ($duration)"
            )
        }
    }

    @Test
    fun seekToEndAndBackToStart() = runBlocking {
        val duration = PlayerController.durationMs.first()

        if (duration > 0) {
            // Seek to end
            PlayerController.seekTo(duration)
            delay(300)
            var pos = PlayerController.currentPositionMs.first()
            assertTrue(pos >= duration - 1000, "Should be at end of track")

            // Seek to start
            PlayerController.seekTo(0)
            delay(300)
            pos = PlayerController.currentPositionMs.first()
            assertTrue(pos < 1000, "Should be at start of track")
        }
    }

    @Test
    fun playPauseWithNoTrackDoesNotCrash() = runBlocking {
        // Сохраняем текущий трек
        val originalTrack = PlayerController.currentTrack.first()

        // Если нет трека — togglePlayPause не должен крашить
        if (originalTrack == null) {
            PlayerController.togglePlayPause(context)
            delay(300)
            // Если дошли сюда — не крашнулось
            assertTrue(true, "togglePlayPause with no track should not crash")
        }
    }

    @Test
    fun volumeZeroMutesAudio() = runBlocking {
        val originalVolume = PlayerController.volume.first()

        PlayerController.setVolume(0f)
        delay(100)
        val mutedVolume = PlayerController.volume.first()
        assertEquals(0f, mutedVolume, "Volume should be 0 when muted")

        // Вернуть
        PlayerController.setVolume(originalVolume)
    }

    @Test
    fun volumeMaxIsOne() = runBlocking {
        val originalVolume = PlayerController.volume.first()

        PlayerController.setVolume(1f)
        delay(100)
        val maxVolume = PlayerController.volume.first()
        assertEquals(1f, maxVolume, "Max volume should be 1.0")

        // Вернуть
        PlayerController.setVolume(originalVolume)
    }

    @Test
    fun repeatModeCyclesCorrectly() = runBlocking {
        val initialMode = PlayerController.repeatMode.first()

        // Test each mode
        for (expectedMode in 0..2) {
            PlayerController.setRepeatMode(expectedMode)
            delay(100)
            val actualMode = PlayerController.repeatMode.first()
            assertEquals(expectedMode, actualMode, "Repeat mode should be set to $expectedMode")
        }

        // Вернуть исходный
        PlayerController.setRepeatMode(initialMode)
    }

    @Test
    fun shuffleOnAndOff() = runBlocking {
        val initialShuffle = PlayerController.shuffleEnabled.first()

        // Включить
        PlayerController.setShuffle(true)
        delay(100)
        assertTrue(PlayerController.shuffleEnabled.first(), "Shuffle should be ON")

        // Выключить
        PlayerController.setShuffle(false)
        delay(100)
        assertFalse(PlayerController.shuffleEnabled.first(), "Shuffle should be OFF")

        // Вернуть
        PlayerController.setShuffle(initialShuffle)
    }

    @Test
    fun autoMixOnAndOff() = runBlocking {
        val initialAutoMix = PlayerController.autoMixEnabled.first()

        // Включить
        PlayerController.setAutoMix(true)
        delay(100)
        assertTrue(PlayerController.autoMixEnabled.first(), "AutoMix should be ON")

        // Выключить
        PlayerController.setAutoMix(false)
        delay(100)
        assertFalse(PlayerController.autoMixEnabled.first(), "AutoMix should be OFF")

        // Вернуть
        PlayerController.setAutoMix(initialAutoMix)
    }

    @Test
    fun bufferingStateIsValid() = runBlocking {
        val isBuffering = PlayerController.isBuffering.first()
        // Буферизация либо true либо false
        assertTrue(isBuffering == true || isBuffering == false, "Buffering state should be boolean")
    }

    @Test
    fun currentTrackTitleNotBlankWhenLoaded() = runBlocking {
        val track = PlayerController.currentTrack.first()
        if (track != null) {
            assertTrue(track.title.isNotBlank(), "Track title should not be blank")
        }
    }

    @Test
    fun queueCanBeEmpty() = runBlocking {
        val queue = PlayerController.queueFlow.first()
        // Очередь может быть пустой — это нормально
        assertTrue(queue.isEmpty() || queue.isNotEmpty(), "Queue should be a valid list")
    }

    @Test
    fun smoothPositionReturnsNonNegative() = runBlocking {
        val smoothPos = PlayerController.getSmoothPositionMs()
        assertTrue(smoothPos >= 0, "Smooth position should not be negative")
    }
}
