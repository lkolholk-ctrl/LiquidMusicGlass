package com.liquidmusicglass.ui.glass

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy

@Composable
fun LiquidGlassSurface(
    backdrop: LayerBackdrop,
    modifier: Modifier = Modifier,
    cornerRadiusDp: Int = 28,
    blurRadius: Dp = 12.dp,
    refractionHeight: Dp = 24.dp,
    refractionAmount: Dp = 32.dp,
    tintAlpha: Float = 0.2f,
    borderAlpha: Float = 0.4f,
    content: @Composable () -> Unit
) {
    val shape = RoundedCornerShape(cornerRadiusDp.dp)

    Box(
        modifier = modifier
            .drawBackdrop(
                backdrop = backdrop,
                shape = { shape },
                effects = {
                    vibrancy()
                    blur(blurRadius.toPx())
                    lens(
                        refractionHeight = refractionHeight.toPx(),
                        refractionAmount = refractionAmount.toPx(),
                        chromaticAberration = true
                    )
                },
                onDrawSurface = {
                    drawRect(Color.White.copy(alpha = tintAlpha))
                    drawRect(
                        color = Color.White.copy(alpha = borderAlpha),
                        style = Stroke(width = 1.dp.toPx())
                    )
                }
            )
    ) {
        content()
    }
}
