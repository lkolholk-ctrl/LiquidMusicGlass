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
 * UI-тесты навигации — проверяют переходы между экранами
 */
@RunWith(AndroidJUnit4::class)
class NavigationTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun homeScreenIsDisplayedByDefault() {
        composeTestRule.waitForIdle()
        // Проверяем, что на главном экране есть контент
        composeTestRule.onNodeWithText("Home", ignoreCase = true).assertExists()
    }

    @Test
    fun navigateToSearchScreen() {
        composeTestRule.waitForIdle()
        
        // Кликаем на Search в bottom bar
        composeTestRule.onNodeWithContentDescription("Search", ignoreCase = true)
            .assertExists()
            .performClick()
        
        composeTestRule.waitForIdle()
        
        // Проверяем, что SearchScreen открылся
        composeTestRule.onNodeWithText("Search", ignoreCase = true).assertExists()
    }

    @Test
    fun navigateToLibraryScreen() {
        composeTestRule.waitForIdle()
        
        // Кликаем на Library в bottom bar
        composeTestRule.onNodeWithContentDescription("Library", ignoreCase = true)
            .assertExists()
            .performClick()
        
        composeTestRule.waitForIdle()
        
        // Проверяем, что LibraryScreen открылся
        composeTestRule.onNodeWithText("Library", ignoreCase = true).assertExists()
    }

    @Test
    fun navigateToProfileScreen() {
        composeTestRule.waitForIdle()
        
        // Кликаем на Profile в bottom bar
        composeTestRule.onNodeWithContentDescription("Profile", ignoreCase = true)
            .assertExists()
            .performClick()
        
        composeTestRule.waitForIdle()
        
        // Проверяем, что ProfileScreen открылся
        composeTestRule.onNodeWithText("Profile", ignoreCase = true).assertExists()
    }

    @Test
    fun navigateBackToHomeScreen() {
        composeTestRule.waitForIdle()
        
        // Переходим на Search
        composeTestRule.onNodeWithContentDescription("Search", ignoreCase = true)
            .performClick()
        composeTestRule.waitForIdle()
        
        // Возвращаемся на Home
        composeTestRule.onNodeWithContentDescription("Home", ignoreCase = true)
            .performClick()
        composeTestRule.waitForIdle()
        
        // Проверяем, что HomeScreen отображается
        composeTestRule.onNodeWithText("Home", ignoreCase = true).assertExists()
    }
}
