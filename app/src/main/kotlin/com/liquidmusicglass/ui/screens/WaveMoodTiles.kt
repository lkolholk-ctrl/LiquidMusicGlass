package com.liquidmusicglass.ui.screens

import android.graphics.RuntimeShader
import android.os.Build
import androidx.compose.animation.core.withInfiniteAnimationFrameMillis
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.liquidmusicglass.ui.theme.AppFontFamily
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Узор плитки — у каждого муда свой. */
enum class MoodPattern { WAVES, DIAGONALS, CIRCLES, BLOBS, DOTS, RINGS }

/**
 * Мудовая плитка «Моей волны»: подпись, пара цветов для перелива, свой узор.
 */
data class WaveMood(
    val label: String,
    val colorA: Color,
    val colorB: Color,
    val pattern: MoodPattern
)

val WAVE_MOODS = listOf(
    WaveMood("Progressive house", Color(0xFF3B6FE0), Color(0xFF7B5BFF), MoodPattern.WAVES),
    WaveMood("Summer running", Color(0xFF2FB24A), Color(0xFFB6E05A), MoodPattern.DIAGONALS),
    WaveMood("Calm evening", Color(0xFF12808C), Color(0xFF36C6C0), MoodPattern.CIRCLES),
    WaveMood("Feeling indie", Color(0xFF7A4BE0), Color(0xFFE05BD0), MoodPattern.BLOBS),
    WaveMood("On the road", Color(0xFFE07B2F), Color(0xFFF5C24B), MoodPattern.DOTS),
    WaveMood("Dancefloor", Color(0xFFE0405F), Color(0xFFFF7AB0), MoodPattern.RINGS),
)

private const val TILE_DP = 168
private const val TAU = (2.0 * PI).toFloat()

// Скорость медленного «дыхания» узора (доля фазы 0..1 в секунду).
private const val PATTERN_SPEED = 0.07f

/**
 * AGSL-шейдер плитки: медленный перелив между двумя цветами муда (домен-варп
 * шумом) + лёгкий шумовой слой для ГЛУБИНЫ/бликов. Всё на GPU — недорого даже
 * для нескольких плиток в ряду. API 33+; ниже — CPU-градиент-фолбэк.
 */
private const val TILE_AGSL = """
uniform float2 uResolution;
uniform float  uTime;
uniform half3  uColorA;
uniform half3  uColorB;

float hash(float2 p) {
    p = fract(p * float2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}
float vnoise(float2 p) {
    float2 i = floor(p);
    float2 f = fract(p);
    float2 u = f * f * (3.0 - 2.0 * f);
    float a = hash(i);
    float b = hash(i + float2(1.0, 0.0));
    float c = hash(i + float2(0.0, 1.0));
    float d = hash(i + float2(1.0, 1.0));
    return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
}
float fbm(float2 p) {
    float v = 0.0;
    float amp = 0.5;
    for (int i = 0; i < 3; i++) {
        v += amp * vnoise(p);
        // поворот координат между октавами — убивает осевые «полосы»
        p = float2(0.8 * p.x + 0.6 * p.y, -0.6 * p.x + 0.8 * p.y) * 2.0;
        amp *= 0.5;
    }
    return v;
}

float hash1(float2 p) {
    return fract(sin(dot(p, float2(12.9898, 78.233))) * 43758.5453);
}

half4 main(float2 fragCoord) {
    float2 uv = fragCoord / uResolution;
    float t = uTime * 0.12;                       // медленно, «дышаще»

    // перелив: диагональная база + мягкий шумовой домен-варп + лёгкая синусоида
    float n = fbm(uv * 2.0 + float2(t * 0.35, -t * 0.22));
    float g = uv.x * 0.55 + uv.y * 0.45;
    float mixf = clamp(g + (n - 0.5) * 0.55 + 0.12 * sin(t + g * 3.0), 0.0, 1.0);
    half3 col = mix(uColorA, uColorB, mixf);

    // второй слой — тонкий шум для глубины/бликов (не перегружая)
    float hi = fbm(uv * 4.5 + t * 0.15);
    col += (hi - 0.5) * 0.06;

    // дизеринг: ломаем 8-битное квантование, чтобы не было ступенчатых полос
    col += (hash1(fragCoord) - 0.5) * (1.0 / 255.0);

    return half4(col, 1.0);
}
"""

/**
 * Горизонтальный ряд крупных мудовых плиток. Перелив+глубина — шейдером (GPU),
 * свой узор — сверху. Анимируются только ВИДИМЫЕ плитки: LazyRow уничтожает
 * элементы за вьюпортом, поэтому их drawBehind не вызывается; время — один
 * общий кадровый клок.
 */
@Composable
fun WaveMoodTiles(
    onSelect: (WaveMood) -> Unit,
    modifier: Modifier = Modifier
) {
    // Единый кадровый клок (секунды). Один на весь ряд — не плодим корутины.
    val timeSec = produceState(0f) {
        while (true) {
            withInfiniteAnimationFrameMillis { value = it / 1000f }
        }
    }

    LazyRow(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        items(WAVE_MOODS, key = { it.label }) { mood ->
            MoodTile(
                mood = mood,
                timeSec = timeSec,
                seed = WAVE_MOODS.indexOf(mood) * 1.7f,
                onClick = { onSelect(mood) }
            )
        }
    }
}

@Composable
private fun MoodTile(
    mood: WaveMood,
    timeSec: State<Float>,
    seed: Float,
    onClick: () -> Unit
) {
    // Шейдер живёт только на API 33+. Создаётся один раз на плитку.
    val shader = remember(mood) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) RuntimeShader(TILE_AGSL) else null
    }
    val shaderBrush = remember(shader) { shader?.let { ShaderBrush(it) } }

    Box(
        modifier = Modifier
            .size(TILE_DP.dp)
            .clip(RoundedCornerShape(44.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .drawBehind {
                val ts = timeSec.value                       // читаем здесь → редроу только видимых
                val patternPhase = ((ts * PATTERN_SPEED) + seed * 0.13f) % 1f

                if (shader != null && shaderBrush != null) {
                    shader.setFloatUniform("uResolution", size.width, size.height)
                    shader.setFloatUniform("uTime", ts + seed)       // сдвиг фазы у каждой плитки
                    shader.setFloatUniform("uColorA", mood.colorA.red, mood.colorA.green, mood.colorA.blue)
                    shader.setFloatUniform("uColorB", mood.colorB.red, mood.colorB.green, mood.colorB.blue)
                    drawRect(shaderBrush)
                } else {
                    // CPU-фолбэк (<API33): медленно сдвигаемый диагональный градиент
                    val shift = sin(patternPhase * TAU) * 0.5f
                    drawRect(
                        Brush.linearGradient(
                            colors = listOf(mood.colorA, mood.colorB, mood.colorA),
                            start = Offset(size.width * shift, 0f),
                            end = Offset(size.width * (shift + 1f), size.height)
                        )
                    )
                }

                // ── узор (свой у каждого муда) ──
                clipRect {
                    when (mood.pattern) {
                        MoodPattern.WAVES -> drawWaves(patternPhase, size.width, size.height)
                        MoodPattern.DIAGONALS -> drawDiagonals(patternPhase, size.width, size.height)
                        MoodPattern.CIRCLES -> drawCircles(patternPhase, size.width, size.height)
                        MoodPattern.BLOBS -> drawBlobs(patternPhase, size.width, size.height, mood.colorB)
                        MoodPattern.DOTS -> drawDots(patternPhase, size.width, size.height)
                        MoodPattern.RINGS -> drawRings(patternPhase, size.width, size.height)
                    }
                }

                // ── мягкий scrim к низу для читаемости подписи ──
                drawRect(
                    Brush.verticalGradient(
                        0.45f to Color.Transparent,
                        1.0f to Color.Black.copy(alpha = 0.35f)
                    )
                )
            }
            .padding(18.dp)
    ) {
        Text(
            text = mood.label,
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.Black,
            fontFamily = AppFontFamily,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.BottomStart)
        )
    }
}

private val LINE = Color.White.copy(alpha = 0.16f)
private val LINE2 = Color.White.copy(alpha = 0.10f)

private fun DrawScope.drawWaves(p: Float, w: Float, h: Float) {
    val amp = h * 0.07f
    for (k in 0..3) {
        val path = Path()
        val yBase = h * (0.25f + k * 0.2f)
        val phaseShift = p * TAU + k * 0.8f
        path.moveTo(0f, yBase)
        var x = 0f
        while (x <= w) {
            val y = yBase + amp * sin(phaseShift + x / w * TAU * 1.6f)
            path.lineTo(x, y)
            x += w / 24f
        }
        drawPath(path, if (k % 2 == 0) LINE else LINE2, style = Stroke(width = 3f))
    }
}

private fun DrawScope.drawDiagonals(p: Float, w: Float, h: Float) {
    val n = 9
    val span = (w + h)
    val gap = span / n
    val off = (p * gap)
    for (i in -1..n + 1) {
        val s = i * gap + off
        drawLine(
            if (i % 2 == 0) LINE else LINE2,
            start = Offset(s, 0f),
            end = Offset(s - h, h),
            strokeWidth = 6f
        )
    }
}

private fun DrawScope.drawCircles(p: Float, w: Float, h: Float) {
    val c = Offset(w * 0.72f, h * 0.30f)
    val maxR = w * 0.95f
    val n = 5
    for (i in 0 until n) {
        val frac = ((i.toFloat() / n) + p) % 1f
        val r = frac * maxR
        val alpha = (1f - frac) * 0.22f
        drawCircle(Color.White.copy(alpha = alpha), radius = r, center = c, style = Stroke(width = 4f))
    }
}

private fun DrawScope.drawBlobs(p: Float, w: Float, h: Float, accent: Color) {
    fun blob(seed: Float, baseX: Float, baseY: Float, rad: Float, color: Color) {
        val a = (p + seed) * TAU
        val cx = w * baseX + w * 0.12f * cos(a)
        val cy = h * baseY + h * 0.12f * sin(a * 1.1f)
        drawCircle(
            Brush.radialGradient(
                colors = listOf(color.copy(alpha = 0.35f), Color.Transparent),
                center = Offset(cx, cy),
                radius = w * rad
            ),
            radius = w * rad,
            center = Offset(cx, cy)
        )
    }
    blob(0.0f, 0.30f, 0.35f, 0.42f, Color.White)
    blob(0.5f, 0.70f, 0.55f, 0.38f, accent)
}

private fun DrawScope.drawDots(p: Float, w: Float, h: Float) {
    val cols = 5
    val rows = 5
    val r = w * 0.022f
    for (gy in 0 until rows) {
        for (gx in 0 until cols) {
            val x = w * (gx + 0.5f) / cols
            val y = h * (gy + 0.5f) / rows
            val tw = sin(p * TAU + (gx + gy) * 0.6f) * 0.5f + 0.5f
            drawCircle(Color.White.copy(alpha = 0.10f + tw * 0.22f), radius = r * (0.7f + tw * 0.6f), center = Offset(x, y))
        }
    }
}

private fun DrawScope.drawRings(p: Float, w: Float, h: Float) {
    val centers = listOf(
        Offset(w * 0.30f, h * 0.32f),
        Offset(w * 0.74f, h * 0.62f)
    )
    for ((idx, c) in centers.withIndex()) {
        val n = 4
        for (i in 0 until n) {
            val frac = ((i.toFloat() / n) + p + idx * 0.4f) % 1f
            val r = frac * w * 0.6f
            drawCircle(Color.White.copy(alpha = (1f - frac) * 0.20f), radius = r, center = c, style = Stroke(width = 5f))
        }
    }
}
