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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
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
    val pattern: MoodPattern,
    /** Поисковый запрос для seed-трека станции этого настроения. */
    val query: String
)

val WAVE_MOODS = listOf(
    WaveMood("Progressive house", Color(0xFF3B6FE0), Color(0xFF7B5BFF), MoodPattern.WAVES, "progressive house"),
    WaveMood("Summer running", Color(0xFF2FB24A), Color(0xFFB6E05A), MoodPattern.DIAGONALS, "running workout pop"),
    WaveMood("Calm evening", Color(0xFF12808C), Color(0xFF36C6C0), MoodPattern.CIRCLES, "calm chill evening"),
    WaveMood("Feeling indie", Color(0xFF7A4BE0), Color(0xFFE05BD0), MoodPattern.BLOBS, "indie"),
    WaveMood("On the road", Color(0xFFE07B2F), Color(0xFFF5C24B), MoodPattern.DOTS, "road trip rock"),
    WaveMood("Dancefloor", Color(0xFFE0405F), Color(0xFFFF7AB0), MoodPattern.RINGS, "dance edm hits"),
)

private const val TILE_DP = 168
private const val TAU = (2.0 * PI).toFloat()

// Скорость медленного «дыхания» узора (доля фазы 0..1 в секунду).
private const val PATTERN_SPEED = 0.07f

// ── Отложенный прогрев AGSL-плиток (НЕ деградация) ──
// Первые кадры экрана плитки рисуют ДЕШЁВЫМ статичным градиентом (никаких
// RuntimeShader), чтобы холодный старт не давил GPU. Затем каждая ВИДИМАЯ плитка
// включает свой шейдер ПО ОЧЕРЕДИ — отсчёт ведётся в РЕАЛЬНО отрисованных кадрах
// (withFrameNanos), поэтому тяжёлая линковка AGSL-программы ложится на отдельный
// кадр уже ПОСЛЕ стартового шторма, по одной плитке за раз. Эффект включается и
// работает ПОСТОЯННО (не пропадает, не зависит от FPS-деградации).
private const val TILE_WARMUP_FRAMES = 30  // ~0.5с отрисованных кадров до 1-й плитки
private const val TILE_STAGGER_FRAMES = 6  // +~0.1с (кадров) на каждую следующую

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

// Interleaved Gradient Noise — per-pixel (без ячеек): дизер + мелкое зерно.
float ign(float2 p) {
    return fract(52.9829189 * fract(dot(p, float2(0.06711056, 0.00583715))));
}

half4 main(float2 fragCoord) {
    float2 uv = fragCoord / uResolution;
    float t = uTime * 0.12;                       // медленно, «дышаще»

    // Плавный диагональный перелив ТОЛЬКО на синусах — без value-noise.
    // value-noise при низкой частоте даёт видимую сетку ячеек (блочные пятна),
    // поэтому в цвет его не подмешиваем.
    float g = uv.x * 0.55 + uv.y * 0.45;
    float flow = 0.16 * sin(t + g * 3.2) + 0.09 * sin(t * 0.7 + (uv.x - uv.y) * 4.5);
    float mixf = clamp(g + flow, 0.0, 1.0);
    float3 col = mix(float3(uColorA), float3(uColorB), mixf);

    // мелкое per-pixel зерно (глубина) — без ячеек, не блочит
    col += (ign(fragCoord * 1.3 + 7.0) - 0.5) * 0.02;
    // IGN-дизер ~2.5 LSB — добивает 8-битную ступенчатость градиента
    col += (ign(fragCoord) - 0.5) * (2.5 / 255.0);

    return half4(half3(col), 1.0);
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
    modifier: Modifier = Modifier,
    animate: Boolean = true
) {
    // Единый кадровый клок (секунды). Один на весь ряд — не плодим корутины.
    // Замораживается, когда Home перекрыт оверлеем (см. AuraBackground): меньше
    // нагрузки на RenderThread при переходах.
    val timeSec = produceState(0f, animate) {
        if (!animate) return@produceState
        while (true) {
            withInfiniteAnimationFrameMillis { value = it / 1000f }
        }
    }

    LazyRow(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        itemsIndexed(WAVE_MOODS, key = { _, m -> m.label }) { index, mood ->
            MoodTile(
                mood = mood,
                timeSec = timeSec,
                seed = index * 1.7f,
                index = index,
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
    index: Int,
    onClick: () -> Unit
) {
    // AGSL включаем НЕ на первом кадре, а со СТАГГЕРОМ по индексу в РЕАЛЬНО
    // отрисованных кадрах — чтобы линковка AGSL-программ легла на разные кадры уже
    // после стартового прогрева. Это отложенный старт, а НЕ FPS-деградация: однажды
    // включившись, плитка остаётся с шейдером (эффект не пропадает).
    val canShader = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    // epoch меняется при возврате из фона → сбрасываем арминг и пере-прогреваем,
    // создавая НОВЫЙ RuntimeShader (старый теряет GPU-контекст после onStop).
    val epoch = com.liquidmusicglass.ui.EffectsLifecycle.epoch
    var shaderArmed by remember { mutableStateOf(false) }
    LaunchedEffect(index, epoch) {
        shaderArmed = false
        if (!canShader) return@LaunchedEffect
        // Ждём N отрисованных кадров (+ стаггер по индексу), затем включаем шейдер.
        // LazyRow держит в композиции только видимые плитки → таймеры идут лишь для них.
        repeat(TILE_WARMUP_FRAMES + index * TILE_STAGGER_FRAMES) { withFrameNanos { } }
        shaderArmed = true
    }

    val shader = remember(mood, shaderArmed, epoch) {
        if (shaderArmed && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            RuntimeShader(TILE_AGSL)
        } else null
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
                if (shader != null && shaderBrush != null) {
                    // AGSL активна (плитка прогрелась). Клок читаем ТОЛЬКО здесь —
                    // статичные плитки не инвалидируются каждый кадр.
                    val ts = timeSec.value
                    val patternPhase = ((ts * PATTERN_SPEED) + seed * 0.13f) % 1f
                    shader.setFloatUniform("uResolution", size.width, size.height)
                    shader.setFloatUniform("uTime", ts + seed)       // сдвиг фазы у каждой плитки
                    shader.setFloatUniform("uColorA", mood.colorA.red, mood.colorA.green, mood.colorA.blue)
                    shader.setFloatUniform("uColorB", mood.colorB.red, mood.colorB.green, mood.colorB.blue)
                    drawRect(shaderBrush)

                    // ── узор (path-тяжёлый) — рисуем только когда шейдер активен ──
                    clipRect {
                        val a = mood.colorB
                        when (mood.pattern) {
                            MoodPattern.WAVES -> drawWaves(patternPhase, size.width, size.height, a)
                            MoodPattern.DIAGONALS -> drawDiagonals(patternPhase, size.width, size.height, a)
                            MoodPattern.CIRCLES -> drawCircles(patternPhase, size.width, size.height, a)
                            MoodPattern.BLOBS -> drawBlobs(patternPhase, size.width, size.height, a)
                            MoodPattern.DOTS -> drawDots(patternPhase, size.width, size.height, a)
                            MoodPattern.RINGS -> drawRings(patternPhase, size.width, size.height, a)
                        }
                    }
                } else {
                    // Прогрев (шейдер ещё не включён) / <API33 — ДЕШЁВЫЙ статичный
                    // градиент. Клок НЕ читаем → плитка не перерисовывается каждый кадр.
                    drawRect(
                        Brush.linearGradient(
                            colors = listOf(mood.colorA, mood.colorB),
                            start = Offset.Zero,
                            end = Offset(size.width, size.height)
                        )
                    )
                }

                // ── мягкий scrim к низу для читаемости подписи (всегда) ──
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

/** Мягкое светящееся пятно (радиальный градиент → прозрачность). */
private fun DrawScope.softGlow(center: Offset, radius: Float, color: Color, alpha: Float) {
    if (radius <= 0f) return
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(color.copy(alpha = alpha), Color.Transparent),
            center = center,
            radius = radius
        ),
        radius = radius,
        center = center
    )
}

// ── WAVES: слоёные текучие волны со свечением (glow-обводка + яркое ядро) ──
private fun DrawScope.drawWaves(p: Float, w: Float, h: Float, accent: Color) {
    val rows = 6
    for (k in 0 until rows) {
        val yBase = h * (0.10f + k * 0.155f)
        val amp = h * (0.045f + 0.02f * sin(k * 1.3f))
        val freq = 1.3f + k * 0.18f
        val phaseShift = p * TAU * (if (k % 2 == 0) 1f else -1f) + k * 0.7f
        val path = Path()
        path.moveTo(0f, yBase)
        var x = 0f
        while (x <= w) {
            val y = yBase + amp * sin(phaseShift + x / w * TAU * freq)
            path.lineTo(x, y)
            x += w / 48f
        }
        val col = if (k % 2 == 0) Color.White else accent
        drawPath(path, col.copy(alpha = 0.05f), style = Stroke(width = 12f))  // glow
        drawPath(path, col.copy(alpha = 0.20f), style = Stroke(width = 2.2f)) // ядро
    }
}

// ── DIAGONALS: мягкие диагональные лучи света (широкие размытые полосы, не «штрихкод») ──
private fun DrawScope.drawDiagonals(p: Float, w: Float, h: Float, accent: Color) {
    rotate(degrees = -32f, pivot = Offset(w / 2f, h / 2f)) {
        val count = 5
        val band = w * 0.42f
        val travel = w * 2.4f
        for (i in -1..count) {
            val cx = ((i + p) / count) * travel - w * 0.7f
            val col = if (i % 2 == 0) Color.White else accent
            drawRect(
                brush = Brush.horizontalGradient(
                    0f to Color.Transparent,
                    0.5f to col.copy(alpha = 0.08f),
                    1f to Color.Transparent,
                    startX = cx - band / 2f,
                    endX = cx + band / 2f
                ),
                topLeft = Offset(cx - band / 2f, -h),
                size = Size(band, h * 3f)
            )
        }
    }
}

// ── CIRCLES: рябь по воде — расходящиеся кольца со свечением + мягкое ядро ──
private fun DrawScope.drawCircles(p: Float, w: Float, h: Float, accent: Color) {
    val c = Offset(w * 0.74f, h * 0.26f)
    val maxR = w * 1.15f
    val n = 7
    for (i in 0 until n) {
        val frac = ((i.toFloat() / n) + p) % 1f
        val r = frac * maxR
        val fade = (1f - frac)
        val col = if (i % 2 == 0) Color.White else accent
        drawCircle(col.copy(alpha = fade * 0.16f), radius = r, center = c, style = Stroke(width = 2.5f + fade * 4f))
    }
    softGlow(c, w * 0.30f, Color.White, 0.12f)
}

// ── BLOBS: слоёные дрейфующие световые сгустки + мелкие яркие блики ──
private fun DrawScope.drawBlobs(p: Float, w: Float, h: Float, accent: Color) {
    fun blob(seed: Float, bx: Float, by: Float, rad: Float, color: Color, alpha: Float) {
        val a = (p + seed) * TAU
        val cx = w * bx + w * 0.12f * cos(a)
        val cy = h * by + h * 0.12f * sin(a * 1.1f)
        softGlow(Offset(cx, cy), w * rad, color, alpha)
    }
    blob(0.00f, 0.30f, 0.34f, 0.46f, Color.White, 0.16f)
    blob(0.33f, 0.72f, 0.52f, 0.40f, accent, 0.24f)
    blob(0.66f, 0.46f, 0.80f, 0.34f, Color.White, 0.12f)
    // мелкие яркие блики
    for (i in 0 until 3) {
        val a = (p * (1f + i * 0.3f) + i * 0.4f) * TAU
        val cx = w * (0.25f + 0.25f * i) + w * 0.06f * cos(a)
        val cy = h * (0.3f + 0.18f * i) + h * 0.06f * sin(a)
        softGlow(Offset(cx, cy), w * 0.07f, Color.White, 0.5f)
    }
}

// ── DOTS: боке — мягкие крупные пятна позади + сетка мерцающих точек ──
private fun DrawScope.drawDots(p: Float, w: Float, h: Float, accent: Color) {
    // крупное мягкое боке позади
    for (i in 0 until 4) {
        val a = (p + i * 0.27f) * TAU
        val cx = w * (0.2f + 0.2f * i) + w * 0.08f * cos(a)
        val cy = h * (0.25f + 0.18f * i) + h * 0.08f * sin(a * 1.2f)
        softGlow(Offset(cx, cy), w * 0.16f, if (i % 2 == 0) Color.White else accent, 0.10f)
    }
    // сетка точек с мерцанием (простые круги — дёшево, без аллокаций на кадр)
    val cols = 6
    val rows = 6
    val r = w * 0.022f
    for (gy in 0 until rows) {
        for (gx in 0 until cols) {
            val x = w * (gx + 0.5f) / cols
            val y = h * (gy + 0.5f) / rows
            val tw = sin(p * TAU + (gx + gy) * 0.55f) * 0.5f + 0.5f
            drawCircle(Color.White.copy(alpha = 0.10f + tw * 0.22f), radius = r * (0.7f + tw * 0.6f), center = Offset(x, y))
        }
    }
}

// ── RINGS: пульсирующие звуковые кольца из двух центров, со свечением ──
private fun DrawScope.drawRings(p: Float, w: Float, h: Float, accent: Color) {
    val centers = listOf(
        Offset(w * 0.28f, h * 0.30f) to Color.White,
        Offset(w * 0.76f, h * 0.64f) to accent
    )
    for ((idx, pair) in centers.withIndex()) {
        val (c, col) = pair
        val n = 5
        for (i in 0 until n) {
            val frac = ((i.toFloat() / n) + p + idx * 0.4f) % 1f
            val r = frac * w * 0.72f
            val fade = (1f - frac)
            drawCircle(col.copy(alpha = fade * 0.18f), radius = r, center = c, style = Stroke(width = 2.5f + fade * 4.5f))
        }
        softGlow(c, w * 0.14f, col, 0.18f)
    }
}
