package com.liquidmusicglass.ui.player

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.liquidmusicglass.ui.glass.AlbumColors

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue

import androidx.compose.ui.graphics.toArgb

/**
 * Apple Music стиль — статичный градиентный фон из палитры обложки.
 *
 * Важно для локального JUCE-воспроизведения: фон не декодирует bitmap. Раньше тут
 * было три AlbumArtImage + blur-слоя; для локальных треков каждый слой запускал
 * MediaMetadataRetriever/loadThumbnail, и открытие FullPlayer из уведомления могло
 * дать пачку тяжёлых декодов + GPU blur на первом кадре. Это забивало main/render
 * и приводило к ANR, а аудио в это время циклично повторяло последний блок.
 */
@Composable
@Suppress("UNUSED_PARAMETER")
fun AnimatedPlayerBackground(
    albumArtUri: Uri?,
    coverUrl: String? = null,
    audioFileUri: Uri? = null,
    albumId: Long = -1L,
    albumColors: AlbumColors,
    modifier: Modifier = Modifier
) {
    val baseVibrant = rememberSaturationBoost(albumColors.vibrant)
    val baseDominant = rememberSaturationBoost(albumColors.dominant)
    val baseMuted = rememberSaturationBoost(albumColors.muted)
    val baseLightVibrant = rememberSaturationBoost(albumColors.lightVibrant)

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
    val boostedLightVibrant by animateColorAsState(
        targetValue = baseLightVibrant,
        animationSpec = tween(durationMillis = 1000),
        label = "boostedLightVibrant"
    )

    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
        // ── Base palette field. Cheap and deterministic on first frame. ──
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            boostedLightVibrant.copy(alpha = 0.52f),
                            boostedVibrant.copy(alpha = 0.36f),
                            Color.Black
                        )
                    )
                )
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
                            0.00f to Color.Black.copy(alpha = 0.25f),
                            0.40f to Color.Black.copy(alpha = 0.15f),
                            0.60f to Color.Black.copy(alpha = 0.25f),
                            1.00f to Color.Black.copy(alpha = 0.55f)
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
