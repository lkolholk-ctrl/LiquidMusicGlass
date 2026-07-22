package com.liquidmusicglass.ui

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration

/**
 * Единый адаптивный механизм для ВСЕГО приложения: телефон-портрет,
 * телефон-альбом и планшет описываются одними брейкпоинтами по ширине окна.
 *
 * Почему по ширине, а не по ориентации: планшет в портрете тоже широкий и
 * уместен под две колонки, а телефон в альбоме — под сплит. `screenWidthDp`
 * (dp!) сам учитывает и физический размер, и ориентацию.
 *
 * Правило для экранов:
 *   - COMPACT  → одноколоночный layout (как сейчас, НЕ трогать).
 *   - MEDIUM+  → двухколоночный/сплит (телефон-альбом, маленький планшет).
 *   - EXPANDED → полноценный планшетный layout (сайдбар + 2-3 колонки).
 *
 * Использование:
 *   val win = rememberWindowInfo()
 *   if (win.useSideBySide) { /* широкая ветка */ } else { /* компактная */ }
 */
enum class WindowWidth { COMPACT, MEDIUM, EXPANDED }

data class WindowInfo(
    val width: WindowWidth,
    val isLandscape: Boolean,
    val widthDp: Int,
    val heightDp: Int,
) {
    /** Достаточно места для двухколоночного/сплит-layout (телефон-альбом и шире). */
    val useSideBySide: Boolean get() = width != WindowWidth.COMPACT

    /** Планшетная ширина — можно показывать боковую навигацию + несколько колонок. */
    val isTablet: Boolean get() = width == WindowWidth.EXPANDED
}

/** Material3-совместимые пороги: <600 compact, 600–839 medium, ≥840 expanded. */
@Composable
fun rememberWindowInfo(): WindowInfo {
    val cfg = LocalConfiguration.current
    val w = cfg.screenWidthDp
    val h = cfg.screenHeightDp
    val landscape = cfg.orientation == Configuration.ORIENTATION_LANDSCAPE
    return remember(w, h, landscape) {
        val width = when {
            w < 600 -> WindowWidth.COMPACT
            w < 840 -> WindowWidth.MEDIUM
            else -> WindowWidth.EXPANDED
        }
        WindowInfo(width = width, isLandscape = landscape, widthDp = w, heightDp = h)
    }
}
