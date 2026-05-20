package com.liquidmusicglass.ui.glass

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope

/**
 * Animated List Item — плавное появление элементов списка.
 *
 * Каждый элемент появляется с:
 * - Fade in (0 → 1)
 * - Slide up (30dp → 0)
 * - Slight scale (0.95 → 1.0)
 *
 * @param index позиция элемента — задержка пропорциональна
 * @param staggerMs задержка между элементами (мс)
 */
@Composable
fun AnimatedListItem(
    index: Int,
    modifier: Modifier = Modifier,
    staggerMs: Long = 40L,
    content: @Composable () -> Unit
) {
    val alpha = remember { Animatable(0f) }
    val offsetY = remember { Animatable(30f) }
    val scale = remember { Animatable(0.95f) }

    LaunchedEffect(Unit) {
        delay(index * staggerMs)
        coroutineScope {
            launch {
                alpha.animateTo(
                    1f,
                    spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 300f)
                )
            }
            launch {
                offsetY.animateTo(
                    0f,
                    spring(dampingRatio = 0.8f, stiffness = 250f)
                )
            }
            launch {
                scale.animateTo(
                    1f,
                    spring(dampingRatio = 0.85f, stiffness = 300f)
                )
            }
        }
    }

    Box(
        modifier = modifier.graphicsLayer {
            this.alpha = alpha.value
            translationY = offsetY.value * density
            scaleX = scale.value
            scaleY = scale.value
        }
    ) {
        content()
    }
}
