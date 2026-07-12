package com.liquidmusicglass.ui.glass

import android.graphics.BitmapFactory
import android.graphics.Color as AndroidColor
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.palette.graphics.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ── Premium neutral dark base — obliterates blue/purple fallbacks ──
private val FALLBACK_DARK  = 0xFF121212.toInt()
private val FALLBACK_MUTED = 0xFF1C1C1E.toInt()

data class AlbumColors(
    val dominant: Color = Color(FALLBACK_DARK),
    val darkMuted: Color = Color(FALLBACK_DARK),      // база дыма — тёмная, но сочная
    val darkVibrant: Color = Color(FALLBACK_DARK),    // глубокий насыщенный тон
    val vibrant: Color = Color(FALLBACK_MUTED),
    val lightVibrant: Color = Color(FALLBACK_MUTED),
    val muted: Color = Color(FALLBACK_MUTED),
    val lightMuted: Color = Color(FALLBACK_MUTED),    // светлый дымок/подсветка
    val accents: List<Color> = emptyList()            // топ-N сочных свотчей обложки
)

/**
 * Поднимает яркость тёмного цвета, чтобы фон не был чёрным.
 * Если цвет слишком холодный (blue/purple bias) или малонасыщенный —
 * примешиваем тёплый нейтральный тон или чёрный для чистоты.
 */
private fun boostDarkColor(color: Int, minBrightness: Float = 0.15f): Color {
    val r = AndroidColor.red(color) / 255f
    val g = AndroidColor.green(color) / 255f
    val b = AndroidColor.blue(color) / 255f
    val brightness = (r + g + b) / 3f

    // ── Saturation guard: reject near-gray / compression noise ──
    val maxCh = maxOf(r, g, b)
    val minCh = minOf(r, g, b)
    val saturation = if (maxCh > 0f) (maxCh - minCh) / maxCh else 0f

    // If too desaturated (gray/noise), force pure dark neutral
    if (saturation < 0.08f || brightness < 0.02f) {
        return Color(FALLBACK_DARK)
    }

    // ── Cold-ARTIFACT guard (не анти-синий!): гасим только МАЛОнасыщенный
    // холодный тон — это грязь сжатия на тёмных обложках. Насыщенный
    // фиолетовый/розовый/синий — честный цвет обложки, ПРОПУСКАЕМ как есть
    // (раньше он «согревался» в бурый — фон терял цвет, полевой фидбек).
    val isColdBlueBias = (b > r + 0.12f) && (b > g + 0.08f)
    if (isColdBlueBias && saturation < 0.30f) {
        // Blend toward warm neutral to kill the cold tint
        val warmR = (r * 0.5f + 0.12f).coerceIn(0f, 1f)
        val warmG = (g * 0.5f + 0.10f).coerceIn(0f, 1f)
        val warmB = (b * 0.3f + 0.08f).coerceIn(0f, 1f)
        return Color(warmR, warmG, warmB)
    }

    return if (brightness < minBrightness && brightness > 0.01f) {
        val boost = minBrightness / brightness.coerceAtLeast(0.02f)
        Color(
            red = (r * boost).coerceIn(0f, 1f),
            green = (g * boost).coerceIn(0f, 1f),
            blue = (b * boost).coerceIn(0f, 1f)
        )
    } else {
        Color(color)
    }
}

/**
 * Hue-СОХРАНЯЮЩИЙ подъём яркости + буст насыщенности в HSV. Заменяет прежний
 * аддитивный withMinBrightness, который поднимал R/G/B на ОДНУ величину и тем
 * ДЕСАТУРИРОВАЛ цвет (тянул к серому — «вымыто», полевой фидбек): аддитив держит
 * (max−min), но растит max → S=(max−min)/max падает. Здесь V умножается — max и min
 * масштабируются одинаково, S в HSV не меняется (яркость без потери цвета), а satMul
 * отдельно добавляет сочности. valFloor — нижний порог яркости (чтобы тёмные/чёрные
 * каверы не давали чёрный дым). H не трогаем — оттенок обложки сохранён точно.
 */
private fun vivid(c: Color, satMul: Float, valMul: Float, valFloor: Float): Color {
    val hsv = FloatArray(3)
    AndroidColor.colorToHSV(c.toArgb(), hsv)
    hsv[1] = (hsv[1] * satMul).coerceIn(0f, 1f)
    hsv[2] = (hsv[2] * valMul).coerceAtLeast(valFloor).coerceIn(0f, 1f)
    return Color(AndroidColor.HSVToColor(hsv))
}

/**
 * Топ-N самых сочных населённых свотчей обложки как акцент-цвета (для пилюль,
 * подсветок, доп. вуали дыма). Скоринг тот же, что у bestVibrant: насыщенность ×
 * населённость. Малонасыщенное/шумовое (sat<0.25 или редкое) не берём.
 */
private fun topAccents(palette: Palette, satMul: Float, count: Int): List<Color> =
    palette.swatches
        .filter { saturationOf(it.rgb) >= 0.25f && it.population >= 40 }
        .sortedByDescending { saturationOf(it.rgb) * (minOf(it.population, 500) / 500f) }
        .take(count)
        .map { vivid(Color(it.rgb), satMul = satMul, valMul = 1.05f, valFloor = 0.20f) }

@Composable
fun rememberAlbumColors(uri: Uri?, coverUrl: String? = null): AlbumColors {
    val context = LocalContext.current
    var colors by remember { mutableStateOf(AlbumColors()) }

    LaunchedEffect(uri, coverUrl) {
        if (uri == null && coverUrl.isNullOrBlank()) {
            colors = AlbumColors()
            return@LaunchedEffect
        }
        colors = withContext(Dispatchers.IO) {
            try {
                val bitmap = when {
                    // Локальный файл обложки (downloads/.covers/… у ЯМ-загрузок):
                    // раньше путь уходил в java.net.URL → MalformedURLException →
                    // фон оставался серым фолбэком.
                    !coverUrl.isNullOrBlank() &&
                        (coverUrl.startsWith("/") || coverUrl.startsWith("file://")) -> {
                        val path = coverUrl.removePrefix("file://")
                        val options = BitmapFactory.Options().apply {
                            inSampleSize = 8
                        }
                        BitmapFactory.decodeFile(path, options)
                    }
                    // Online cover: download via HTTP
                    !coverUrl.isNullOrBlank() -> {
                        val connection = java.net.URL(coverUrl)
                            .openConnection() as java.net.HttpURLConnection
                        // Некоторые обложечные CDN режут дефолтный Java-UA
                        connection.setRequestProperty("User-Agent", "Mozilla/5.0")
                        connection.connectTimeout = 10_000
                        connection.readTimeout = 10_000
                        connection.inputStream.use { stream ->
                            val options = BitmapFactory.Options().apply {
                                inSampleSize = 8
                            }
                            BitmapFactory.decodeStream(stream, null, options)
                        }
                    }
                    // Local album art via ContentResolver
                    uri != null -> {
                        context.contentResolver.openInputStream(uri)?.use { stream ->
                            val options = BitmapFactory.Options().apply {
                                inSampleSize = 8
                            }
                            BitmapFactory.decodeStream(stream, null, options)
                        }
                    }
                    else -> null
                } ?: return@withContext AlbumColors()

                // ── Palette generation OFF the Main thread ──
                val palette = withContext(Dispatchers.Default) {
                    Palette.from(bitmap)
                        .maximumColorCount(24)
                        .generate()
                }

                // Recycle bitmap immediately after palette extraction to free native memory
                bitmap.recycle()

                // ── Монохромная обложка (ч/б или серая): цвет оттуда не выжать
                // ВООБЩЕ — «вибрант» на такой обложке это шумовой пиксель, и фон
                // получал случайный грязный оттенок. Честно признаём монохром:
                // дым — светло-серый (заметно ярче обычного), база — графит.
                // Тонированные ч/б (сепия, холодный синеватый) сюда НЕ попадают:
                // у них есть заселённый свотч с sat >= 0.12. ──
                val isMonochrome = palette.swatches.isNotEmpty() &&
                    palette.swatches.none { saturationOf(it.rgb) >= 0.12f && it.population >= 40 }
                if (isMonochrome) {
                    // Светлота серых следует за яркостью обложки:
                    // тёмный ч/б → графит + серый дым, светлый → серебро.
                    val avgB = brightnessOf(palette.getDominantColor(FALLBACK_DARK))
                    fun gray(v: Float): Color {
                        val c = (v.coerceIn(0f, 1f) * 255).toInt()
                        return Color(c, c, c)
                    }
                    val hi = 0.60f + avgB * 0.25f      // дым — светлый
                    val lo = 0.15f + avgB * 0.10f      // база — графит
                    return@withContext AlbumColors(
                        dominant = gray(lo + 0.06f),
                        darkMuted = gray(lo),
                        vibrant = gray(hi),
                        lightVibrant = gray(hi + 0.12f),
                        muted = gray(lo + 0.10f)
                    )
                }

                // ── Vibrant swatch priority chain (high-contrast, punchy) ──
                val targetSwatch = palette.vibrantSwatch
                    ?: palette.darkVibrantSwatch
                    ?: palette.lightVibrantSwatch
                    ?: palette.darkMutedSwatch

                val rawVibrant = targetSwatch?.rgb ?: FALLBACK_MUTED
                val rawDominant = palette.getDominantColor(FALLBACK_DARK)
                val rawMuted = palette.getMutedColor(FALLBACK_MUTED)
                val rawDarkMuted = palette.getDarkMutedColor(FALLBACK_DARK)
                val rawLightVibrant = palette.getLightVibrantColor(FALLBACK_MUTED)

                // ── Выбор vibrant: решает НАСЫЩЕННОСТЬ × населённость, а не
                // «теплота». Фиолетовый/розовый/синий — полноправные цвета
                // фона (раньше isWarmAndSaturated их отбраковывал, и обложка
                // с сочным фиолетом давала страшно-тёмный фон). ──
                val candidates = listOfNotNull(
                    palette.vibrantSwatch,
                    palette.darkVibrantSwatch,
                    palette.lightVibrantSwatch,
                    palette.dominantSwatch,
                    palette.mutedSwatch
                )
                val bestSwatch = candidates
                    .filter { brightnessOf(it.rgb) > 0.05f && saturationOf(it.rgb) >= 0.18f }
                    .maxByOrNull {
                        saturationOf(it.rgb) *
                            (0.55f + 0.45f * (minOf(it.population, 500) / 500f))
                    }
                val bestVibrant = bestSwatch?.rgb
                    ?: rawVibrant.takeIf { saturationOf(it) >= 0.12f }
                    ?: FALLBACK_MUTED

                // Двухступенчато: boostDarkColor (коррекция холодного/серого,
                // мультипликативный лифт) → vivid (HSV: буст насыщенности + hue-
                // сохраняющий подъём яркости с нижним порогом). Раньше второй ступенью
                // был аддитивный withMinBrightness, который ДЕСАТУРИРОВАЛ («вымыто»).
                // darkMuted — база дыма: сочность поднимаем, но яркость держим (valMul
                // 1.0) — глубина/объём дыма важнее, чем светлота фона.
                AlbumColors(
                    dominant = vivid(boostDarkColor(rawDominant, 0.10f), satMul = 1.50f, valMul = 1.10f, valFloor = 0.18f),
                    darkMuted = vivid(Color(rawDarkMuted), satMul = 1.60f, valMul = 1.00f, valFloor = 0.12f),
                    darkVibrant = vivid(boostDarkColor(palette.getDarkVibrantColor(FALLBACK_DARK), 0.10f), satMul = 1.50f, valMul = 1.05f, valFloor = 0.16f),
                    vibrant = vivid(boostDarkColor(bestVibrant, 0.12f), satMul = 1.55f, valMul = 1.15f, valFloor = 0.22f),
                    lightVibrant = vivid(boostDarkColor(rawLightVibrant, 0.15f), satMul = 1.45f, valMul = 1.15f, valFloor = 0.26f),
                    muted = vivid(boostDarkColor(rawMuted, 0.10f), satMul = 1.45f, valMul = 1.10f, valFloor = 0.16f),
                    lightMuted = vivid(Color(palette.getLightMutedColor(FALLBACK_MUTED)), satMul = 1.30f, valMul = 1.15f, valFloor = 0.30f),
                    accents = topAccents(palette, satMul = 1.40f, count = 3)
                )
            } catch (_: Exception) {
                AlbumColors()
            }
        }
    }

    return colors
}

private fun brightnessOf(color: Int): Float {
    val r = AndroidColor.red(color) / 255f
    val g = AndroidColor.green(color) / 255f
    val b = AndroidColor.blue(color) / 255f
    return (r + g + b) / 3f
}

private fun saturationOf(color: Int): Float {
    val r = AndroidColor.red(color) / 255f
    val g = AndroidColor.green(color) / 255f
    val b = AndroidColor.blue(color) / 255f
    val maxCh = maxOf(r, g, b)
    val minCh = minOf(r, g, b)
    return if (maxCh > 0f) (maxCh - minCh) / maxCh else 0f
}
