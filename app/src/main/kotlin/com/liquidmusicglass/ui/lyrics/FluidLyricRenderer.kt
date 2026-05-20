package com.liquidmusicglass.ui.lyrics

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/**
 * Ультимативный посимвольный рендерер лирики с постоянной скоростью пиксельной волны.
 * Полностью устраняет "лесенку" и рывки, копируя плавное напыление из iOS.
 */
@Composable
fun FluidLyricRenderer(
    text: String,
    progress: Float, // Точное float значение прогресса (0.0 до 1.0)
    activeColor: Color,
    inactiveColor: Color,
    fontSize: TextUnit,
    maxWidthPx: Int,
    isActiveLine: Boolean = true
) {
    if (text.isEmpty()) return

    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current

    val fontWeight = if (isActiveLine) FontWeight.Bold else FontWeight.Normal
    val effectiveFontSize = if (isActiveLine) fontSize * 1.04f else fontSize

    val textStyle = remember(effectiveFontSize, fontWeight) {
        TextStyle(
            fontSize = effectiveFontSize,
            fontWeight = fontWeight,
            lineHeight = 36.sp,
            platformStyle = PlatformTextStyle(includeFontPadding = false)
        )
    }

    val layoutResult = remember(text, textStyle, maxWidthPx) {
        textMeasurer.measure(
            text = text,
            style = textStyle,
            constraints = Constraints(maxWidth = maxWidthPx)
        )
    }

    val canvasWidth = with(density) { layoutResult.size.width.toDp() }
    val canvasHeight = with(density) { layoutResult.size.height.toDp() }

    Canvas(
        modifier = Modifier
            .width(canvasWidth)
            .height(canvasHeight)
    ) {
        val totalWidth = layoutResult.size.width.toFloat()
        if (totalWidth <= 0f) return@Canvas

        // 1. Физическая координата фронта волны в пикселях (движется идеально равномерно)
        val waveX = totalWidth * progress

        // 2. Ширина мягкой зоны размытия волны в пикселях.
        // Задает размер градиентного шлейфа, который плавно "напыляет" цвет на пиксели букв.
        val featherPx = 35f 

        for (i in text.indices) {
            val bounds = try {
                layoutResult.getBoundingBox(i)
            } catch (_: Exception) {
                continue
            }

            if (bounds.right <= bounds.left) continue

            val charWidth = bounds.right - bounds.left

            // 3. Вычисляем состояние буквы относительно абсолютной пиксельной волны waveX
            val localBrush = when {
                // Волна еще не дошла до левой границы буквы плюс шлейф
                waveX + featherPx < bounds.left -> {
                    Brush.horizontalGradient(listOf(inactiveColor, inactiveColor), startX = bounds.left, endX = bounds.right)
                }
                // Волна уже полностью прошла правую границу буквы
                waveX - featherPx > bounds.right -> {
                    Brush.horizontalGradient(listOf(activeColor, activeColor), startX = bounds.left, endX = bounds.right)
                }
                // Волна катится прямо по телу текущей буквы
                else -> {
                    // Переводим глобальные пиксели волны в локальные относительные координаты буквы (0f..1f)
                    val localStartActive = ((waveX - featherPx) - bounds.left) / charWidth
                    val localEndActive = ((waveX + featherPx) - bounds.left) / charWidth

                    val stop0 = localStartActive.coerceIn(0f, 1f)
                    val stop1 = localEndActive.coerceIn(0f, 1f)

                    Brush.horizontalGradient(
                        colorStops = arrayOf(
                            0f to activeColor,
                            stop0 to activeColor,
                            stop1 to inactiveColor,
                            1f to inactiveColor
                        ),
                        startX = bounds.left,
                        endX = bounds.right
                    )
                }
            }

            // Аппаратное отсечение для экономии ресурсов GPU
            clipRect(
                left = bounds.left,
                top = bounds.top,
                right = bounds.right,
                bottom = bounds.bottom
            ) {
                drawText(
                    textLayoutResult = layoutResult,
                    brush = localBrush,
                    topLeft = Offset.Zero
                )
            }
        }
    }
}
