package com.liquidmusicglass.ui.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.liquidmusicglass.ui.theme.LiquidTheme

/**
 * iOS-стиль (Batch 5): стекла на контенте больше нет — это плоская светло-серая
 * карточка-подложка под текущую тему (как Settings). [backdrop] и blur/tint-параметры
 * сохранены в сигнатуре ради совместимости вызовов, но не используются. Стекло
 * осталось только на тумблерах (LiquidToggle).
 */
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
    val lc = LiquidTheme.colors
    val surface = if (lc.isDark) Color(0xFF1C1C1E) else Color(0xFFF2F2F7)
    val border = if (lc.isDark) Color.White.copy(alpha = 0.06f) else Color.Black.copy(alpha = 0.05f)
    Box(
        modifier = modifier
            .clip(shape)
            .background(surface)
            .border(0.8.dp, border, shape)
    ) { content() }
}
