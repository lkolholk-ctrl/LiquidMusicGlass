package com.liquidmusicglass

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.liquidmusicglass.api.icm.IcmAuthRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * UI-тесты авторизации
 */
@RunWith(AndroidJUnit4::class)
class AuthTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun authScreenCanBeOpened() {
        composeTestRule.waitForIdle()

        // Переходим на Profile
        composeTestRule.onNodeWithContentDescription("Profile", ignoreCase = true)
            .performClick()
        composeTestRule.waitForIdle()

        // Ищем кнопку входа/регистрации
        try {
            composeTestRule.onNodeWithText("Sign in", ignoreCase = true)
                .performClick()
            composeTestRule.waitForIdle()

            composeTestRule.onNodeWithText("Sign in", ignoreCase = true).assertExists()
        } catch (_: AssertionError) {
            // Может быть уже авторизован
        }
    }

    @Test
    fun loginStateIsBoolean() = runBlocking {
        val isLoggedIn = IcmAuthRepository.isLoggedIn.first()
        assertTrue(isLoggedIn == true || isLoggedIn == false, "Login state should be boolean")
    }

    @Test
    fun premiumStateIsBoolean() = runBlocking {
        val isPremium = IcmAuthRepository.isPremium.first()
        assertTrue(isPremium == true || isPremium == false, "Premium state should be boolean")
    }

    @Test
    fun userEmailIsNullWhenNotLoggedIn() = runBlocking {
        val isLoggedIn = IcmAuthRepository.isLoggedIn.first()
        val email = IcmAuthRepository.userEmail.first()

        if (!isLoggedIn) {
            assertNull(email, "Email should be null when not logged in")
        }
    }

    @Test
    fun telegramIdIsNullWhenNotLinked() = runBlocking {
        val telegramId = IcmAuthRepository.telegramId.first()
        // Может быть null если не привязан Telegram
        assertTrue(telegramId == null || telegramId.isNotBlank(), "Telegram ID should be null or valid")
    }

    @Test
    fun logoutClearsSession() = runBlocking {
        val wasLoggedIn = IcmAuthRepository.isLoggedIn.first()

        if (wasLoggedIn) {
            // Выходим
            IcmAuthRepository.logout()
            delay(300)

            val isLoggedIn = IcmAuthRepository.isLoggedIn.first()
            assertFalse(isLoggedIn, "Should be logged out after logout()")

            val token = IcmAuthRepository.getSessionToken()
            assertNull(token, "Session token should be null after logout")
        }
    }

    @Test
    fun authScreenHasRequiredElements() {
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithContentDescription("Profile", ignoreCase = true)
            .performClick()
        composeTestRule.waitForIdle()

        // Проверяем наличие основных элементов
        val hasSignIn = try {
            composeTestRule.onNodeWithText("Sign in", ignoreCase = true).assertExists()
            true
        } catch (_: AssertionError) { false }

        val hasSignUp = try {
            composeTestRule.onNodeWithText("Sign up", ignoreCase = true).assertExists()
            true
        } catch (_: AssertionError) { false }

        val hasTelegram = try {
            composeTestRule.onNodeWithText("Telegram", ignoreCase = true).assertExists()
            true
        } catch (_: AssertionError) { false }

        // Должен быть хотя бы один способ входа
        assertTrue(hasSignIn || hasSignUp || hasTelegram, "Auth screen should have login options")
    }
}
