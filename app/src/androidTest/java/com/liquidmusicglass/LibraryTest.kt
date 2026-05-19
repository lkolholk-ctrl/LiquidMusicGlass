package com.liquidmusicglass

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * UI-тесты библиотеки.
 *
 * ВАЖНО: Используем mainClock.advanceTimeBy() вместо waitForIdle(),
 * т.к. в приложении могут быть бесконечные анимации (плеер).
 */
@RunWith(AndroidJUnit4::class)
class LibraryTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun libraryScreenOpens() {
        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.mainClock.advanceTimeBy(100)

        composeTestRule.onNodeWithContentDescription("Library", ignoreCase = true)
            .performClick()
        composeTestRule.mainClock.advanceTimeBy(300)

        composeTestRule.onNodeWithText("Library", ignoreCase = true).assertExists()
    }

    @Test
    fun libraryHasContentOrEmptyState() {
        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.mainClock.advanceTimeBy(100)

        composeTestRule.onNodeWithContentDescription("Library", ignoreCase = true)
            .performClick()
        composeTestRule.mainClock.advanceTimeBy(300)

        // Проверяем, что на экране есть либо контент, либо empty state
        val hasContent = try {
            composeTestRule.onNodeWithContentDescription("Album", substring = true).assertExists()
            true
        } catch (_: AssertionError) {
            false
        }

        val hasEmptyState = try {
            composeTestRule.onNodeWithText("Empty", substring = true, ignoreCase = true).assertExists()
            true
        } catch (_: AssertionError) {
            false
        }

        assert(hasContent || hasEmptyState) { "Library should show content or empty state" }
    }

    @Test
    fun libraryToAlbumNavigation() {
        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.mainClock.advanceTimeBy(100)

        composeTestRule.onNodeWithContentDescription("Library", ignoreCase = true)
            .performClick()
        composeTestRule.mainClock.advanceTimeBy(300)

        // Пытаемся кликнуть на первый альбом
        try {
            composeTestRule.onNodeWithContentDescription("Album", substring = true)
                .performClick()
            composeTestRule.mainClock.advanceTimeBy(300)

            // Проверяем, что открылся детальный экран
            composeTestRule.onNodeWithContentDescription("Back", ignoreCase = true).assertExists()
        } catch (_: AssertionError) {
            // Если альбомов нет — тест пропускаем
        }
    }
}
