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
import com.liquidmusicglass.ui.glass.AlbumColors

/**
 * «Liquid Aurora» — собственный анимированный фон-аура для FullPlayer.
 *
 * Техника (общая, не привязана ни к какой конкретной чужой реализации): фрагментный шейдер строит
 * ПЛАВНЫЕ ТЕКУЧИЕ ВУАЛИ света через **domain-warped fbm** (value-noise) и красит их цветами палитры
 * обложки. Никаких концентрических колец / полярных «sparks» — другой визуальный почерк: жидкие
 * перетекающие ленты (в духе LiquidMusicGlass).
 *
 * - Android 13+ (API 33): настоящий AGSL [RuntimeShader], анимируется по времени, цвета — из [AlbumColors].
 * - Ниже 33: мягкий дрейфующий градиент-фолбэк на тех же цветах.
 *
 * Аудио-реактивность намеренно не делаю в первой версии (чисто визуал + плавность); можно добавить
 * uniform с уровнем громкости отдельным шагом.
 */
private const val AURA_AGSL = """
uniform float2 uResolution;
uniform float  uTime;
uniform half3  uColorBg;
uniform half3  uColorA;
uniform half3  uColorB;
uniform half3  uColorC;

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
    for (int i = 0; i < 5; i++) {
        v += amp * vnoise(p);
        p = p * 2.02 + 17.3;
        amp *= 0.5;
    }
    return v;
}

half4 main(float2 fragCoord) {
    float2 uv = fragCoord / uResolution;
    float2 p = uv * 2.3;
    p.x *= uResolution.x / uResolution.y;       // коррекция аспекта
    float t = uTime * 0.05;

    // двухступенчатый domain-warp: вуали текут, не повторяясь
    float2 q = float2(fbm(p + float2(0.0, t)),
                      fbm(p + float2(5.2, -t)));
    float2 r = float2(fbm(p + 3.0 * q + float2(1.7, 9.2) + t * 0.6),
                      fbm(p + 3.0 * q + float2(8.3, 2.8) - t * 0.6));
    float f = fbm(p + 2.4 * r);

    // перетекающий цвет из палитры
    half3 col = uColorBg;
    col = mix(col, uColorA, clamp(f * 1.7 - 0.1, 0.0, 1.0));
    col = mix(col, uColorB, clamp(length(r) * 0.65, 0.0, 1.0));
    col = mix(col, uColorC, clamp(q.x * q.x * 1.5, 0.0, 1.0));

    // мягкий подъём яркости к верхней зоне, затемнение к низу (читаемость текста)
    float topGlow = smoothstep(0.95, 0.18, uv.y);
    col *= mix(0.55, 1.18, topGlow);
    col = mix(col, uColorBg, smoothstep(0.52, 1.0, uv.y) * 0.85);

    return half4(col, 1.0);
}
"""

@Composable
fun AuraBackground(
    albumColors: AlbumColors,
    modifier: Modifier = Modifier,
) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        AuraShaderBackground(albumColors, modifier)
    } else {
        AuraGradientFallback(albumColors, modifier)
    }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun AuraShaderBackground(albumColors: AlbumColors, modifier: Modifier) {
    val shader = remember { RuntimeShader(AURA_AGSL) }
    val brush = remember { ShaderBrush(shader) }

    // плавная смена цветов при переключении трека
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
                shader.setFloatUniform("uColorBg", bg.red, bg.green, bg.blue)
                shader.setFloatUniform("uColorA", a.red, a.green, a.blue)
                shader.setFloatUniform("uColorB", b.red, b.green, b.blue)
                shader.setFloatUniform("uColorC", c.red, c.green, c.blue)
                drawRect(brush)
            },
    )
}

/** Фолбэк для Android < 13: мягкий дрейфующий градиент на тех же цветах (без шейдера). */
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
                fun pt(seed: Float, sx: Float, sy: Float) = androidx.compose.ui.geometry.Offset(
                    w * (0.5f + 0.35f * kotlin.math.cos(tau * (phase + seed)) * sx),
                    h * (0.42f + 0.30f * kotlin.math.sin(tau * (phase + seed)) * sy),
                )
                drawRect(
                    Brush.radialGradient(
                        listOf(a.copy(alpha = 0.55f), Color.Transparent),
                        center = pt(0.0f, 1f, 1f), radius = w * 0.9f,
                    )
                )
                drawRect(
                    Brush.radialGradient(
                        listOf(b.copy(alpha = 0.45f), Color.Transparent),
                        center = pt(0.33f, -1f, 1f), radius = w * 0.8f,
                    )
                )
                drawRect(
                    Brush.radialGradient(
                        listOf(c.copy(alpha = 0.40f), Color.Transparent),
                        center = pt(0.66f, 1f, -1f), radius = w * 0.7f,
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
