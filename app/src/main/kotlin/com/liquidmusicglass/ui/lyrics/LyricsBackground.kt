package com.liquidmusicglass.ui.lyrics

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.liquidmusicglass.ui.glass.AlbumArtImage
import com.liquidmusicglass.ui.glass.AlbumColors

/**
 * Статичный фон для экрана лирики с HSV-boost saturation = 2.5f.
 *
 * Техника:
 * 1. Размытая обложка (blur 25.dp)
 * 2. Два scrim-слоя: чёрный (alpha 0.30) + белый (alpha 0.08) для глянцевого свечения
 * 3. HSV-boosted цвет из палитры для неонового эффекта
 */
@Composable
fun LyricsBackground(
    albumArtUri: Uri?,
    coverUrl: String? = null,
    audioFileUri: Uri? = null,
    albumId: Long = -1L,
    albumColors: AlbumColors,
    saturationBoost: Float = LyricsTimeProcessor.SATURATION_BOOST,
    modifier: Modifier = Modifier
) {
    val boostedVibrant = rememberSaturationBoost(albumColors.vibrant, saturationBoost)
    val boostedDominant = rememberSaturationBoost(albumColors.dominant, saturationBoost)
    val boostedMuted = rememberSaturationBoost(albumColors.muted, saturationBoost)

    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
        // ── Layer 1: размытая обложка ──
        AlbumArtImage(
            uri = albumArtUri,
            coverUrl = coverUrl,
            audioFileUri = audioFileUri,
            albumId = albumId,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = 1.8f
                    scaleY = 1.8f
                    alpha = 0.55f
                }
                .blur(LyricsTimeProcessor.BACKGROUND_BLUR_DP.dp)
        )

        // ── Layer 2: HSV-boosted цветовой слой ──
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colorStops = arrayOf(
                            0.00f to boostedVibrant.copy(alpha = 0.35f),
                            0.35f to boostedDominant.copy(alpha = 0.30f),
                            0.65f to boostedMuted.copy(alpha = 0.35f),
                            1.00f to boostedVibrant.copy(alpha = 0.30f)
                        )
                    )
                )
        )

        // ── Layer 3: горизонтальный акцент ──
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        colorStops = arrayOf(
                            0.00f to boostedVibrant.copy(alpha = 0.20f),
                            0.50f to Color.Transparent,
                            1.00f to boostedMuted.copy(alpha = 0.18f)
                        )
                    )
                )
        )

        // ── Scrim 1: чёрная маска (глубина) ──
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = LyricsTimeProcessor.BLACK_SCRIM_ALPHA))
        )

        // ── Scrim 2: белая маска (глянцевое свечение) ──
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.White.copy(alpha = LyricsTimeProcessor.WHITE_SCRIM_ALPHA))
        )

        // ── Bottom gradient для читаемости текста ──
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colorStops = arrayOf(
                            0.00f to Color.Black.copy(alpha = 0.10f),
                            0.40f to Color.Transparent,
                            0.60f to Color.Black.copy(alpha = 0.15f),
                            1.00f to Color.Black.copy(alpha = 0.50f)
                        )
                    )
                )
        )
    }
}

@Composable
private fun rememberSaturationBoost(color: Color, boost: Float): Color {
    return androidx.compose.runtime.remember(color) {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(color.toArgb(), hsv)
        hsv[1] = (hsv[1] * boost).coerceIn(0f, 1f)
        androidx.compose.ui.graphics.Color(android.graphics.Color.HSVToColor(hsv))
    }
}
