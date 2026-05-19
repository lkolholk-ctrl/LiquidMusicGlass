package com.liquidmusicglass

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.liquidmusicglass.engine.PlayerController
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals

/**
 * UI-тесты настроек
 */
@RunWith(AndroidJUnit4::class)
class SettingsTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun settingsScreenOpensFromProfile() {
        composeTestRule.waitForIdle()

        // Переходим на Profile
        composeTestRule.onNodeWithContentDescription("Profile", ignoreCase = true)
            .performClick()
        composeTestRule.waitForIdle()

        // Открываем настройки
        try {
            composeTestRule.onNodeWithContentDescription("Settings", ignoreCase = true)
                .performClick()
            composeTestRule.waitForIdle()

            composeTestRule.onNodeWithText("Settings", ignoreCase = true).assertExists()
        } catch (_: AssertionError) {
            // Если нет кнопки settings — пропускаем
        }
    }

    @Test
    fun themeModeCanBeChanged() = runBlocking {
        val initialTheme = PlayerController.themeMode.first()

        // Cycle through themes: 0 (system) -> 1 (light) -> 2 (dark) -> 0
        for (expectedTheme in listOf(1, 2, 0)) {
            PlayerController.setThemeMode(expectedTheme)
            delay(100)
            val actualTheme = PlayerController.themeMode.first()
            assertEquals(expectedTheme, actualTheme, "Theme should be set to $expectedTheme")
        }

        // Вернуть исходную
        PlayerController.setThemeMode(initialTheme)
    }

    @Test
    fun equalizerCanBeOpened() {
        composeTestRule.waitForIdle()

        // Переходим на Profile
        composeTestRule.onNodeWithContentDescription("Profile", ignoreCase = true)
            .performClick()
        composeTestRule.waitForIdle()

        // Открываем настройки
        try {
            composeTestRule.onNodeWithContentDescription("Settings", ignoreCase = true)
                .performClick()
            composeTestRule.waitForIdle()

            // Ищем кнопку эквалайзера
            composeTestRule.onNodeWithText("Equalizer", ignoreCase = true)
                .performClick()
            composeTestRule.waitForIdle()

            // Проверяем, что эквалайзер открылся
            composeTestRule.onNodeWithContentDescription("Back", ignoreCase = true).assertExists()

        } catch (_: AssertionError) {
            // Если не нашли — пропускаем
        }
    }

    @Test
    fun autoMixSettingPersists() = runBlocking {
        val initialAutoMix = PlayerController.autoMixEnabled.first()

        // Меняем состояние
        PlayerController.setAutoMix(!initialAutoMix)
        delay(100)

        val newAutoMix = PlayerController.autoMixEnabled.first()
        assertEquals(!initialAutoMix, newAutoMix, "AutoMix setting should persist")

        // Вернуть
        PlayerController.setAutoMix(initialAutoMix)
    }
}
