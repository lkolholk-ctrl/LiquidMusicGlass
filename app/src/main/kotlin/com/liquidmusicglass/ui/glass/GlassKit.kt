package com.liquidmusicglass.ui.glass

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.Capsule

/**
 * GlassKit — единая система стеклянных компонентов Kyant0.
 *
 * Полный стек эффектов:
 *  - vibrancy()        → цветовая вибрация через стекло
 *  - blur()            → размытие фона
 *  - lens()            → рефракция (искажение) + chromaticAberration
 *  - highlight         → световой блик сверху (как отражение света)
 *  - shadow            → тень под элементом (глубина)
 *  - innerShadow       → внутренняя тень (вдавленность)
 *  - onDrawSurface     → тонировка + обводка
 */
object GlassKit {

    // ═══════════════════════════════════════════
    //  Glass Card — карточка с полным стеклом
    // ═══════════════════════════════════════════

    @Composable
    fun Card(
        backdrop: LayerBackdrop,
        modifier: Modifier = Modifier,
        cornerRadius: Dp = 22.dp,
        blurRadius: Dp = 14.dp,
        refractionHeight: Dp = 18.dp,
        refractionAmount: Dp = 22.dp,
        tintColor: Color = Color.White.copy(alpha = 0.05f),
        borderColor: Color = Color.White.copy(alpha = 0.22f),
        highlightAlpha: Float = 0.6f,
        shadowAlpha: Float = 0.15f,
        innerShadowRadius: Dp = 4.dp,
        contentPadding: Dp = 20.dp,
        content: @Composable () -> Unit
    ) {
        val shape = RoundedCornerShape(cornerRadius)

        Box(
            modifier = modifier
                .fillMaxWidth()
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
                    highlight = {
                        Highlight.Ambient.copy(
                            alpha = highlightAlpha
                        )
                    },
                    shadow = {
                        Shadow(
                            radius = 8.dp,
                            color = Color.Black.copy(alpha = shadowAlpha)
                        )
                    },
                    innerShadow = {
                        InnerShadow(
                            radius = innerShadowRadius,
                            alpha = 0.3f
                        )
                    },
                    onDrawSurface = {
                        drawRect(tintColor)
                        drawRect(
                            color = borderColor,
                            style = Stroke(width = 0.8.dp.toPx())
                        )
                    }
                )
                .padding(horizontal = contentPadding, vertical = 6.dp)
        ) {
            Column { content() }
        }
    }

    // ═══════════════════════════════════════════
    //  Glass Pill — капсула (для чипов, табов)
    // ═══════════════════════════════════════════

    @Composable
    fun Pill(
        backdrop: LayerBackdrop,
        modifier: Modifier = Modifier,
        blurRadius: Dp = 10.dp,
        tintColor: Color = Color.White.copy(alpha = 0.04f),
        borderColor: Color = Color.White.copy(alpha = 0.20f),
        highlightAlpha: Float = 0.5f,
        shadowAlpha: Float = 0.10f,
        content: @Composable () -> Unit
    ) {
        Box(
            modifier = modifier
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { Capsule() },
                    effects = {
                        vibrancy()
                        blur(blurRadius.toPx())
                        lens(
                            refractionHeight = 14.dp.toPx(),
                            refractionAmount = 16.dp.toPx(),
                            chromaticAberration = true
                        )
                    },
                    highlight = {
                        Highlight.Ambient.copy(alpha = highlightAlpha)
                    },
                    shadow = {
                        Shadow(
                            radius = 4.dp,
                            color = Color.Black.copy(alpha = shadowAlpha)
                        )
                    },
                    innerShadow = {
                        InnerShadow(
                            radius = 3.dp,
                            alpha = 0.2f
                        )
                    },
                    onDrawSurface = {
                        drawRect(tintColor)
                        drawRect(
                            color = borderColor,
                            style = Stroke(width = 0.8.dp.toPx())
                        )
                    }
                )
        ) {
            content()
        }
    }

    // ═══════════════════════════════════════════
    //  Glass Circle — кнопка (назад, ресет)
    // ═══════════════════════════════════════════

    @Composable
    fun Circle(
        backdrop: LayerBackdrop,
        modifier: Modifier = Modifier,
        tintColor: Color = Color.White.copy(alpha = 0.04f),
        borderColor: Color = Color.White.copy(alpha = 0.20f),
        content: @Composable () -> Unit
    ) {
        Box(
            modifier = modifier
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { Capsule() },
                    effects = {
                        vibrancy()
                        blur(4.dp.toPx())
                        lens(
                            refractionHeight = 12.dp.toPx(),
                            refractionAmount = 16.dp.toPx(),
                            chromaticAberration = true
                        )
                    },
                    highlight = {
                        Highlight.Ambient.copy(alpha = 0.5f)
                    },
                    shadow = {
                        Shadow(
                            radius = 4.dp,
                            color = Color.Black.copy(alpha = 0.12f)
                        )
                    },
                    innerShadow = {
                        InnerShadow(radius = 3.dp, alpha = 0.2f)
                    },
                    onDrawSurface = {
                        drawRect(tintColor)
                        drawRect(
                            color = borderColor,
                            style = Stroke(width = 0.8.dp.toPx())
                        )
                    }
                )
        ) {
            content()
        }
    }

    // ═══════════════════════════════════════════
    //  Glass Row — строка списка (треки, поиск)
    // ═══════════════════════════════════════════

    @Composable
    fun Row(
        backdrop: LayerBackdrop,
        modifier: Modifier = Modifier,
        cornerRadius: Dp = 16.dp,
        tintColor: Color = Color.White.copy(alpha = 0.03f),
        borderColor: Color = Color.White.copy(alpha = 0.16f),
        content: @Composable () -> Unit
    ) {
        val shape = RoundedCornerShape(cornerRadius)

        Box(
            modifier = modifier
                .fillMaxWidth()
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { shape },
                    effects = {
                        vibrancy()
                        blur(4.dp.toPx())
                    },
                    highlight = {
                        Highlight.Ambient.copy(alpha = 0.3f)
                    },
                    shadow = {
                        Shadow(
                            radius = 2.dp,
                            color = Color.Black.copy(alpha = 0.06f)
                        )
                    },
                    onDrawSurface = {
                        drawRect(tintColor)
                        drawRect(
                            color = borderColor,
                            style = Stroke(width = 0.8.dp.toPx())
                        )
                    }
                )
        ) {
            content()
        }
    }

    // ═══════════════════════════════════════════
    //  Glass Accent Pill — подсвеченная (пресет, выбранный таб)
    // ═══════════════════════════════════════════

    @Composable
    fun AccentPill(
        backdrop: LayerBackdrop,
        modifier: Modifier = Modifier,
        accentColor: Color = Color(0xFFFC3C44),
        content: @Composable () -> Unit
    ) {
        Box(
            modifier = modifier
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { Capsule() },
                    effects = {
                        vibrancy()
                        blur(4.dp.toPx())
                        lens(
                            refractionHeight = 10.dp.toPx(),
                            refractionAmount = 12.dp.toPx(),
                            chromaticAberration = true
                        )
                    },
                    highlight = {
                        Highlight.Default.copy(alpha = 0.7f)
                    },
                    shadow = {
                        Shadow(
                            radius = 6.dp,
                            color = accentColor.copy(alpha = 0.3f)
                        )
                    },
                    innerShadow = {
                        InnerShadow(radius = 4.dp, alpha = 0.25f)
                    },
                    onDrawSurface = {
                        drawRect(accentColor)
                    }
                )
        ) {
            content()
        }
    }

    // ═══════════════════════════════════════════
    //  Glass MiniPlayer / Bar — прозрачная с highlight
    // ═══════════════════════════════════════════

    @Composable
    fun Bar(
        backdrop: LayerBackdrop,
        modifier: Modifier = Modifier,
        tintColor: Color = Color.White.copy(alpha = 0.04f),
        borderColor: Color = Color.White.copy(alpha = 0.20f),
        content: @Composable () -> Unit
    ) {
        Box(
            modifier = modifier
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { Capsule() },
                    effects = {
                        vibrancy()
                        blur(4.dp.toPx())
                        lens(
                            refractionHeight = 24.dp.toPx(),
                            refractionAmount = 32.dp.toPx(),
                            chromaticAberration = true
                        )
                    },
                    highlight = {
                        Highlight.Ambient.copy(alpha = 0.5f)
                    },
                    shadow = {
                        Shadow(
                            radius = 8.dp,
                            color = Color.Black.copy(alpha = 0.2f)
                        )
                    },
                    innerShadow = {
                        InnerShadow(radius = 4.dp, alpha = 0.15f)
                    },
                    onDrawSurface = {
                        drawRect(tintColor)
                        drawRect(
                            color = borderColor,
                            style = Stroke(width = 0.8.dp.toPx())
                        )
                    }
                )
        ) {
            content()
        }
    }

    // ═══════════════════════════════════════════
    //  Badges — Explicit, Verified
    // ═══════════════════════════════════════════

    @Composable
    fun ExplicitBadge(modifier: Modifier = Modifier) {
        Box(
            modifier = modifier
                .size(14.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(Color.White.copy(alpha = 0.25f)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "E",
                color = Color.White.copy(alpha = 0.8f),
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = 9.sp
            )
        }
    }

    @Composable
    fun VerifiedBadge(modifier: Modifier = Modifier) {
        Icon(
            imageVector = Icons.Rounded.CheckCircle,
            contentDescription = null,
            tint = Color(0xFF00BFFF),
            modifier = modifier.size(14.dp)
        )
    }
}
