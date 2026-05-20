package com.liquidmusicglass.ui.lyrics

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Отдельное слово лирики с плавным градиентным закрашиванием (караоке-эффект).
 *
 * Использует Brush.horizontalGradient в TextStyle — Skia/Impeller автоматически
 * применяет субпиксельное сглаживание (anti-aliasing) на стыке цветов.
 * Никаких clipRect, никакой посимвольной отрисовки.
 *
 * @param text текст слова
 * @param progress прогресс закрашивания (0f..1f), плавный Float от плеера
 * @param color базовый цвет слова (для upcoming / неактивной части)
 * @param activeColor цвет активной (закрашенной) части
 * @param fontSize размер шрифта
 * @param fontWeight вес шрифта
 */
@Composable
fun LyricsWord(
    text: String,
    progress: Float = 0f,
    color: Color = Color.White.copy(alpha = LyricsTimeProcessor.UPCOMING_ALPHA),
    activeColor: Color = Color.White,
    fontSize: TextUnit = 26.sp,
    fontWeight: FontWeight = FontWeight.SemiBold,
    modifier: Modifier = Modifier
) {
    val clampedProgress = progress.coerceIn(0f, 1f)

    // Ультра-короткий переход 1-2% — полностью убирает пиксельные стыки
    val transitionStart = clampedProgress
    val transitionEnd = (clampedProgress + 0.015f).coerceAtMost(1.0f)

    val karaokeBrush = Brush.horizontalGradient(
        colorStops = arrayOf(
            0.0f to activeColor,
            transitionStart to activeColor,
            transitionEnd to color,
            1.0f to color
        )
    )

    Text(
        text = text,
        style = TextStyle(
            brush = karaokeBrush,
            fontSize = fontSize,
            fontWeight = fontWeight
        ),
        modifier = modifier.padding(end = 6.dp),
        softWrap = false
    )
}
