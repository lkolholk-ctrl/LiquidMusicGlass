package com.liquidmusicglass.ui.player

import com.liquidmusicglass.ui.icons.LiquidGlyphs
import android.net.Uri
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.liquidmusicglass.ui.components.LikeBurstHeart
import com.liquidmusicglass.ui.glass.AlbumArtImage
import com.liquidmusicglass.ui.glass.pressScale
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Мини-плеер (референс — Яндекс.Музыка): плоская тёмная карточка, чуть
 * подкрашенная палитрой обложки. Без стекла/блюра — по GPU почти бесплатно.
 *
 * Слева обложка, по центру название+артист, справа сердечко и play/pause.
 * Тап по карточке — развернуть полный плеер; свайп влево/вправо — трек.
 * Показывается только на вкладках, где включают музыку (не на Wave-главной).
 */
@Composable
fun MiniPlayer(
    trackTitle: String,
    artistName: String,
    isPlaying: Boolean,
    albumArtUri: Uri?,
    coverUrl: String? = null,
    tint: Color = Color(0xFF221C1E),
    isLiked: Boolean = false,
    onToggleLike: () -> Unit = {},
    onExpand: () -> Unit,
    onPlayPause: () -> Unit,
    onSkipNext: () -> Unit = {},
    onSkipPrevious: () -> Unit = {}
) {
    val cardShape = RoundedCornerShape(18.dp)
    val artShape = RoundedCornerShape(12.dp)
    // Карточка темнее подкраса: подкрас — оттенок, не заливка цветом обложки.
    val cardColor = lerp(tint, Color.Black, 0.45f)

    // Свайп по карточке: влево = следующий, вправо = предыдущий.
    // Резинка с сопротивлением и упругий возврат — как у обложки на главной.
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val swipeThresholdPx = remember(density) { with(density) { 72.dp.toPx() } }
    val maxSwipePx = remember(density) { with(density) { 120.dp.toPx() } }
    val swipeOffset = remember { Animatable(0f) }

    Row(
        modifier = Modifier
            .graphicsLayer {
                translationX = swipeOffset.value
                alpha = 1f - (abs(swipeOffset.value) / (maxSwipePx * 3f)).coerceAtMost(0.25f)
            }
            .draggable(
                orientation = Orientation.Horizontal,
                state = rememberDraggableState { delta ->
                    scope.launch {
                        val next = (swipeOffset.value + delta * 0.8f)
                            .coerceIn(-maxSwipePx, maxSwipePx)
                        swipeOffset.snapTo(next)
                    }
                },
                onDragStopped = { velocity ->
                    val off = swipeOffset.value
                    when {
                        off < -swipeThresholdPx || velocity < -2400f -> onSkipNext()
                        off > swipeThresholdPx || velocity > 2400f -> onSkipPrevious()
                    }
                    scope.launch {
                        swipeOffset.animateTo(
                            0f,
                            spring(dampingRatio = 0.8f, stiffness = 380f)
                        )
                    }
                }
            )
            .fillMaxWidth(0.94f)
            .height(64.dp)
            .clip(cardShape)
            .background(cardColor)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onExpand() }
            .padding(start = 8.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(artShape)
        ) {
            AlbumArtImage(
                uri = albumArtUri,
                coverUrl = coverUrl,
                modifier = Modifier.size(48.dp),
                contentScale = ContentScale.Crop
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = trackTitle,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = artistName,
                color = Color.White.copy(alpha = 0.55f),
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        LikeBurstHeart(
            isLiked = isLiked,
            modifier = Modifier.size(44.dp),
            iconSize = 24.dp,
            onToggle = onToggleLike
        )

        Box(
            modifier = Modifier
                .size(44.dp)
                .pressScale { onPlayPause() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (isPlaying) LiquidGlyphs.Pause else LiquidGlyphs.Play,
                contentDescription = if (isPlaying) "Pause" else "Play",
                modifier = Modifier.size(28.dp),
                tint = Color.White
            )
        }
    }
}
