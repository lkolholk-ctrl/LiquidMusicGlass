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
                    alpha = 0.5f
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
                    alpha = 0.45f
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
                    alpha = 0.35f
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
                            0.00f to albumColors.vibrant.copy(alpha = 0.25f),
                            0.35f to albumColors.dominant.copy(alpha = 0.20f),
                            0.65f to albumColors.muted.copy(alpha = 0.25f),
                            1.00f to albumColors.vibrant.copy(alpha = 0.20f)
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
                            0.00f to albumColors.lightVibrant.copy(alpha = 0.15f),
                            0.50f to Color.Transparent,
                            1.00f to albumColors.vibrant.copy(alpha = 0.12f)
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
                            0.00f to Color.Black.copy(alpha = 0.15f),
                            0.40f to Color.Black.copy(alpha = 0.05f),
                            0.60f to Color.Black.copy(alpha = 0.15f),
                            1.00f to Color.Black.copy(alpha = 0.45f)
                        )
                    )
                )
        )
    }
}
