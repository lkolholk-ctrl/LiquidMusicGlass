package com.liquidmusicglass.ui.player

import android.net.Uri
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.liquidmusicglass.ui.glass.AlbumArtImage
import com.liquidmusicglass.ui.glass.AlbumColors
import kotlin.math.cos
import kotlin.math.sin

/**
 * Apple Music стиль — анимированный градиентный фон.
 *
 * Техника (реверс-инженеринг Apple Music):
 * 1. Несколько копий обложки разных размеров (25%, 50%, 80%, 125%)
 * 2. Перенасыщение (повышенная saturation)
 * 3. Каждая копия медленно вращается
 * 4. Тяжёлый blur поверх всего
 * 5. Gradient overlay для глубины
 */
@Composable
fun AnimatedPlayerBackground(
    albumArtUri: Uri?,
    audioFileUri: Uri? = null,
    albumId: Long = -1L,
    albumColors: AlbumColors,
    modifier: Modifier = Modifier
) {
    val transition = rememberInfiniteTransition(label = "bg")

    // Медленное вращение для каждого слоя
    val rotation1 by transition.animateFloat(
        0f, 360f,
        infiniteRepeatable(tween(45000, easing = LinearEasing), RepeatMode.Restart),
        label = "rot1"
    )
    val rotation2 by transition.animateFloat(
        0f, -360f,
        infiniteRepeatable(tween(55000, easing = LinearEasing), RepeatMode.Restart),
        label = "rot2"
    )
    val rotation3 by transition.animateFloat(
        0f, 360f,
        infiniteRepeatable(tween(70000, easing = LinearEasing), RepeatMode.Restart),
        label = "rot3"
    )

    // Медленное перемещение по кругу для маленьких копий
    val drift1 by transition.animateFloat(
        0f, 6.2832f, // 2π
        infiniteRepeatable(tween(30000, easing = LinearEasing), RepeatMode.Restart),
        label = "drift1"
    )
    val drift2 by transition.animateFloat(
        0f, 6.2832f,
        infiniteRepeatable(tween(40000, easing = LinearEasing), RepeatMode.Restart),
        label = "drift2"
    )

    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {

        // ── Layer 1: 125% — базовый слой, заполняет всё ──
        AlbumArtImage(
            uri = albumArtUri,
            audioFileUri = audioFileUri,
            albumId = albumId,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = 2.2f
                    scaleY = 2.2f
                    rotationZ = rotation1 * 0.3f
                    alpha = 0.5f
                }
                .blur(60.dp)
        )

        // ── Layer 2: 80% — средний слой ──
        AlbumArtImage(
            uri = albumArtUri,
            audioFileUri = audioFileUri,
            albumId = albumId,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = 1.5f
                    scaleY = 1.5f
                    rotationZ = rotation2 * 0.4f
                    translationX = sin(drift1.toDouble()).toFloat() * 40f
                    translationY = cos(drift1.toDouble()).toFloat() * 30f
                    alpha = 0.45f
                }
                .blur(50.dp)
        )

        // ── Layer 3: 50% — яркий акцент ──
        AlbumArtImage(
            uri = albumArtUri,
            audioFileUri = audioFileUri,
            albumId = albumId,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = 1.1f
                    scaleY = 1.1f
                    rotationZ = rotation3 * 0.2f
                    translationX = cos(drift2.toDouble()).toFloat() * 50f
                    translationY = sin(drift2.toDouble()).toFloat() * 40f
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
