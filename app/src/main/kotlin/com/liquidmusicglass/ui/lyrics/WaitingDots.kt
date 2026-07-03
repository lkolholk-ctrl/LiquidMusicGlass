package com.liquidmusicglass.ui.lyrics

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

/**
 * Анимированный индикатор ожидания — три точки.
 *
 * Два режима:
 *  - [progress] в 0..1 — «живой» (Apple Music style): точки последовательно
 *    наливаются по мере проигрыша (видно, сколько осталось ждать), вся группа
 *    мягко дышит, а на последних ~10% схлопывается — вокал вот-вот вернётся;
 *  - [progress] < 0 — прежний бесконечный пульс (когда прогресс неизвестен).
 */
@Composable
fun WaitingDots(
    modifier: Modifier = Modifier,
    dotColor: Color = Color.White,
    dotSize: androidx.compose.ui.unit.Dp = 12.dp,
    spacing: androidx.compose.ui.unit.Dp = 12.dp,
    animationDuration: Int = 1200,
    progress: Float = -1f
) {
    val infiniteTransition = rememberInfiniteTransition(label = "waitingDots")

    if (progress in 0f..1f) {
        // Дыхание всей группы — медленное, ненавязчивое.
        val breath by infiniteTransition.animateFloat(
            initialValue = 0.94f,
            targetValue = 1.06f,
            animationSpec = infiniteRepeatable(
                animation = tween(animationDuration, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "breath"
        )
        // Схлопывание перед вокалом: последние 10% ожидания.
        val exit = ((progress - 0.90f) / 0.10f).coerceIn(0f, 1f)

        Row(
            modifier = modifier.graphicsLayer {
                val s = breath * (1f - 0.55f * exit)
                scaleX = s
                scaleY = s
                alpha = 1f - exit
            },
            horizontalArrangement = Arrangement.spacedBy(spacing),
            verticalAlignment = Alignment.CenterVertically
        ) {
            repeat(3) { index ->
                // Точки наливаются по очереди: i-я заполняется на своей трети
                // ожидания (с лёгким перекрытием, чтобы не было «ступенек»).
                val fill = (progress * 3.6f - index * 1.2f).coerceIn(0f, 1f)
                Box(
                    modifier = Modifier
                        .size(dotSize)
                        .graphicsLayer {
                            val s = 0.78f + 0.28f * fill
                            scaleX = s
                            scaleY = s
                        }
                        .background(dotColor.copy(alpha = 0.22f + 0.68f * fill), CircleShape)
                )
            }
        }
        return
    }

    // Прежний режим: бесконечный пульс со сдвигом фазы.
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(spacing),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(3) { index ->
            val delayMs = index * (animationDuration / 3)
            val alpha by infiniteTransition.animateFloat(
                initialValue = 0.3f,
                targetValue = 1.0f,
                animationSpec = infiniteRepeatable(
                    animation = tween(
                        durationMillis = animationDuration / 2,
                        delayMillis = delayMs,
                        easing = FastOutSlowInEasing
                    ),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "dotAlpha$index"
            )

            Box(
                modifier = Modifier
                    .size(dotSize)
                    .alpha(alpha)
                    .background(dotColor.copy(alpha = alpha), CircleShape)
            )
        }
    }
}
