package com.liquidmusicglass.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.util.lerp
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.liquidmusicglass.ui.theme.AppFontFamily
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Пресет «Моей волны»: подпись + цвет (свой у каждого муда).
 */
data class WaveOrbPreset(val label: String, val color: Color)

val WAVE_ORB_PRESETS = listOf(
    WaveOrbPreset("Прогрессив-хаус", Color(0xFF3B6FE0)),
    WaveOrbPreset("Бегаю под летние треки", Color(0xFF2FB24A)),
    WaveOrbPreset("Спокойный вечер", Color(0xFF17A2A2)),
    WaveOrbPreset("Хочется инди", Color(0xFF7A5BE0)),
    WaveOrbPreset("В дороге", Color(0xFFE07B2F)),
    WaveOrbPreset("Танцпол", Color(0xFFE0405F))
)

// ── tuning ──
private const val R_FACTOR = 1.35f          // радиус колеса = ширина * R_FACTOR
private const val SPACING = 1.04f            // плотность (1.0 = впритык)
private const val VISIBLE_HALF = 1.15f       // полу-угол видимой дуги (рад)
private const val EDGE_SCALE = 0.5f          // масштаб орба на краю дуги
private const val EDGE_ALPHA = 0.28f         // прозрачность на краю
private const val AUTO_STEP_MS = 2600        // время доезда до след. орба
private const val AUTO_PAUSE_MS = 700L       // пауза на орбе
private const val DIR = 1f                   // знак вращения (флипнуть при необходимости)

/**
 * Вращающаяся карусель аврора-капель. Центр колеса — ниже экрана,
 * видна верхняя дуга. Автоповорот + флик с инерцией + снап к ближайшему орбу.
 */
@Composable
fun WaveOrbCarousel(
    onSelect: (WaveOrbPreset) -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val measurer = rememberTextMeasurer()
    val rotation = remember { Animatable(0f) }
    val presets = WAVE_ORB_PRESETS
    val n = presets.size

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(280.dp)
    ) {
        val density = LocalDensity.current
        val wPx = constraints.maxWidth.toFloat()
        val hPx = constraints.maxHeight.toFloat()
        val orbR = with(density) { 58.dp.toPx() }
        val radius = wPx * R_FACTOR
        val dTheta = (2f * orbR * SPACING) / radius   // шаг между орбами
        val centerX = wPx / 2f
        val centerY = hPx * 0.42f + radius            // центр колеса ниже экрана
        val frontPhi = -kotlin.math.PI.toFloat() / 2f // верх дуги = фронт

        // ── подписи мерим один раз (не каждый кадр) ──
        val labelFontSp = with(density) { (orbR * 0.20f).toSp() }
        val labelMaxW = (orbR * 1.5f).toInt().coerceAtLeast(1)
        val labelLayouts = remember(presets, labelFontSp, labelMaxW) {
            presets.map { preset ->
                measurer.measure(
                    text = preset.label,
                    style = TextStyle(
                        color = Color.White,
                        fontSize = labelFontSp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = AppFontFamily,
                        textAlign = TextAlign.Center,
                        lineHeight = labelFontSp * 1.15f,
                        shadow = androidx.compose.ui.graphics.Shadow(
                            color = Color.Black.copy(alpha = 0.85f),
                            offset = Offset(0f, 2f),
                            blurRadius = 6f
                        )
                    ),
                    constraints = Constraints(maxWidth = labelMaxW),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // ── авто-доводка до следующего орба, циклично ──
        var autoJob by remember { mutableStateOf<Job?>(null) }
        fun startAuto() {
            autoJob?.cancel()
            autoJob = scope.launch {
                while (isActive) {
                    val next = (kotlin.math.round(rotation.value / dTheta) + DIR) * dTheta
                    rotation.animateTo(next, tween(AUTO_STEP_MS, easing = LinearEasing))
                    delay(AUTO_PAUSE_MS)
                }
            }
        }
        LaunchedEffect(dTheta) { startAuto() }

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(280.dp)
                .draggable(
                    orientation = androidx.compose.foundation.gestures.Orientation.Horizontal,
                    state = rememberDraggableState { dx ->
                        autoJob?.cancel()
                        scope.launch { rotation.snapTo(rotation.value + DIR * dx / radius) }
                    },
                    onDragStopped = { velocity ->
                        scope.launch {
                            rotation.animateDecay(DIR * velocity / radius, exponentialDecay(frictionMultiplier = 1.6f))
                            val snapped = kotlin.math.round(rotation.value / dTheta) * dTheta
                            rotation.animateTo(snapped, spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessLow))
                            startAuto()
                        }
                    }
                )
                .pointerInput(Unit) {
                    detectTapGestures { tap ->
                        // ближайший по экрану орб
                        val rot = rotation.value
                        val front = (rot / dTheta).roundToInt()
                        var bestK = front
                        var bestDist = Float.MAX_VALUE
                        for (k in front - 4..front + 4) {
                            val phi = frontPhi + (k * dTheta - rot)
                            val cx = centerX + radius * cos(phi)
                            val cy = centerY + radius * sin(phi)
                            val d = (tap - Offset(cx, cy)).getDistance()
                            if (d < bestDist) { bestDist = d; bestK = k }
                        }
                        if (bestDist <= orbR * 1.1f) {
                            onSelect(presets[((bestK % n) + n) % n])
                        }
                    }
                }
        ) {
            val rot = rotation.value
            val front = (rot / dTheta).roundToInt()

            // рисуем от краёв к центру (фронт сверху)
            val order = (front - 5..front + 5).sortedByDescending { abs(it * dTheta - rot) }
            for (k in order) {
                val dAng = k * dTheta - rot
                val norm = (abs(dAng) / VISIBLE_HALF).coerceIn(0f, 1f)
                if (abs(dAng) > VISIBLE_HALF) continue

                val phi = frontPhi + dAng
                val cx = centerX + radius * cos(phi)
                val cy = centerY + radius * sin(phi)
                val depth = 1f - norm
                val scale = lerp(EDGE_SCALE, 1f, depth)
                val alpha = lerp(EDGE_ALPHA, 1f, depth)
                val r = orbR * scale
                val preset = presets[((k % n) + n) % n]

                drawAuroraOrb(Offset(cx, cy), r, preset.color, alpha)
                drawOrbLabel(labelLayouts[((k % n) + n) % n], Offset(cx, cy), scale, alpha)
            }
        }
    }
}

/** Матовая светящаяся капля: слоёные радиальные градиенты, без глянца/ободка. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawAuroraOrb(
    center: Offset,
    r: Float,
    color: Color,
    alpha: Float
) {
    val light = lerp(color, Color.White, 0.35f)
    val deep = lerp(color, Color.Black, 0.35f)

    // 1) внешнее гало — мягкое, чтобы орб «сидел» в дымке
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(color.copy(alpha = 0.30f * alpha), Color.Transparent),
            center = center,
            radius = r * 1.7f
        ),
        radius = r * 1.7f,
        center = center
    )
    // 2) тело капли — насыщенное кольцо со спадом к краю; центр спокойный (под текст)
    drawCircle(
        brush = Brush.radialGradient(
            colorStops = arrayOf(
                0.0f to deep.copy(alpha = 0.78f * alpha),
                0.45f to light.copy(alpha = 0.95f * alpha),
                0.80f to color.copy(alpha = 0.70f * alpha),
                1.0f to color.copy(alpha = 0.0f)
            ),
            center = center,
            radius = r
        ),
        radius = r,
        center = center
    )
    // 3) тёмный скрим в центре — чтобы белый текст читался на любом цвете
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(Color.Black.copy(alpha = 0.28f * alpha), Color.Transparent),
            center = center,
            radius = r * 0.72f
        ),
        radius = r * 0.72f,
        center = center
    )
}

/** Подпись муда по центру НА орбе (заранее измерена), масштаб под глубину. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawOrbLabel(
    layout: TextLayoutResult,
    center: Offset,
    s: Float,
    alpha: Float
) {
    scale(s, s, pivot = center) {
        drawText(
            textLayoutResult = layout,
            color = Color.White.copy(alpha = alpha),
            topLeft = Offset(
                center.x - layout.size.width / 2f,
                center.y - layout.size.height / 2f
            )
        )
    }
}
