package com.liquidmusicglass.ui.player

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.withInfiniteAnimationFrameMillis
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.graphicsLayer
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
// Число октав fbm подставляется при создании шейдера: 6 на обычных устройствах,
// 4 на lite (DeviceTier). [lighting] — объёмный свет (эмбосс по производной
// поля, +1 fbm-выборка): даёт дыму 3D-клубы; на lite выключен (дорого).
private fun auraAgsl(octaves: Int, lighting: Boolean): String {
    val lightBlock = if (lighting) """
    // Объёмный свет: производная поля по фикс. направлению света (эмбосс) —
    // наветренная сторона клуба светлее, подветренная в тени. Это и делает
    // дым «настоящим»: плоский туман превращается в объёмные клубы.
    float ffL = fbm(pw + float2(-0.14, -0.20));
    float shade = clamp(0.55 + (ff - ffL) * 3.0, 0.35, 1.55);"""
    else """
    float shade = 1.0;"""
    return """
uniform float2 uResolution;
uniform float  uTime;
uniform float  uIntensity;
uniform half3  uColorBg;
uniform half3  uColorA;
uniform half3  uColorB;
uniform half3  uColorC;
uniform float  uBass;   // сглаженная огибающая баса 0..1 (без вспышек)
uniform float  uSmokeSaturation;
uniform float  uSmokeContrast;

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
    for (int i = 0; i < $octaves; i++) {
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
    // ГЛАВНЫЙ эффект — «вдох»: на басу дым пухнет (масштаб +~8%), на тихом
    // опадает. Плавно (uBass — сглаженная огибающая с медленным спадом).
    p *= (1.0 - uBass * 0.08);
    // uTime — это УЖЕ проинтегрированная на CPU фаза (база + лёгкий басовый
    // прирост скорости), поэтому здесь просто линейно, без скачков фазы.
    float t = uTime * 0.065;

    // Реализм: дым медленно ВСПЛЫВАЕТ (тёплый воздух поднимается) — мягкий
    // вертикальный дрейф поверх вихрей. Скорость крошечная, полос не даёт.
    p += float2(0.0, t * 0.30);

    // Ограниченное вихревое движение вместо линейного сдвига — дым активно
    // «бурлит» на месте (живой), без прямых полос, ползущих с краёв.
    float2 d1 = float2(sin(t), cos(t * 0.8)) * 1.0;
    float2 d2 = float2(cos(t * 0.9), sin(t * 1.1)) * 1.0;
    float2 d3 = float2(sin(t * 0.7 + 1.3), cos(t * 1.2 + 0.5)) * 0.9;

    // три декоррелированных потока (двойной domain-warp) → мультицвет
    float w1 = fbm(p + d1);
    float w2 = fbm(float2(p.x * 1.3 + 5.2 + 0.8 * w1, p.y * 1.3 + 0.8 * w1) + d2);
    float2 pw = float2(p.x + 1.4 * w1, p.y + 1.4 * w2) + d3;
    float ff = fbm(pw);

    // «Клубы»: billow из УЖЕ посчитанного w2 (бесплатно) — плотность вуалей
    // модулируется пухлыми сгустками, края становятся рваными, как у дыма.
    float puff = 1.0 - abs(2.0 * w2 - 1.0);

    float sA = smoothstep(0.40, 0.63, ff) * (0.72 + 0.55 * puff);
    float sB = smoothstep(0.40, 0.63, w1) * (0.80 + 0.40 * puff);
    float sC = smoothstep(0.42, 0.66, w2);
$lightBlock

    half3 col = uColorBg;
    col = mix(col, uColorA, clamp(sA * 0.92 * uIntensity, 0.0, 1.0));
    col = mix(col, uColorB, clamp(sB * 0.78 * uIntensity, 0.0, 1.0));
    col = mix(col, uColorC, clamp(sC * 0.70 * uIntensity, 0.0, 1.0));

    // Усиление только цветного дыма: база остаётся как есть, меняется
    // насыщенность/контраст вуалей относительно uColorBg. Свет (shade)
    // применяется тоже только к дыму — фон не мигает.
    half3 smoke = col - uColorBg;
    half smokeLuma = dot(smoke, half3(0.2126, 0.7152, 0.0722));
    smoke = mix(half3(smokeLuma), smoke, uSmokeSaturation) * uSmokeContrast;
    smoke *= half(shade);
    col = clamp(uColorBg + smoke, 0.0, 1.0);

    // Поглощение: самые плотные ядра чуть темнее — глубина/объём, как у
    // настоящего густого дыма (свет сквозь него не проходит).
    float dens = clamp(max(sA, max(sB, sC)), 0.0, 1.0);
    col = mix(col, col * 0.90, dens * 0.30);

    // лёгкая вертикальная форма: чуть ярче к верху, мягче к фону у низа (читаемость)
    col *= mix(0.82, 1.05, 1.0 - smoothstep(0.1, 1.0, uv.y));
    // лёгкая поддержка яркостью (вторично, вдох — главный): узко +~10%,
    // через сглаженную огибающую — без вспышек (безопасно для фотосенситивности)
    col *= (1.0 + uBass * 0.10);
    col = mix(col, uColorBg, smoothstep(0.62, 1.0, uv.y) * 0.62);

    return half4(col, 1.0);
}
"""
}

// ── Envelope follower баса (анти-вспышки + анти-дёрганье) ──
// Раздельные атака/спад + кап на шаг за кадр, плюс ВТОРАЯ ступень сглаживания
// (каскад) — дым плавно КЛУБИТСЯ под бас, не дёргается покадрово.
private const val BASS_ATTACK = 0.08f      // подъём — помягче, чтоб не «снэпало» на транзиентах
private const val BASS_RELEASE = 0.035f    // спад — медленный, дым плавно успокаивается
private const val BASS_MAX_STEP = 0.022f   // кап: макс. шаг параметра за кадр
private const val BASS_STAGE2 = 0.15f      // вторая ступень low-pass поверх огибающей

/** Один шаг огибающей с раздельной атакой/спадом и капом скорости изменения. */
private fun advanceBass(current: Float, target: Float): Float {
    val rate = if (target > current) BASS_ATTACK else BASS_RELEASE
    val step = ((target - current) * rate).coerceIn(-BASS_MAX_STEP, BASS_MAX_STEP)
    return (current + step).coerceIn(0f, 1f)
}

/**
 * Сглаженный уровень баса 0..1 как [State]. Сырой [AudioReactor.low] прогоняется
 * через КАСКАД: огибающая с инерцией → вторая ступень low-pass. В шейдер уходит
 * одно очень плавное число (uBass) — клубление без рывков.
 *
 * [halfRate] (lite-устройства): каскад считается каждый кадр (постоянная времени
 * сохраняется), но ПУБЛИКУЕТСЯ значение через кадр — дым перерисовывается на
 * ~30 Гц вместо 60, GPU-нагрузка вдвое ниже, визуально неотличимо.
 */
@Composable
private fun rememberSmoothedBass(halfRate: Boolean = false): State<Float> = produceState(0f, halfRate) {
    var s1 = 0f
    var s2 = 0f
    var skip = false
    while (true) {
        withInfiniteAnimationFrameMillis {
            s1 = advanceBass(s1, AudioReactor.low.coerceIn(0f, 1f))
            s2 += (s1 - s2) * BASS_STAGE2
            if (!halfRate || !skip) value = s2
            skip = !skip
        }
    }
}

// Тяжёлый AGSL-дым НЕ монтируется на первых кадрах экрана: пока экран не отрисовал
// AURA_WARMUP_FRAMES кадров — рисуем дешёвый статичный градиент, и только потом
// подключаем fbm-шейдер (6 октав). Это снимает компиляцию/линковку fbm-программы с
// критического пути холодного старта (именно одновременная линковка ауры + плиток
// давала ANR на старте My Wave даже на флагмане). Аура и плитки прогреваются в
// РАЗНЫХ кадрах (у плиток свой стаггер), так что линковка идёт по одной программе.
//
// Это ОТЛОЖЕННЫЙ СТАРТ, а НЕ деградация: дым включается через ~полсекунды после
// открытия экрана и работает ПОСТОЯННО (не пропадает, не зависит от FPS).
private const val AURA_WARMUP_FRAMES = 24  // ~0.4с отрисованных кадров

@Composable
fun AuraBackground(
    albumColors: AlbumColors,
    modifier: Modifier = Modifier,
    intensity: Float = 0.78f,
    animate: Boolean = true,
    smokeSaturation: Float = 1.0f,
    smokeContrast: Float = 1.0f,
) {
    val tiramisu = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    // epoch меняется при возврате из фона → сбрасываем дым на статичный и пере-
    // прогреваем. Смена heavyArmed true→false→true пересоздаёт AuraShaderBackground
    // (а с ним — новый RuntimeShader вместо потерявшего GPU-контекст).
    val epoch = com.liquidmusicglass.ui.EffectsLifecycle.epoch
    var heavyArmed by remember { mutableStateOf(false) }
    LaunchedEffect(epoch) {
        heavyArmed = false
        // Ждём N реально отрисованных кадров, затем включаем дым — и оставляем включённым.
        repeat(AURA_WARMUP_FRAMES) { withFrameNanos { } }
        heavyArmed = true
    }

    // Энергосбережение: тяжёлый fbm-дым → лёгкий градиентный фон (не выключаем).
    val powerSave = com.liquidmusicglass.ui.PowerSaveMonitor.active

    when {
        heavyArmed && tiramisu && !powerSave -> {
            // Плавное появление: под шейдером — мгновенная градиентная база (нет
            // чёрной вспышки и резкого щелчка), сам fbm-дым «проявляется» альфой
            // 0→1, как эффекты на мудовых карточках. Базу убираем, когда дым
            // полностью проявился (без лишнего overdraw).
            var shown by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) { shown = true }
            val fade by animateFloatAsState(
                targetValue = if (shown) 1f else 0f,
                animationSpec = tween(550, easing = LinearOutSlowInEasing),
                label = "auraFadeIn"
            )
            Box(modifier) {
                if (fade < 0.999f) AuraStaticBackground(albumColors, Modifier)
                AuraShaderBackground(
                    albumColors, intensity,
                    Modifier.graphicsLayer { alpha = fade },
                    animate,
                    smokeSaturation,
                    smokeContrast
                )
            }
        }
        heavyArmed -> AuraGradientFallback(albumColors, modifier, smokeContrast)
        else -> AuraStaticBackground(albumColors, modifier)
    }
}

/** Сверхдешёвый статичный фон: один линейный градиент по палитре, без анимации. */
@Composable
private fun AuraStaticBackground(albumColors: AlbumColors, modifier: Modifier) {
    val top by animateColorAsState(albumColors.darkMuted, tween(600), label = "auraStaticTop")
    val bottom by animateColorAsState(albumColors.dominant, tween(600), label = "auraStaticBot")
    Box(
        modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(top, bottom)))
    )
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun AuraShaderBackground(
    albumColors: AlbumColors,
    intensity: Float,
    modifier: Modifier,
    animate: Boolean = true,
    smokeSaturation: Float = 1.0f,
    smokeContrast: Float = 1.0f,
) {
    // lite-устройства: 4 октавы fbm вместо 6 (~вдвое дешевле на пиксель),
    // объёмный свет выключен (он стоит +1 полную fbm-выборку на пиксель).
    val liteTier = com.liquidmusicglass.ui.DeviceTier.lite
    val shader = remember { RuntimeShader(auraAgsl(if (liteTier) 4 else 6, lighting = !liteTier)) }
    val brush = remember { ShaderBrush(shader) }

    // цвета вуалей — из палитры обложки, плавно меняются при смене трека
    val bg by animateColorAsState(albumColors.darkMuted, tween(900), label = "auraBg")
    val a by animateColorAsState(albumColors.vibrant, tween(900), label = "auraA")
    val b by animateColorAsState(albumColors.dominant, tween(900), label = "auraB")
    val c by animateColorAsState(albumColors.lightVibrant, tween(900), label = "auraC")

    // Фаза анимации интегрируется на CPU: базовая (НЕнулевая) скорость + узкий
    // басовый прирост ≤ +15% (множитель 1.0..1.15), накапливаемый по dt. Дым
    // всегда спокойно движется сам, бас лишь чуть подкручивает — без «стоп→турбо»
    // и без скачков фазы (умножать накопленное uTime на бас — НЕЛЬЗЯ).
    // Когда экран перекрыт оверлеем (открыт альбом/плеер/настройки) — замораживаем
    // тяжёлый AGSL-клок, чтобы не рисовать дым под другим экраном (это давало спайк
    // RenderThread → ANR на слабых GPU). produceState перезапускается при смене animate.
    // Фаза ПЕРСИСТЕНТНА между заморозками: когда экран перекрыт (animate=false)
    // produceState приостанавливается (кадры не тратятся — perf сохранён), но при
    // возврате фаза ПРОДОЛЖАЕТСЯ с того же места, а не сбрасывается в 0. Это
    // убирает «прыжок» дыма при переключении на главный (resume из phaseHolder).
    val phaseHolder = remember { floatArrayOf(0f) }
    val timeSec by produceState(phaseHolder[0], animate) {
        if (!animate) return@produceState
        var phase = phaseHolder[0]
        var s1 = 0f
        var s2 = 0f
        var lastMs = 0L
        var skip = false
        while (true) {
            withInfiniteAnimationFrameMillis { ms ->
                val dt = if (lastMs == 0L) 0f else ((ms - lastMs).coerceIn(0L, 64L)) / 1000f
                lastMs = ms
                s1 = advanceBass(s1, AudioReactor.low.coerceIn(0f, 1f))
                s2 += (s1 - s2) * BASS_STAGE2
                phase += dt * (1f + s2 * 0.15f)
                phaseHolder[0] = phase
                // lite: публикуем фазу через кадр — полноэкранный fbm рисуется на
                // ~30 Гц (фаза интегрируется по dt, так что движение не ускоряется).
                if (!liteTier || !skip) value = phase
                skip = !skip
            }
        }
    }

    // одно сглаженное число баса в шейдер — дыхание/яркость (узко, безопасно)
    val bass by rememberSmoothedBass(halfRate = liteTier)

    Box(
        modifier
            .fillMaxSize()
            // Без .background(Color.Black): шейдер заполняет кадр непрозрачно
            // (alpha=1.0), отдельная заливка фона была лишним полноэкранным overdraw.
            .drawBehind {
                shader.setFloatUniform("uResolution", size.width, size.height)
                shader.setFloatUniform("uTime", timeSec)
                shader.setFloatUniform("uIntensity", intensity)
                shader.setFloatUniform("uColorBg", bg.red, bg.green, bg.blue)
                shader.setFloatUniform("uColorA", a.red, a.green, a.blue)
                shader.setFloatUniform("uColorB", b.red, b.green, b.blue)
                shader.setFloatUniform("uColorC", c.red, c.green, c.blue)
                shader.setFloatUniform("uBass", bass)
                shader.setFloatUniform("uSmokeSaturation", smokeSaturation.coerceIn(0.0f, 2.0f))
                shader.setFloatUniform("uSmokeContrast", smokeContrast.coerceIn(0.0f, 2.0f))
                drawRect(brush)
            },
    )
}

/** Фолбэк для Android < 13: мягкий дрейфующий радиальный градиент на тех же цветах (без шейдера). */
@Composable
private fun AuraGradientFallback(albumColors: AlbumColors, modifier: Modifier, smokeContrast: Float = 1.0f) {
    val bg by animateColorAsState(albumColors.darkMuted, tween(900), label = "fbBg")
    val a by animateColorAsState(albumColors.vibrant, tween(900), label = "fbA")
    val b by animateColorAsState(albumColors.dominant, tween(900), label = "fbB")
    val c by animateColorAsState(albumColors.lightVibrant, tween(900), label = "fbC")

    // Частота публикаций: энергосбер → каждый 4-й кадр (~15 Гц), lite → каждый
    // 2-й (~30 Гц). Медленному дрейфу пятен этого более чем достаточно.
    val powerSave = com.liquidmusicglass.ui.PowerSaveMonitor.active
    val divider = when {
        powerSave -> 4
        com.liquidmusicglass.ui.DeviceTier.lite -> 2
        else -> 1
    }
    val phase by produceState(0f, divider) {
        var frame = 0
        while (true) {
            withInfiniteAnimationFrameMillis {
                if (frame % divider == 0) value = (it % 16000L) / 16000f
                frame++
            }
        }
    }

    // та же сглаженная огибающая баса — мягкое дыхание, без вспышек
    val bass by rememberSmoothedBass(halfRate = divider > 1)

    Box(
        modifier
            .fillMaxSize()
            .background(bg)
            .drawBehind {
                val w = size.width
                val h = size.height
                val tau = (2.0 * Math.PI).toFloat()
                // Бас слегка раздувает/подсвечивает пятна — УЗКО (±10..12%), без морганий
                val rBoost = 1f + bass * 0.10f
                val aBoost = (1f + bass * 0.12f) * smokeContrast.coerceIn(0.0f, 2.0f)
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
