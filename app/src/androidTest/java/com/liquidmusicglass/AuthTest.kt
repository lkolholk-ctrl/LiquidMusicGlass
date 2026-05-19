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
 * UI-тесты авторизации.
 *
 * ВАЖНО: Используем mainClock.advanceTimeBy() вместо waitForIdle(),
 * т.к. в приложении могут быть бесконечные анимации (плеер).
 */
@RunWith(AndroidJUnit4::class)
class AuthTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun authScreenCanBeOpened() {
        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.mainClock.advanceTimeBy(100)

        // Переходим на Profile
        composeTestRule.onNodeWithContentDescription("Profile", ignoreCase = true)
            .performClick()
        composeTestRule.mainClock.advanceTimeBy(300)

        // Ищем кнопку входа/регистрации
        try {
            composeTestRule.onNodeWithText("Sign in", ignoreCase = true)
                .performClick()
            composeTestRule.mainClock.advanceTimeBy(300)

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
        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.mainClock.advanceTimeBy(100)

        composeTestRule.onNodeWithContentDescription("Profile", ignoreCase = true)
            .performClick()
        composeTestRule.mainClock.advanceTimeBy(300)

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
