package com.liquidmusicglass

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.ActivityTestRule
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiSelector
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertTrue

/**
 * Smoke test — проверяет, что приложение запускается без крашей
 * и основные экраны открываются.
 */
@RunWith(AndroidJUnit4::class)
class SmokeTest {

    @get:Rule
    val activityRule = ActivityTestRule(MainActivity::class.java)

    @Test
    fun appLaunchesWithoutCrash() {
        // Приложение уже запущено через ActivityTestRule
        // Если дошли сюда — MainActivity не упал на старте
        assertTrue(true, "App launched successfully")
    }

    @Test
    fun mainScreenIsVisible() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        // Ждём загрузки UI
        Thread.sleep(3000)
        
        // Проверяем, что на экране есть что-то из главного экрана
        // (bottom navigation или content)
        val bottomBar = device.findObject(UiSelector().resourceIdMatches(".*bottom.*"))
        assertTrue(bottomBar.exists() || device.findObject(UiSelector().clickable(true)).exists(), 
            "Main screen should be visible")
    }

    @Test
    fun navigationTabsWork() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        Thread.sleep(2000)
        
        // Ищем bottom navigation items и кликаем по ним
        val tabs = listOf("Home", "Search", "Library", "Profile")
        var clickedAny = false
        
        for (tabName in tabs) {
            val tab = device.findObject(UiSelector().text(tabName))
            if (tab.exists()) {
                tab.click()
                Thread.sleep(1000)
                clickedAny = true
            }
        }
        
        assertTrue(clickedAny, "Should be able to navigate between tabs")
    }
}
