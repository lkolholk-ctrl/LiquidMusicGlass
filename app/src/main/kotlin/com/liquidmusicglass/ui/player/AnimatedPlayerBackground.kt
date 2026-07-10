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
        // Alpha цветных слоёв подняты ~×1.5, а чёрный оверлей ослаблен —
        // фон из палитры обложки заметно ярче (просьба: «ярче в 2 раза»).
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            boostedLightVibrant.copy(alpha = 0.80f),
                            boostedVibrant.copy(alpha = 0.55f),
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
                            0.00f to boostedVibrant.copy(alpha = 0.68f),
                            0.35f to boostedDominant.copy(alpha = 0.55f),
                            0.65f to boostedMuted.copy(alpha = 0.68f),
                            1.00f to boostedVibrant.copy(alpha = 0.55f)
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
                            0.00f to boostedLightVibrant.copy(alpha = 0.38f),
                            0.50f to Color.Transparent,
                            1.00f to boostedVibrant.copy(alpha = 0.32f)
                        )
                    )
                )
        )

        // ── Dark overlay for readability ──
        // Верх/середину почти не затемняем (фон должен светиться), низ держим
        // потемнее — под ним лежат контролы и текст, читаемость важнее.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colorStops = arrayOf(
                            0.00f to Color.Black.copy(alpha = 0.08f),
                            0.40f to Color.Black.copy(alpha = 0.04f),
                            0.60f to Color.Black.copy(alpha = 0.12f),
                            1.00f to Color.Black.copy(alpha = 0.45f)
                        )
                    )
                )
        )
    }
}

/**
 * Готовит цвет палитры под фон плеера: усиливает насыщенность И ЯРКОСТЬ.
 * Раньше бустилась только насыщенность (value не трогался) — тёмные обложки
 * давали «тухлый» фон. Теперь поднимаем и HSV-value с нижним порогом, чтобы
 * даже тёмный кавер давал ощутимое свечение.
 */
@Composable
private fun rememberSaturationBoost(
    color: Color,
    satBoost: Float = 2.5f,
    valBoost: Float = 1.9f,
    valFloor: Float = 0.38f
): Color {
    return androidx.compose.runtime.remember(color) {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(color.toArgb(), hsv)
        hsv[1] = (hsv[1] * satBoost).coerceIn(0f, 1f)
        hsv[2] = (hsv[2] * valBoost).coerceAtLeast(valFloor).coerceIn(0f, 1f)
        androidx.compose.ui.graphics.Color(android.graphics.Color.HSVToColor(hsv))
    }
}
