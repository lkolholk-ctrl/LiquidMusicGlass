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
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember

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
 * Плавный переход (crossfade) цветов и изображений при смене песен.
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
    val baseVibrant = rememberSaturationBoost(albumColors.vibrant, saturationBoost)
    val baseDominant = rememberSaturationBoost(albumColors.dominant, saturationBoost)
    val baseMuted = rememberSaturationBoost(albumColors.muted, saturationBoost)

    val boostedVibrant by animateColorAsState(
        targetValue = baseVibrant,
        animationSpec = tween(durationMillis = 1000),
        label = "boostedVibrant"
    )
    val boostedDominant by animateColorAsState(
        targetValue = baseDominant,
        animationSpec = tween(durationMillis = 1000),
        label = "boostedDominant"
    )
    val boostedMuted by animateColorAsState(
        targetValue = baseMuted,
        animationSpec = tween(durationMillis = 1000),
        label = "boostedMuted"
    )

    data class ArtState(
        val albumArtUri: Uri?,
        val coverUrl: String?,
        val audioFileUri: Uri?,
        val albumId: Long
    )
    val artState = remember(albumArtUri, coverUrl, audioFileUri, albumId) {
        ArtState(albumArtUri, coverUrl, audioFileUri, albumId)
    }

    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
        // ── Crossfade for static background image to prevent abrupt pops ──
        Crossfade(
            targetState = artState,
            animationSpec = tween(durationMillis = 1000),
            modifier = Modifier.fillMaxSize(),
            label = "lyricsBackgroundCrossfade"
        ) { state ->
            // ── Layer 1: размытая обложка ──
            AlbumArtImage(
                uri = state.albumArtUri,
                coverUrl = state.coverUrl,
                audioFileUri = state.audioFileUri,
                albumId = state.albumId,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = 1.8f
                        scaleY = 1.8f
                        alpha = 0.75f
                    }
                    .blur(LyricsTimeProcessor.BACKGROUND_BLUR_DP.dp)
            )
        }

        // ── Layer 2: HSV-boosted цветовой слой ──
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colorStops = arrayOf(
                            0.00f to boostedVibrant.copy(alpha = 0.45f),
                            0.35f to boostedDominant.copy(alpha = 0.40f),
                            0.65f to boostedMuted.copy(alpha = 0.45f),
                            1.00f to boostedVibrant.copy(alpha = 0.40f)
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
                            0.00f to boostedVibrant.copy(alpha = 0.30f),
                            0.50f to Color.Transparent,
                            1.00f to boostedMuted.copy(alpha = 0.25f)
                        )
                    )
                )
        )

        // ── Scrim 1: чёрная маска (глубина) ──
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.55f))
        )

        // ── Scrim 2: белая маска (глянцевое свечение) ──
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.White.copy(alpha = 0.02f))
        )

        // ── Bottom gradient для читаемости текста ──
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colorStops = arrayOf(
                            0.00f to Color.Black.copy(alpha = 0.25f),
                            0.40f to Color.Transparent,
                            0.60f to Color.Black.copy(alpha = 0.25f),
                            1.00f to Color.Black.copy(alpha = 0.55f)
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
