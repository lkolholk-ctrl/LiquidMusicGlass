package com.liquidmusicglass.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.Capsule
import com.liquidmusicglass.engine.ListeningStats
import com.liquidmusicglass.ui.theme.LiquidTheme

private val AppleRed = Color(0xFFFC3C44)

@Composable
fun StatsScreen(onBack: () -> Unit) {
    val backdrop = rememberLayerBackdrop()
    val lc = LiquidTheme.colors

    val totalPlays by ListeningStats.totalPlayCount.collectAsState()
    val totalTime by ListeningStats.totalListenTimeMs.collectAsState()
    val topTracks by ListeningStats.topTracks.collectAsState()
    val topArtists by ListeningStats.topArtists.collectAsState()
    val dailyStats by ListeningStats.dailyStats.collectAsState()

    Box(modifier = Modifier.fillMaxSize().background(lc.screenBackground)) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp)
    ) {
        // ── Header ──
        item {
            Spacer(modifier = Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
            Spacer(modifier = Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                GlassCircle(backdrop, lc) { onBack() }
                Spacer(Modifier.width(16.dp))
                Text("Statistics", color = lc.textPrimary, fontSize = 24.sp,
                    fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(24.dp))
        }

        // ── Overview Cards ──
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                StatCard(
                    icon = Icons.Rounded.PlayArrow,
                    value = "$totalPlays",
                    label = "Plays",
                    backdrop = backdrop, lc = lc,
                    modifier = Modifier.weight(1f)
                )
                StatCard(
                    icon = Icons.Rounded.Schedule,
                    value = ListeningStats.formatTime(totalTime),
                    label = "Listened",
                    backdrop = backdrop, lc = lc,
                    modifier = Modifier.weight(1f)
                )
                StatCard(
                    icon = Icons.Rounded.MusicNote,
                    value = "${topTracks.size}",
                    label = "Tracks",
                    backdrop = backdrop, lc = lc,
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(24.dp))
        }

        // ── Daily Activity Chart ──
        if (dailyStats.isNotEmpty()) {
            item {
                SectionLabel("ACTIVITY", lc)
                Spacer(Modifier.height(10.dp))
                GlassCard(backdrop, lc) {
                    DailyChart(
                        days = dailyStats.reversed().takeLast(14),
                        modifier = Modifier.fillMaxWidth().height(100.dp).padding(vertical = 8.dp)
                    )
                }
                Spacer(Modifier.height(24.dp))
            }
        }

        // ── Top Tracks ──
        if (topTracks.isNotEmpty()) {
            item {
                SectionLabel("TOP TRACKS", lc)
                Spacer(Modifier.height(10.dp))
            }
            itemsIndexed(topTracks.take(10), key = { _, t -> t.trackId }) { index, track ->
                TrackStatRow(
                    index = index + 1,
                    title = track.title,
                    artist = track.artist,
                    plays = track.playCount,
                    time = ListeningStats.formatTimeShort(track.totalTimeMs),
                    backdrop = backdrop, lc = lc
                )
                Spacer(Modifier.height(6.dp))
            }
            item { Spacer(Modifier.height(18.dp)) }
        }

        // ── Top Artists ──
        if (topArtists.isNotEmpty()) {
            item {
                SectionLabel("TOP ARTISTS", lc)
                Spacer(Modifier.height(10.dp))
            }
            itemsIndexed(topArtists.take(10), key = { _, a -> a.artist }) { index, artist ->
                ArtistStatRow(
                    index = index + 1,
                    artist = artist.artist,
                    plays = artist.playCount,
                    tracks = artist.trackCount,
                    time = ListeningStats.formatTimeShort(artist.totalTimeMs),
                    backdrop = backdrop, lc = lc
                )
                Spacer(Modifier.height(6.dp))
            }
        }

        // ── Empty state ──
        if (topTracks.isEmpty()) {
            item {
                Spacer(Modifier.height(80.dp))
                Text(
                    "No listening data yet.\nPlay some music!",
                    color = lc.textTertiary,
                    fontSize = 16.sp,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }

        item { Spacer(Modifier.height(200.dp)) }
    }
    } // Box
}

// ═══════════════════════════════════════════════════════════
//  Stat Overview Card
// ═══════════════════════════════════════════════════════════

@Composable
private fun StatCard(
    icon: ImageVector,
    value: String,
    label: String,
    backdrop: LayerBackdrop,
    lc: com.liquidmusicglass.ui.theme.LiquidColors,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(22.dp)
    Box(
        modifier = modifier
            .height(100.dp)
            .drawBackdrop(
                backdrop = backdrop,
                shape = { shape },
                effects = {
                    vibrancy(); blur(4.dp.toPx())
                    lens(22.dp.toPx(), 30.dp.toPx(), chromaticAberration = true)
                },
                highlight = { Highlight.Ambient },
                shadow = { Shadow(radius = 8.dp, color = Color.Black.copy(alpha = 0.15f)) },
                innerShadow = { InnerShadow(radius = 4.dp, alpha = 0.3f) },
                onDrawSurface = {
                    drawRect(Color.White.copy(alpha = 0.03f))
                    drawRect(Color.White.copy(alpha = 0.20f), style = Stroke(width = 1.dp.toPx()))
                }
            )
            .padding(14.dp),
        contentAlignment = Alignment.TopStart
    ) {
        Column {
            Icon(icon, null, tint = AppleRed, modifier = Modifier.size(22.dp))
            Spacer(Modifier.weight(1f))
            Text(value, color = lc.textPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(label, color = lc.textTertiary, fontSize = 12.sp)
        }
    }
}

// ═══════════════════════════════════════════════════════════
//  Daily Activity Bar Chart
// ═══════════════════════════════════════════════════════════

@Composable
private fun DailyChart(
    days: List<ListeningStats.DayStat>,
    modifier: Modifier = Modifier
) {
    val maxPlays = (days.maxOfOrNull { it.playCount } ?: 1).coerceAtLeast(1)

    Canvas(modifier = modifier) {
        val barCount = days.size
        if (barCount == 0) return@Canvas
        val barWidth = (size.width - (barCount - 1) * 4.dp.toPx()) / barCount
        val maxHeight = size.height

        days.forEachIndexed { i, day ->
            val fraction = day.playCount.toFloat() / maxPlays
            val barH = (fraction * maxHeight * 0.85f).coerceAtLeast(4.dp.toPx())
            val x = i * (barWidth + 4.dp.toPx())
            val y = maxHeight - barH

            drawRoundRect(
                color = AppleRed.copy(alpha = 0.2f + fraction * 0.6f),
                topLeft = Offset(x, y),
                size = Size(barWidth, barH),
                cornerRadius = CornerRadius(4.dp.toPx())
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════
//  Track Stat Row
// ═══════════════════════════════════════════════════════════

@Composable
private fun TrackStatRow(
    index: Int,
    title: String,
    artist: String,
    plays: Int,
    time: String,
    backdrop: LayerBackdrop,
    lc: com.liquidmusicglass.ui.theme.LiquidColors
) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .drawBackdrop(
                backdrop = backdrop,
                shape = { shape },
                effects = { vibrancy(); blur(4.dp.toPx()) },
                highlight = { Highlight.Ambient.copy(alpha = 0.3f) },
                onDrawSurface = {
                    drawRect(Color.White.copy(alpha = 0.03f))
                    drawRect(Color.White.copy(alpha = 0.22f), style = Stroke(width = 1.dp.toPx()))
                }
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Rank
        Text(
            text = "#$index",
            color = if (index <= 3) AppleRed else lc.textTertiary,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(36.dp)
        )

        // Info
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = lc.textPrimary, fontSize = 15.sp,
                fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(artist, color = lc.textSecondary, fontSize = 13.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }

        // Stats
        Column(horizontalAlignment = Alignment.End) {
            Text("$plays plays", color = lc.textSecondary, fontSize = 12.sp)
            Text(time, color = lc.textTertiary, fontSize = 11.sp)
        }
    }
}

// ═══════════════════════════════════════════════════════════
//  Artist Stat Row
// ═══════════════════════════════════════════════════════════

@Composable
private fun ArtistStatRow(
    index: Int,
    artist: String,
    plays: Int,
    tracks: Int,
    time: String,
    backdrop: LayerBackdrop,
    lc: com.liquidmusicglass.ui.theme.LiquidColors
) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .drawBackdrop(
                backdrop = backdrop,
                shape = { shape },
                effects = { vibrancy(); blur(4.dp.toPx()) },
                highlight = { Highlight.Ambient.copy(alpha = 0.3f) },
                onDrawSurface = {
                    drawRect(Color.White.copy(alpha = 0.03f))
                    drawRect(Color.White.copy(alpha = 0.22f), style = Stroke(width = 1.dp.toPx()))
                }
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "#$index",
            color = if (index <= 3) AppleRed else lc.textTertiary,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(36.dp)
        )

        // Avatar placeholder
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { Capsule() },
                    effects = { vibrancy(); blur(4.dp.toPx()) },
                    onDrawSurface = { drawRect(Color.White.copy(alpha = 0.06f)) }
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Rounded.Person, null, tint = lc.iconMuted, modifier = Modifier.size(22.dp))
        }

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(artist, color = lc.textPrimary, fontSize = 15.sp,
                fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("$tracks tracks", color = lc.textSecondary, fontSize = 13.sp)
        }

        Column(horizontalAlignment = Alignment.End) {
            Text("$plays plays", color = lc.textSecondary, fontSize = 12.sp)
            Text(time, color = lc.textTertiary, fontSize = 11.sp)
        }
    }
}

// ═══════════════════════════════════════════════════════════
//  Helpers
// ═══════════════════════════════════════════════════════════

@Composable
private fun SectionLabel(text: String, lc: com.liquidmusicglass.ui.theme.LiquidColors) {
    Text(text, color = lc.sectionLabel, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 4.dp))
}

@Composable
private fun GlassCard(
    backdrop: LayerBackdrop,
    lc: com.liquidmusicglass.ui.theme.LiquidColors,
    content: @Composable () -> Unit
) {
    val shape = RoundedCornerShape(22.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .drawBackdrop(
                backdrop = backdrop,
                shape = { shape },
                effects = {
                    vibrancy(); blur(4.dp.toPx())
                    lens(22.dp.toPx(), 30.dp.toPx(), chromaticAberration = true)
                },
                highlight = { Highlight.Ambient },
                shadow = { Shadow(radius = 8.dp, color = Color.Black.copy(alpha = 0.15f)) },
                innerShadow = { InnerShadow(radius = 4.dp, alpha = 0.3f) },
                onDrawSurface = {
                    drawRect(Color.White.copy(alpha = 0.03f))
                    drawRect(Color.White.copy(alpha = 0.20f), style = Stroke(width = 1.dp.toPx()))
                }
            )
            .padding(horizontal = 20.dp, vertical = 12.dp)
    ) { Column { content() } }
}

@Composable
private fun GlassCircle(
    backdrop: LayerBackdrop,
    lc: com.liquidmusicglass.ui.theme.LiquidColors,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .drawBackdrop(
                backdrop = backdrop,
                shape = { Capsule() },
                effects = {
                    vibrancy(); blur(4.dp.toPx())
                    lens(18.dp.toPx(), 24.dp.toPx(), chromaticAberration = true)
                },
                highlight = { Highlight.Ambient },
                shadow = { Shadow(radius = 4.dp, color = Color.Black.copy(alpha = 0.12f)) },
                innerShadow = { InnerShadow(radius = 3.dp, alpha = 0.2f) },
                onDrawSurface = {
                    drawRect(Color.White.copy(alpha = 0.03f))
                    drawRect(Color.White.copy(alpha = 0.22f), style = Stroke(width = 1.dp.toPx()))
                }
            )
            .clip(CircleShape)
            .clickable(remember { MutableInteractionSource() }, null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(Icons.AutoMirrored.Rounded.ArrowBack, null, tint = lc.iconDefault, modifier = Modifier.size(22.dp))
    }
}
