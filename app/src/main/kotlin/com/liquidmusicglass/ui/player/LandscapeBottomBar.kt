package com.liquidmusicglass.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.liquidmusicglass.engine.PlayerController
import com.liquidmusicglass.ui.glass.liquidClickable
import com.liquidmusicglass.ui.icons.LiquidGlyphs
import com.liquidmusicglass.ui.theme.LiquidTheme

/**
 * Полноширинный нижний мини-плеер для широких окон (телефон-альбом / планшет).
 * Расположение — по референсу друга: слева обложка+инфо, по центру
 * управление и прогресс, справа shuffle / repeat / favorite / queue. Стиль —
 * НАШ ([LiquidTheme.colors], LiquidGlyphs), работает в светлой и тёмной теме.
 * Все действия ведут в реальный [PlayerController] — новой логики нет, только
 * широкая раскладка уже существующих контролов.
 */
@Composable
fun LandscapeBottomBar(
    onExpand: () -> Unit,
    onQueueClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val lc = LiquidTheme.colors
    val context = LocalContext.current

    val track by PlayerController.currentTrack.collectAsState()
    val isPlaying by PlayerController.isPlaying.collectAsState()
    val positionMs by PlayerController.currentPositionMs.collectAsState()
    val durationMs by PlayerController.durationMs.collectAsState()
    val shuffleEnabled by PlayerController.shuffleEnabled.collectAsState()
    val repeatMode by PlayerController.repeatMode.collectAsState()
    val favoriteIds by PlayerController.favoriteIds.collectAsState()

    val cur = track ?: return
    val isFavorite = favoriteIds.contains(cur.id)

    var isDragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    val safeDuration = durationMs.takeIf { it > 0L } ?: 1L
    val liveFraction = if (durationMs > 0L) positionMs.toFloat() / durationMs else 0f
    val displayFraction = (if (isDragging) dragFraction else liveFraction).coerceIn(0f, 1f)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(12.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(lc.cardSurface)
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // ── Слева: обложка + название/артист ──
        Row(
            modifier = Modifier
                .width(240.dp)
                .clip(RoundedCornerShape(12.dp))
                .liquidClickable(onClick = onExpand)
                .padding(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (lc.isDark) Color(0xFF242424) else Color(0xFFE3E3E8)),
                contentAlignment = Alignment.Center
            ) {
                val cover = cur.coverUrl ?: cur.displayArtUri.toString()
                if (!cover.isNullOrBlank()) {
                    AsyncImage(
                        model = cover, contentDescription = null,
                        contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(LiquidGlyphs.MusicNote, null, tint = lc.iconMuted, modifier = Modifier.size(20.dp))
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    cur.title, color = lc.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Text(
                    cur.artist, color = lc.textSecondary, fontSize = 12.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
        }

        Spacer(Modifier.width(16.dp))

        // ── По центру: управление + прогресс ──
        Column(
            modifier = Modifier.weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircleIconButton(LiquidGlyphs.Previous, "Previous", 34.dp, 20.dp, lc.textPrimary) {
                    PlayerController.skipPrevious(context)
                }
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(lc.accent)
                        .liquidClickable { PlayerController.togglePlayPause(context) },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        if (isPlaying) LiquidGlyphs.Pause else LiquidGlyphs.Play,
                        if (isPlaying) "Pause" else "Play",
                        tint = if (lc.isDark) Color.Black else Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
                CircleIconButton(LiquidGlyphs.Next, "Next", 34.dp, 20.dp, lc.textPrimary) {
                    PlayerController.skipNext(context)
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(0.92f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    formatMs(if (isDragging) (dragFraction * safeDuration).toLong() else positionMs),
                    color = lc.textTertiary, fontSize = 11.sp
                )
                Slider(
                    value = displayFraction,
                    onValueChange = { frac -> isDragging = true; dragFraction = frac },
                    onValueChangeFinished = {
                        PlayerController.seekTo((dragFraction * safeDuration).toLong())
                        isDragging = false
                    },
                    colors = SliderDefaults.colors(
                        thumbColor = lc.accent,
                        activeTrackColor = lc.accent,
                        inactiveTrackColor = lc.divider
                    ),
                    modifier = Modifier.weight(1f).padding(horizontal = 10.dp).height(20.dp)
                )
                Text(formatMs(durationMs), color = lc.textTertiary, fontSize = 11.sp)
            }
        }

        Spacer(Modifier.width(16.dp))

        // ── Справа: shuffle / repeat / favorite / queue ──
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            CircleIconButton(
                Icons.Rounded.Shuffle, "Shuffle", 34.dp, 18.dp,
                if (shuffleEnabled) lc.accent else lc.iconMuted
            ) { PlayerController.toggleShuffle() }
            CircleIconButton(
                LiquidGlyphs.Repeat, "Repeat", 34.dp, 18.dp,
                if (repeatMode != 0) lc.accent else lc.iconMuted
            ) { PlayerController.cycleRepeatMode() }
            CircleIconButton(
                LiquidGlyphs.Heart, "Favorite", 34.dp, 18.dp,
                if (isFavorite) lc.accentRed else lc.iconMuted
            ) { PlayerController.toggleFavorite(cur.id) }
            CircleIconButton(LiquidGlyphs.QueueMusic, "Queue", 34.dp, 18.dp, lc.iconMuted) {
                onQueueClick()
            }
        }
    }
}

@Composable
private fun CircleIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    box: androidx.compose.ui.unit.Dp,
    glyph: androidx.compose.ui.unit.Dp,
    tint: Color,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(box)
            .clip(CircleShape)
            .liquidClickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription, tint = tint, modifier = Modifier.size(glyph))
    }
}

private fun formatMs(ms: Long): String {
    val safe = ms.coerceAtLeast(0L)
    val totalSec = safe / 1000
    return "%d:%02d".format(totalSec / 60, totalSec % 60)
}
