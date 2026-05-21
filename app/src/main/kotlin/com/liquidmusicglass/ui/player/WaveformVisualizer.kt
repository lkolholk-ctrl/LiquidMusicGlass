package com.liquidmusicglass.ui.player

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.*
import kotlin.random.Random

/**
 * Audio Waveform Visualizer — стилизованная волна как в VK X.
 * Рисует анимированные столбцы амплитуды с плавными переходами.
 */
@Composable
fun WaveformVisualizer(
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    barCount: Int = 48,
    barWidth: Float = 3f,
    barGap: Float = 2f,
    activeColor: Color = Color(0xFFFC3C44),
    inactiveColor: Color = Color.White.copy(alpha = 0.15f),
    minBarHeight: Float = 4f,
    maxBarHeight: Float = 32f
) {
    val random = remember { Random(System.currentTimeMillis()) }
    
    // Генерируем seed-амплитуды для каждого столбца
    val seeds = remember {
        FloatArray(barCount) { random.nextFloat() }
    }
    
    // Текущие амплитуды
    val amplitudes = remember {
        mutableStateListOf(*FloatArray(barCount) { minBarHeight }.toTypedArray())
    }
    
    // Целевые амплитуды
    val targets = remember {
        FloatArray(barCount)
    }
    
    LaunchedEffect(isPlaying) {
        while (isActive) {
            if (isPlaying) {
                // Генерируем новые целевые амплитуды
                val time = System.currentTimeMillis() / 1000f
                for (i in 0 until barCount) {
                    val seed = seeds[i]
                    // Комбинация нескольких синусоид для органичного вида
                    val wave1 = sin(time * 3f + seed * 10f)
                    val wave2 = sin(time * 5f + seed * 20f + 1.5f) * 0.5f
                    val wave3 = sin(time * 2f + seed * 5f + 3f) * 0.3f
                    val combined = (wave1 + wave2 + wave3) / 1.8f
                    
                    // Центральные столбцы выше (как в VK X — горбатая форма)
                    val centerBias = 1f - abs(i - barCount / 2f) / (barCount / 2f)
                    val heightFactor = (combined * 0.5f + 0.5f) * (0.4f + centerBias * 0.6f)
                    
                    targets[i] = minBarHeight + (maxBarHeight - minBarHeight) * heightFactor
                }
            } else {
                // Когда пауза — все столбцы к минимуму
                for (i in 0 until barCount) {
                    targets[i] = minBarHeight
                }
            }
            
            delay(50) // 20fps обновление
        }
    }
    
    // Интерполяция амплитуд
    LaunchedEffect(Unit) {
        while (isActive) {
            for (i in 0 until barCount) {
                val current = amplitudes[i]
                val target = targets[i]
                // Smooth lerp
                amplitudes[i] = current + (target - current) * 0.15f
            }
            delay(16) // ~60fps
        }
    }
    
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height((maxBarHeight * 2).dp)
    ) {
        val totalWidth = barCount * barWidth + (barCount - 1) * barGap
        val startX = (size.width - totalWidth) / 2f
        val centerY = size.height / 2f
        
        for (i in 0 until barCount) {
            val x = startX + i * (barWidth + barGap)
            val height = amplitudes[i]
            
            // Цвет: активный для центральных, иначе inactive
            val color = if (isPlaying && height > minBarHeight * 2f) {
                activeColor.copy(alpha = 0.6f + (height / maxBarHeight) * 0.4f)
            } else {
                inactiveColor
            }
            
            // Рисуем столбец (симметрично от центра)
            drawRoundRect(
                color = color,
                topLeft = Offset(x, centerY - height),
                size = Size(barWidth, height * 2),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(barWidth / 2, barWidth / 2)
            )
        }
    }
}

/**
 * Компактный вариант для mini-player
 */
@Composable
fun WaveformVisualizerCompact(
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    barCount: Int = 24
) {
    WaveformVisualizer(
        isPlaying = isPlaying,
        modifier = modifier,
        barCount = barCount,
        barWidth = 2f,
        barGap = 1.5f,
        maxBarHeight = 16f
    )
}
