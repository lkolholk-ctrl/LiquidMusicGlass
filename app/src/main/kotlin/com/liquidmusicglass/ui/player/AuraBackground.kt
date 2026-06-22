package com.liquidmusicglass.ui.player

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.withInfiniteAnimationFrameMillis
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import com.liquidmusicglass.engine.AudioReactor
import com.liquidmusicglass.ui.glass.AlbumColors

/**
 * «Liquid Aurora» — собственный анимированный фон-аура. Текучие мультицветные вуали (туманность)
 * через **повёрнутый fbm + двойной domain-warp**, цвета берутся ИЗ ПАЛИТРЫ ОБЛОЖКИ ([AlbumColors]) и
 * плавно меняются при смене трека.
 *
 * Без концентрических колец/полярных «sparks» — другой почерк. Реализация полностью своя.
 *
 * Анти-баг: между октавами fbm крутится (mat2) — нет осевых «древесных» полос; цвета мапятся через
 * `smoothstep` (а не жёсткий `clamp`) — нет контур-бандинга.
 *
 * - Android 13+ (API 33): настоящий AGSL [RuntimeShader].
 * - Ниже — мягкий дрейфующий радиальный-градиент фолбэк на тех же цветах.
 *
 * Компонент НЕ подключён в FullPlayer (там свой palette-фон). Подключать там, где нужен фон-аура.
 */
private const val AURA_AGSL = """
uniform float2 uResolution;
uniform float  uTime;
uniform float  uIntensity;
uniform half3  uColorBg;
uniform half3  uColorA;
uniform half3  uColorB;
uniform half3  uColorC;
uniform float  uLow;
uniform float  uMid;
uniform float  uHigh;

float hash2(float2 p) {
    float px = fract(p.x * 0.3183099 + 0.1) * 17.0;
    float py = fract(p.y * 0.3183099 + 0.1) * 17.0;
    return fract(px * py * (px + py));
}

float vnoise(float2 p) {
    float2 i = floor(p);
    float2 f = fract(p);
    float2 u = f * f * (3.0 - 2.0 * f);
    float a = hash2(i);
    float b = hash2(i + float2(1.0, 0.0));
    float c = hash2(i + float2(0.0, 1.0));
    float d = hash2(i + float2(1.0, 1.0));
    return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
}

// fbm с поворотом координат между октавами — убивает осевые полосы
float fbm(float2 p) {
    float v = 0.0;
    float amp = 0.5;
    for (int i = 0; i < 6; i++) {
        v += amp * vnoise(p);
        p = float2(0.8 * p.x + 0.6 * p.y, -0.6 * p.x + 0.8 * p.y) * 2.0;
        amp *= 0.5;
    }
    return v;
}

half4 main(float2 fragCoord) {
    float2 uv = fragCoord / uResolution;
    float asp = uResolution.x / uResolution.y;
    float2 p = float2(uv.x * asp, uv.y) * 3.4;
    float t = uTime * 0.04;

    // три декоррелированных потока (двойной domain-warp) → мультицвет
    float w1 = fbm(p + float2(0.0, t));
    float w2 = fbm(float2(p.x * 1.3 + 5.2 + 0.8 * w1, p.y * 1.3 - t + 0.8 * w1));
    float ff = fbm(float2(p.x + 1.4 * w1 + 0.3 * t, p.y + 1.4 * w2));

    half3 col = uColorBg;
    col = mix(col, uColorA, smoothstep(0.40, 0.63, ff) * 0.92 * uIntensity);
    col = mix(col, uColorB, smoothstep(0.40, 0.63, w1) * 0.78 * uIntensity);
    col = mix(col, uColorC, clamp(smoothstep(0.42, 0.66, w2) * 0.70 * uIntensity + uHigh * 0.25, 0.0, 1.0));

    // лёгкая вертикальная форма: чуть ярче к верху, мягче к фону у низа (читаемость)
    col *= mix(0.82, 1.05, 1.0 - smoothstep(0.1, 1.0, uv.y));
    // бас подсвечивает ауру — «дыхание» под музыку
    col *= (1.0 + uLow * 0.30 + uMid * 0.10);
    col = mix(col, uColorBg, smoothstep(0.62, 1.0, uv.y) * 0.62);

    return half4(col, 1.0);
}
"""

@Composable
fun AuraBackground(
    albumColors: AlbumColors,
    modifier: Modifier = Modifier,
    intensity: Float = 0.78f,
) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        AuraShaderBackground(albumColors, intensity, modifier)
    } else {
        AuraGradientFallback(albumColors, modifier)
    }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun AuraShaderBackground(albumColors: AlbumColors, intensity: Float, modifier: Modifier) {
    val shader = remember { RuntimeShader(AURA_AGSL) }
    val brush = remember { ShaderBrush(shader) }

    // цвета вуалей — из палитры обложки, плавно меняются при смене трека
    val bg by animateColorAsState(albumColors.darkMuted, tween(900), label = "auraBg")
    val a by animateColorAsState(albumColors.vibrant, tween(900), label = "auraA")
    val b by animateColorAsState(albumColors.dominant, tween(900), label = "auraB")
    val c by animateColorAsState(albumColors.lightVibrant, tween(900), label = "auraC")

    val timeSec by produceState(0f) {
        while (true) {
            withInfiniteAnimationFrameMillis { value = it / 1000f }
        }
    }

    Box(
        modifier
            .fillMaxSize()
            .background(Color.Black)
            .drawBehind {
                shader.setFloatUniform("uResolution", size.width, size.height)
                shader.setFloatUniform("uTime", timeSec)
                shader.setFloatUniform("uIntensity", intensity)
                shader.setFloatUniform("uColorBg", bg.red, bg.green, bg.blue)
                shader.setFloatUniform("uColorA", a.red, a.green, a.blue)
                shader.setFloatUniform("uColorB", b.red, b.green, b.blue)
                shader.setFloatUniform("uColorC", c.red, c.green, c.blue)
                shader.setFloatUniform("uLow", AudioReactor.low)
                shader.setFloatUniform("uMid", AudioReactor.mid)
                shader.setFloatUniform("uHigh", AudioReactor.high)
                drawRect(brush)
            },
    )
}

/** Фолбэк для Android < 13: мягкий дрейфующий радиальный градиент на тех же цветах (без шейдера). */
@Composable
private fun AuraGradientFallback(albumColors: AlbumColors, modifier: Modifier) {
    val bg by animateColorAsState(albumColors.darkMuted, tween(900), label = "fbBg")
    val a by animateColorAsState(albumColors.vibrant, tween(900), label = "fbA")
    val b by animateColorAsState(albumColors.dominant, tween(900), label = "fbB")
    val c by animateColorAsState(albumColors.lightVibrant, tween(900), label = "fbC")

    val phase by produceState(0f) {
        while (true) {
            withInfiniteAnimationFrameMillis { value = (it % 16000L) / 16000f }
        }
    }

    Box(
        modifier
            .fillMaxSize()
            .background(bg)
            .drawBehind {
                val w = size.width
                val h = size.height
                val tau = (2.0 * Math.PI).toFloat()
                // Бас слегка раздувает и подсвечивает пятна
                val low = AudioReactor.low.coerceIn(0f, 1f)
                val rBoost = 1f + low * 0.22f
                val aBoost = 1f + low * 0.45f
                fun pt(seed: Float, sx: Float, sy: Float) = androidx.compose.ui.geometry.Offset(
                    w * (0.5f + 0.35f * kotlin.math.cos(tau * (phase + seed)) * sx),
                    h * (0.42f + 0.30f * kotlin.math.sin(tau * (phase + seed)) * sy),
                )
                drawRect(
                    Brush.radialGradient(
                        listOf(a.copy(alpha = (0.55f * aBoost).coerceAtMost(1f)), Color.Transparent),
                        center = pt(0.0f, 1f, 1f), radius = w * 0.9f * rBoost,
                    )
                )
                drawRect(
                    Brush.radialGradient(
                        listOf(b.copy(alpha = (0.45f * aBoost).coerceAtMost(1f)), Color.Transparent),
                        center = pt(0.33f, -1f, 1f), radius = w * 0.8f * rBoost,
                    )
                )
                drawRect(
                    Brush.radialGradient(
                        listOf(c.copy(alpha = (0.40f * aBoost).coerceAtMost(1f)), Color.Transparent),
                        center = pt(0.66f, 1f, -1f), radius = w * 0.7f * rBoost,
                    )
                )
                drawRect(
                    Brush.verticalGradient(
                        0.55f to Color.Transparent,
                        1.0f to bg.copy(alpha = 0.85f),
                    )
                )
            },
    )
}
