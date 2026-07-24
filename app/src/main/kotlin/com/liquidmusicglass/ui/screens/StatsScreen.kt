package com.liquidmusicglass.ui.screens

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.liquidmusicglass.data.local.db.AppDatabase
import com.liquidmusicglass.data.local.db.ArtistPlayStat
import com.liquidmusicglass.data.local.db.TrackPlayStat
import com.liquidmusicglass.engine.PlayerController
import com.liquidmusicglass.engine.Track
import com.liquidmusicglass.ui.glass.liquidClickable
import com.liquidmusicglass.ui.theme.LiquidMotion
import com.liquidmusicglass.ui.theme.LiquidTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Снимок агрегированной статистики прослушивания. */
private data class StatsData(
    val totalMs: Long = 0L,
    val plays: Int = 0,
    val tracks: Int = 0,
    val artists: Int = 0,
    val topTracks: List<TrackPlayStat> = emptyList(),
    val topArtists: List<ArtistPlayStat> = emptyList()
)

/**
 * Экран «Статистика прослушивания» — в духе Apple Music Replay.
 * Считает из Room (listening_history): суммарное время, треки, артисты,
 * топ-треки и топ-артисты. Тап по треку — играет его.
 */
@Composable
fun StatsScreen(
    onBack: () -> Unit = {}
) {
    val context = LocalContext.current
    val lc = LiquidTheme.colors

    val win = com.liquidmusicglass.ui.rememberWindowInfo()
    val compact = win.useSideBySide
    val sidePad = if (compact) 24.dp else 20.dp

    var loading by remember { mutableStateOf(true) }
    var data by remember { mutableStateOf(StatsData()) }

    LaunchedEffect(Unit) {
        val dao = AppDatabase.getInstance(context).playbackHistoryDao()
        data = withContext(Dispatchers.IO) {
            StatsData(
                totalMs = dao.getTotalListenedMs(),
                plays = dao.getTotalPlayEvents(),
                tracks = dao.getDistinctTrackCount(),
                artists = dao.getDistinctArtistCount(),
                topTracks = dao.getTopTracksDetailed(10),
                topArtists = dao.getTopArtistsDetailed(8)
            )
        }
        loading = false
    }

    val empty = !loading && data.plays == 0

    Box(modifier = Modifier.fillMaxSize().background(lc.settingsBackground)) {
        Column(modifier = Modifier.fillMaxSize()) {
            Spacer(modifier = Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
            Spacer(modifier = Modifier.height(12.dp))

            // Header
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(if (compact) 34.dp else 40.dp)
                        .clip(CircleShape)
                        .background(if (lc.isDark) Color(0xFF1C1C1E) else Color(0xFFF2F2F7))
                        .liquidClickable(pressedScale = LiquidMotion.PressIcon) { onBack() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = null,
                        tint = lc.iconDefault,
                        modifier = Modifier.size(if (compact) 18.dp else 22.dp)
                    )
                }
                Spacer(modifier = Modifier.width(if (compact) 12.dp else 16.dp))
                Text(
                    text = "Listening Stats",
                    color = lc.textPrimary,
                    fontSize = if (compact) 20.sp else 24.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (empty) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "Play some music to see your stats",
                        color = lc.textSecondary,
                        fontSize = 15.sp
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = sidePad, end = sidePad, bottom = 96.dp)
                ) {
                    // ── Сводка: три «плитки» ──
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            SummaryTile(
                                value = formatDuration(data.totalMs),
                                label = "Listened",
                                compact = compact,
                                modifier = Modifier.weight(1f)
                            )
                            SummaryTile(
                                value = data.tracks.toString(),
                                label = "Tracks",
                                compact = compact,
                                modifier = Modifier.weight(1f)
                            )
                            SummaryTile(
                                value = data.artists.toString(),
                                label = "Artists",
                                compact = compact,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Spacer(modifier = Modifier.height(24.dp))
                    }

                    // ── Top tracks ──
                    if (data.topTracks.isNotEmpty()) {
                        item {
                            SectionHeader("Top Songs", compact)
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                        itemsIndexed(data.topTracks, key = { _, t -> t.trackId }) { i, t ->
                            TopTrackRow(rank = i + 1, stat = t, compact = compact) {
                                playStatTrack(context, t)
                            }
                        }
                        item { Spacer(modifier = Modifier.height(24.dp)) }
                    }

                    // ── Top artists ──
                    if (data.topArtists.isNotEmpty()) {
                        item {
                            SectionHeader("Top Artists", compact)
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                        itemsIndexed(data.topArtists, key = { _, a -> a.artist }) { i, a ->
                            TopArtistRow(rank = i + 1, stat = a, compact = compact)
                        }
                    }
                }
            }
        }
    }
}

private fun playStatTrack(context: android.content.Context, stat: TrackPlayStat) {
    val track = Track(
        id = stat.trackId,
        title = stat.title,
        artist = stat.artist,
        albumName = "",
        uri = Uri.parse("https://byicloud.online/track/${stat.trackId}"),
        durationMs = 0L,
        albumId = -1L,
        coverUrl = null
    )
    PlayerController.playFromList(context, listOf(track), 0)
}

@Composable
private fun SummaryTile(
    value: String,
    label: String,
    compact: Boolean,
    modifier: Modifier = Modifier
) {
    val lc = LiquidTheme.colors
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(if (lc.isDark) Color(0xFF1C1C1E) else Color(0xFFF2F2F7))
            .padding(vertical = if (compact) 14.dp else 18.dp, horizontal = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = value,
            color = lc.accent,
            fontSize = if (compact) 18.sp else 22.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            color = lc.textSecondary,
            fontSize = if (compact) 11.sp else 12.sp
        )
    }
}

@Composable
private fun SectionHeader(title: String, compact: Boolean) {
    Text(
        text = title,
        color = LiquidTheme.colors.textPrimary,
        fontSize = if (compact) 16.sp else 19.sp,
        fontWeight = FontWeight.Bold
    )
}

@Composable
private fun TopTrackRow(rank: Int, stat: TrackPlayStat, compact: Boolean, onClick: () -> Unit) {
    val lc = LiquidTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .liquidClickable(onClick = onClick)
            .padding(vertical = if (compact) 6.dp else 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = rank.toString(),
            color = lc.textTertiary,
            fontSize = if (compact) 15.sp else 18.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(if (compact) 24.dp else 30.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stat.title,
                color = lc.textPrimary,
                fontSize = if (compact) 14.sp else 16.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = stat.artist,
                color = lc.textSecondary,
                fontSize = if (compact) 12.sp else 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "${stat.plays}×",
            color = lc.accent,
            fontSize = if (compact) 12.sp else 14.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun TopArtistRow(rank: Int, stat: ArtistPlayStat, compact: Boolean) {
    val lc = LiquidTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = if (compact) 6.dp else 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = rank.toString(),
            color = lc.textTertiary,
            fontSize = if (compact) 15.sp else 18.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(if (compact) 24.dp else 30.dp)
        )
        Text(
            text = stat.artist,
            color = lc.textPrimary,
            fontSize = if (compact) 14.sp else 16.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = formatDuration(stat.listenedMs),
            color = lc.textSecondary,
            fontSize = if (compact) 12.sp else 13.sp
        )
    }
}

/** «2h 14m» / «43m» / «12s» из миллисекунд. */
private fun formatDuration(ms: Long): String {
    if (ms <= 0L) return "0m"
    val totalMin = ms / 60_000L
    val hours = totalMin / 60L
    val min = totalMin % 60L
    return when {
        hours > 0L -> "${hours}h ${min}m"
        totalMin > 0L -> "${min}m"
        else -> "${ms / 1000L}s"
    }
}
