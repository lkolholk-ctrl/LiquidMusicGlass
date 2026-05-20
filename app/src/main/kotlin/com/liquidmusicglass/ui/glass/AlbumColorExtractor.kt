package com.liquidmusicglass.ui.glass

import android.graphics.BitmapFactory
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

data class AlbumColors(
    val dominant: Color = Color(0xFF1A1A2E),
    val darkMuted: Color = Color(0xFF0F0F1A),
    val vibrant: Color = Color(0xFF2A2A3E),
    val lightVibrant: Color = Color(0xFF3A3A4E),
    val muted: Color = Color(0xFF1E1E2E)
)

/**
 * Поднимает яркость тёмного цвета, чтобы фон не был чёрным.
 */
private fun boostDarkColor(color: Int, minBrightness: Float = 0.15f): Color {
    val r = android.graphics.Color.red(color) / 255f
    val g = android.graphics.Color.green(color) / 255f
    val b = android.graphics.Color.blue(color) / 255f
    val brightness = (r + g + b) / 3f

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

                val palette = Palette.from(bitmap)
                    .maximumColorCount(24)
                    .generate()

                val rawVibrant = palette.getVibrantColor(0xFF2A2A3E.toInt())
                val rawDominant = palette.getDominantColor(0xFF1A1A2E.toInt())
                val rawMuted = palette.getMutedColor(0xFF1E1E2E.toInt())
                val rawDarkMuted = palette.getDarkMutedColor(0xFF0F0F1A.toInt())
                val rawLightVibrant = palette.getLightVibrantColor(0xFF3A3A4E.toInt())

                // Если vibrant слишком тёмный — попробуем lightVibrant или dominant
                val bestVibrant = when {
                    brightnessOf(rawVibrant) > 0.08f -> rawVibrant
                    brightnessOf(rawLightVibrant) > 0.08f -> rawLightVibrant
                    brightnessOf(rawDominant) > 0.08f -> rawDominant
                    else -> rawMuted
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
    val r = android.graphics.Color.red(color) / 255f
    val g = android.graphics.Color.green(color) / 255f
    val b = android.graphics.Color.blue(color) / 255f
    return (r + g + b) / 3f
}
