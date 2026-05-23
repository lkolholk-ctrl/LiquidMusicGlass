package com.liquidmusicglass.ui.player

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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.liquidmusicglass.ui.glass.AlbumArtImage
import com.liquidmusicglass.ui.glass.AlbumColors

import androidx.compose.ui.graphics.toArgb

/**
 * Apple Music стиль — статичный градиентный фон из обложки.
 *
 * Техника:
 * 1. Несколько копий обложки разных размеров
 * 2. Тяжёлый blur поверх всего
 * 3. Gradient overlay для глубины
 *
 * Анимация (вращение/дрифт) убрана — фон статичный.
 */
@Composable
fun AnimatedPlayerBackground(
    albumArtUri: Uri?,
    coverUrl: String? = null,
    audioFileUri: Uri? = null,
    albumId: Long = -1L,
    albumColors: AlbumColors,
    modifier: Modifier = Modifier
) {
    val boostedVibrant = rememberSaturationBoost(albumColors.vibrant)
    val boostedDominant = rememberSaturationBoost(albumColors.dominant)
    val boostedMuted = rememberSaturationBoost(albumColors.muted)
    val boostedLightVibrant = rememberSaturationBoost(albumColors.lightVibrant)

    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {

        // ── Layer 1: базовый слой, заполняет всё ──
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
                    scaleX = 2.2f
                    scaleY = 2.2f
                    alpha = 0.70f
                }
                .blur(60.dp)
        )

        // ── Layer 2: средний слой ──
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
                    scaleX = 1.5f
                    scaleY = 1.5f
                    alpha = 0.60f
                }
                .blur(50.dp)
        )

        // ── Layer 3: яркий акцент ──
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
                    scaleX = 1.1f
                    scaleY = 1.1f
                    alpha = 0.50f
                }
                .blur(40.dp)
        )

        // ── Saturation boost — цветной слой от palette ──
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colorStops = arrayOf(
                            0.00f to boostedVibrant.copy(alpha = 0.45f),
                            0.35f to boostedDominant.copy(alpha = 0.35f),
                            0.65f to boostedMuted.copy(alpha = 0.45f),
                            1.00f to boostedVibrant.copy(alpha = 0.35f)
                        )
                    )
                )
        )

        // ── Horizontal color accent ──
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        colorStops = arrayOf(
                            0.00f to boostedLightVibrant.copy(alpha = 0.25f),
                            0.50f to Color.Transparent,
                            1.00f to boostedVibrant.copy(alpha = 0.20f)
                        )
                    )
                )
        )

        // ── Dark overlay for readability ──
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colorStops = arrayOf(
                            0.00f to Color.Black.copy(alpha = 0.08f),
                            0.40f to Color.Black.copy(alpha = 0.02f),
                            0.60f to Color.Black.copy(alpha = 0.08f),
                            1.00f to Color.Black.copy(alpha = 0.35f)
                        )
                    )
                )
        )
    }
}

@Composable
private fun rememberSaturationBoost(color: Color, boost: Float = 2.5f): Color {
    return androidx.compose.runtime.remember(color) {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(color.toArgb(), hsv)
        hsv[1] = (hsv[1] * boost).coerceIn(0f, 1f)
        androidx.compose.ui.graphics.Color(android.graphics.Color.HSVToColor(hsv))
    }
}

