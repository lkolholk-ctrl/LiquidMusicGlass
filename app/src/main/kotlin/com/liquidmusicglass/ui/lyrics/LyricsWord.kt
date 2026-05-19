package com.liquidmusicglass.ui.lyrics

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Отдельное слово лирики с градиентным наплывом (караоке-эффект).
 *
 * @param text текст слова
 * @param progress прогресс наплыва (0f..1f), уже интерполирован через PathInterpolator
 *                 и вычислен на каждом кадре отрисовки (60/120 FPS)
 * @param color базовый цвет слова (для past/upcoming)
 * @param activeColor цвет активного наплыва (обычно белый)
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
    // НЕ используем animateFloatAsState — progress уже плавный (60/120 FPS)
    // Дополнительная анимация добавит лаг ~16мс и размытие движения
    val clampedProgress = progress.coerceIn(0f, 1f)

    val textStyle = TextStyle(
        color = color,
        fontSize = fontSize,
        fontWeight = fontWeight
    )

    val textMeasurer = rememberTextMeasurer()

    Text(
        text = text,
        style = textStyle,
        modifier = modifier
            .padding(end = 6.dp)
            .drawWithContent {
                // 1. Рисуем базовый текст (приглушённый)
                drawContent()

                // 2. Рисуем наплыв активного цвета через clipRect
                if (clampedProgress > 0.005f) {
                    val measured = textMeasurer.measure(text, textStyle)
                    val clipWidth = measured.size.width.toFloat() * clampedProgress
                    clipRect(
                        left = 0f,
                        top = 0f,
                        right = clipWidth,
                        bottom = measured.size.height.toFloat()
                    ) {
                        drawText(
                            textMeasurer = textMeasurer,
                            text = text,
                            style = textStyle.copy(color = activeColor),
                            topLeft = Offset.Zero
                        )
                    }
                }
            },
        softWrap = false
    )
}
