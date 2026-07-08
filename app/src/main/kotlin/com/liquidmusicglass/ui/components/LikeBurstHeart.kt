package com.liquidmusicglass.ui.components

import com.liquidmusicglass.ui.icons.LiquidGlyphs
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Сердечко лайка с «взрывом»: при постановке лайка — упругий scale-подскок,
 * разлёт частиц по кругу и короткий хаптик. Снятие лайка — тихое, без эффектов.
 * Используется в FullPlayer и мини-плеере.
 */
@Composable
fun LikeBurstHeart(
    isLiked: Boolean,
    modifier: Modifier = Modifier,
    iconSize: Dp = 24.dp,
    likedTint: Color = Color(0xFFFC3C44),
    idleTint: Color = Color.White.copy(alpha = 0.70f),
    onToggle: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val burst = remember { Animatable(0f) }   // прогресс разлёта частиц 0→1
    val scale = remember { Animatable(1f) }

    Box(
        modifier = modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null
        ) {
            val turningOn = !isLiked
            onToggle()
            if (turningOn) {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                scope.launch {
                    launch {
                        scale.snapTo(0.6f)
                        scale.animateTo(1f, spring(dampingRatio = 0.35f, stiffness = 650f))
                    }
                    burst.snapTo(0.001f)
                    burst.animateTo(1f, tween(550, easing = FastOutSlowInEasing))
                    burst.snapTo(0f)
                }
            }
        },
        contentAlignment = Alignment.Center
    ) {
        val p = burst.value
        if (p > 0f && p < 1f) {
            Canvas(Modifier.fillMaxSize()) {
                val radius = size.minDimension / 2f * (0.45f + 0.55f * p)
                val alpha = (1f - p).coerceIn(0f, 1f)
                val dotR = size.minDimension * 0.05f * (1f - p * 0.55f)
                repeat(8) { i ->
                    val ang = i * (2f * PI.toFloat() / 8f) + 0.35f
                    drawCircle(
                        color = likedTint.copy(alpha = alpha),
                        radius = dotR,
                        center = Offset(
                            center.x + cos(ang) * radius,
                            center.y + sin(ang) * radius
                        )
                    )
                }
            }
        }
        Icon(
            imageVector = if (isLiked) LiquidGlyphs.Favorite else LiquidGlyphs.FavoriteBorder,
            contentDescription = null,
            tint = if (isLiked) likedTint else idleTint,
            modifier = Modifier
                .size(iconSize)
                .graphicsLayer {
                    scaleX = scale.value
                    scaleY = scale.value
                }
        )
    }
}
