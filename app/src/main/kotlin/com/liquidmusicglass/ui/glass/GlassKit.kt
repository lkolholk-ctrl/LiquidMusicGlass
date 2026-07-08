package com.liquidmusicglass.ui.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.liquidmusicglass.ui.theme.LiquidTheme

/**
 * GlassKit — общие «карточные» компоненты.
 *
 * iOS-стиль (Batch 5): на КОНТЕНТЕ стекла больше нет — это плоские светло-серые
 * карточки-группы с крупными скруглениями (как эталонный Settings). Стекло
 * (glassmorphism) осталось ТОЛЬКО на тумблерах (LiquidToggle). Параметр [backdrop]
 * и tint/blur-параметры сохранены в сигнатурах ради совместимости вызовов, но
 * больше не используются для захвата фона/блюра.
 */
object GlassKit {

    private val Capsule: Shape = RoundedCornerShape(percent = 50)

    /** Сплошная светло-серая подложка карточки под текущую тему (как Settings). */
    @Composable
    private fun cardSurface(): Color =
        if (LiquidTheme.colors.isDark) Color(0xFF1C1C1E) else Color(0xFFF2F2F7)

    @Composable
    private fun cardBorder(): Color =
        if (LiquidTheme.colors.isDark) Color.White.copy(alpha = 0.06f) else Color.Black.copy(alpha = 0.05f)

    @Composable
    private fun flatCard(shape: Shape, content: @Composable () -> Unit, modifier: Modifier) {
        Box(
            modifier = modifier
                .clip(shape)
                .background(cardSurface())
                .border(0.8.dp, cardBorder(), shape)
        ) { content() }
    }

    // ── Glass Card → плоская группа-карточка (крупный радиус) ──
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
                .clip(shape)
                .background(cardSurface())
                .border(0.8.dp, cardBorder(), shape)
                .padding(horizontal = contentPadding, vertical = 6.dp)
        ) { Column { content() } }
    }

    // ── Glass Pill → плоская капсула ──
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
        flatCard(Capsule, content, modifier)
    }

    // ── Glass Circle → плоская круглая кнопка ──
    @Composable
    fun Circle(
        backdrop: LayerBackdrop,
        modifier: Modifier = Modifier,
        tintColor: Color = Color.White.copy(alpha = 0.04f),
        borderColor: Color = Color.White.copy(alpha = 0.20f),
        content: @Composable () -> Unit
    ) {
        flatCard(Capsule, content, modifier)
    }

    // ── Glass Row → плоская строка списка (крупный радиус) ──
    @Composable
    fun Row(
        backdrop: LayerBackdrop,
        modifier: Modifier = Modifier,
        cornerRadius: Dp = 20.dp,
        tintColor: Color = Color.White.copy(alpha = 0.03f),
        borderColor: Color = Color.White.copy(alpha = 0.16f),
        content: @Composable () -> Unit
    ) {
        val shape = RoundedCornerShape(cornerRadius)
        Box(
            modifier = modifier
                .fillMaxWidth()
                .clip(shape)
                .background(cardSurface())
                .border(0.8.dp, cardBorder(), shape)
        ) { content() }
    }

    // ── Glass Accent Pill → сплошная акцентная капсула (выбранный таб/пресет) ──
    @Composable
    fun AccentPill(
        backdrop: LayerBackdrop,
        modifier: Modifier = Modifier,
        accentColor: Color = Color(0xFF7FB77E),
        content: @Composable () -> Unit
    ) {
        Box(modifier = modifier.clip(Capsule).background(accentColor)) { content() }
    }

    // ── Glass Bar → плоская капсула-бар ──
    @Composable
    fun Bar(
        backdrop: LayerBackdrop,
        modifier: Modifier = Modifier,
        tintColor: Color = Color.White.copy(alpha = 0.04f),
        borderColor: Color = Color.White.copy(alpha = 0.20f),
        content: @Composable () -> Unit
    ) {
        flatCard(Capsule, content, modifier)
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
