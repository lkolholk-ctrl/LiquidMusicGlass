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

    // Насыщенный базовый цвет фона из обложки (+сатурация/+яркость, как у Apple).
    val bgColor by animateColorAsState(
        targetValue = boostedCoverColor(albumColors.vibrant),
        animationSpec = tween(durationMillis = 1000),
        label = "lyricsBgColor"
    )

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

    // База — НАСЫЩЕННЫЙ цвет обложки (никакого серого/чёрного оверлея поверх).
    Box(modifier = modifier.fillMaxSize().background(bgColor)) {
        // ── Crossfade for static background image to prevent abrupt pops ──
        Crossfade(
            targetState = artState,
            animationSpec = tween(durationMillis = 1000),
            modifier = Modifier.fillMaxSize(),
            label = "lyricsBackgroundCrossfade"
        ) { state ->
            // ── Layer 1: размытая обложка (лёгкая фактура поверх насыщенной базы) ──
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
                        alpha = 0.45f
                    }
                    // При просадке FPS режем тяжёлый full-screen blur (RenderEffect) —
                    // на слабом GPU это один из главных пожирателей RenderThread.
                    .blur(
                        (if (com.liquidmusicglass.ui.PerfMonitor.degraded) 6
                         else LyricsTimeProcessor.BACKGROUND_BLUR_DP).dp
                    )
            )
        }

        // ── Layer 2: насыщенный цветовой градиент (глубина, без серого) ──
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colorStops = arrayOf(
                            0.00f to boostedVibrant.copy(alpha = 0.55f),
                            0.40f to boostedDominant.copy(alpha = 0.45f),
                            0.75f to boostedMuted.copy(alpha = 0.50f),
                            1.00f to boostedVibrant.copy(alpha = 0.50f)
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
                            0.00f to boostedVibrant.copy(alpha = 0.28f),
                            0.50f to Color.Transparent,
                            1.00f to boostedMuted.copy(alpha = 0.24f)
                        )
                    )
                )
        )

        // ── Лёгкое затемнение только у нижней кромки (под навбар), без серой плёнки ──
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colorStops = arrayOf(
                            0.75f to Color.Transparent,
                            1.00f to Color.Black.copy(alpha = 0.28f)
                        )
                    )
                )
        )
    }
}

/**
 * Насыщенный цвет обложки для фона/шапки: +30% сатурации и +30% яркости
 * (но не вымывая в пастель). Используется и фоном лирики, и скрим-шапкой.
 */
fun boostedCoverColor(color: Color): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(color.toArgb(), hsv)
    hsv[1] = (hsv[1] * 1.3f).coerceAtMost(1f)
    hsv[2] = (hsv[2] * 1.3f).coerceAtMost(0.85f)
    return Color(android.graphics.Color.HSVToColor(hsv))
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
