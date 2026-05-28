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
import androidx.compose.ui.platform.LocalContext
import androidx.palette.graphics.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ── Premium neutral dark base — obliterates blue/purple fallbacks ──
private val FALLBACK_DARK  = 0xFF121212.toInt()
private val FALLBACK_MUTED = 0xFF1C1C1E.toInt()

data class AlbumColors(
    val dominant: Color = Color(FALLBACK_DARK),
    val darkMuted: Color = Color(FALLBACK_DARK),
    val vibrant: Color = Color(FALLBACK_MUTED),
    val lightVibrant: Color = Color(FALLBACK_MUTED),
    val muted: Color = Color(FALLBACK_MUTED)
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

    // ── Warmth guard: detect cold blue/purple bias ──
    // If blue is dominant and red is weak → cold artifact
    val isColdBlueBias = (b > r + 0.12f) && (b > g + 0.08f)
    if (isColdBlueBias) {
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
                    // Online cover: download via HTTP
                    !coverUrl.isNullOrBlank() -> {
                        val url = java.net.URL(coverUrl)
                        url.openStream().use { stream ->
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

                // ── Population / luminance filter for contrast richness ──
                val bestVibrant = when {
                    targetSwatch != null && targetSwatch.population > 40 &&
                        brightnessOf(targetSwatch.rgb) > 0.06f -> targetSwatch.rgb
                    isWarmAndSaturated(rawVibrant) -> rawVibrant
                    isWarmAndSaturated(rawLightVibrant) -> rawLightVibrant
                    isWarmAndSaturated(rawDominant) -> rawDominant
                    isWarmAndSaturated(rawMuted) -> rawMuted
                    else -> FALLBACK_MUTED
                }

                AlbumColors(
                    dominant = boostDarkColor(rawDominant, 0.10f),
                    darkMuted = Color(rawDarkMuted),
                    vibrant = boostDarkColor(bestVibrant, 0.12f),
                    lightVibrant = boostDarkColor(rawLightVibrant, 0.15f),
                    muted = boostDarkColor(rawMuted, 0.10f)
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

/**
 * Returns true if the color is warm-leaning (not cold blue/purple)
 * and has enough saturation to be a real swatch, not compression noise.
 */
private fun isWarmAndSaturated(color: Int): Boolean {
    val r = AndroidColor.red(color) / 255f
    val g = AndroidColor.green(color) / 255f
    val b = AndroidColor.blue(color) / 255f
    val brightness = (r + g + b) / 3f
    if (brightness < 0.02f) return false

    val maxCh = maxOf(r, g, b)
    val minCh = minOf(r, g, b)
    val saturation = if (maxCh > 0f) (maxCh - minCh) / maxCh else 0f
    if (saturation < 0.10f) return false

    // Reject cold blue/purple bias
    val isCold = (b > r + 0.12f) && (b > g + 0.08f)
    return !isCold
}
