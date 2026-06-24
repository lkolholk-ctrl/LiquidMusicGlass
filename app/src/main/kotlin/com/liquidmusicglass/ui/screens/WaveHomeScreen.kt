package com.liquidmusicglass.ui.screens

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.liquidmusicglass.api.icm.IcmChart
import com.liquidmusicglass.api.icm.IcmHomeItem
import com.liquidmusicglass.api.icm.IcmRepository
import com.liquidmusicglass.api.icm.toTrack
import com.liquidmusicglass.engine.PlayerController
import com.liquidmusicglass.engine.Track
import com.liquidmusicglass.ui.glass.AlbumArtImage
import com.liquidmusicglass.ui.glass.rememberAlbumColors
import com.liquidmusicglass.ui.player.AuraBackground
import com.liquidmusicglass.ui.theme.AppFontFamily
import com.liquidmusicglass.ui.viewmodel.HomeViewModel
import kotlinx.coroutines.launch

/**
 * "My Wave" — the main screen, our own take on the Yandex-Music style feed.
 *
 * It is a single scrollable feed:
 *  - a wave hero (idle: big title + Play; playing: artist, cover and flat controls);
 *  - a row of animated mood tiles (patterned, color-shifting);
 *  - content sections reusing [HomeViewModel] data (recently played, charts,
 *    new releases and recommendations).
 *
 * Glass is intentionally avoided (heavy blur lags on devices) — controls are flat
 * and the background is a single animated aura Canvas. Tapping the cover or the
 * title panel opens the full-screen player via [onOpenPlayer].
 */
@Composable
fun WaveHomeScreen(
    onNavigateToSearch: () -> Unit = {},
    onOpenPlayer: () -> Unit = {},
    onNavigateToAlbum: (String) -> Unit = {},
    onNavigateToArtist: (String) -> Unit = {},
    onNavigateToPlaylist: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val viewModel = remember { HomeViewModel() }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    LaunchedEffect(Unit) { viewModel.loadHomeContent() }

    val currentTrack by PlayerController.currentTrack.collectAsState()
    val isPlaying by PlayerController.isPlaying.collectAsState()
    val favoriteIds by PlayerController.favoriteIds.collectAsState()
    val isBuildingWave by viewModel.isBuildingWave.collectAsState()
    val recentlyPlayed by PlayerController.recentlyPlayed.collectAsState()
    val homeContent by viewModel.homeContent.collectAsState()
    val charts by viewModel.charts.collectAsState()

    val albumColors = rememberAlbumColors(currentTrack?.displayArtUri, currentTrack?.coverUrl)

    val track = currentTrack
    val isFavorite = track?.id?.let { favoriteIds.contains(it) } == true

    // Все смысловые блоки home-контента (popular / banners / new_releases /
    // recommendations …). Чарты идут отдельной секцией из viewModel.charts.
    val homeBlocks = remember(homeContent) {
        homeContent?.blocks?.filter { it.type != "charts" && it.items.isNotEmpty() } ?: emptyList()
    }

    Box(modifier = Modifier.fillMaxSize()) {

        // ── Living aura background (own AGSL shader, reacts to the music) ──
        AuraBackground(albumColors = albumColors, modifier = Modifier.fillMaxSize())

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding(),
            contentPadding = PaddingValues(bottom = 96.dp)
        ) {
            item { WaveTopBar(onSearch = onNavigateToSearch) }

            // ── Hero ──
            item {
                if (track == null) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 48.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "My Wave",
                            color = Color.White,
                            fontSize = 46.sp,
                            fontWeight = FontWeight.Black,
                            fontFamily = AppFontFamily,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(40.dp))
                        BigPlayButton(
                            loading = isBuildingWave,
                            onClick = { viewModel.buildWaveQueue(context) }
                        )
                    }
                } else {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Spacer(Modifier.height(24.dp))
                        Text(
                            text = track.artist,
                            color = Color.White,
                            fontSize = 40.sp,
                            fontWeight = FontWeight.Black,
                            fontFamily = AppFontFamily,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 24.dp)
                        )
                        Spacer(Modifier.height(24.dp))
                        AlbumArtImage(
                            uri = track.displayArtUri,
                            coverUrl = track.coverUrl,
                            albumId = track.albumId,
                            contentDescription = track.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(216.dp)
                                .clip(RoundedCornerShape(28.dp))
                                .clickable { onOpenPlayer() }
                        )
                        Spacer(Modifier.height(20.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 24.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            FlatCircleButton(onClick = { PlayerController.togglePlayPause(context) }) {
                                Icon(
                                    imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                    contentDescription = if (isPlaying) "Pause" else "Play",
                                    tint = Color.White,
                                    modifier = Modifier.size(26.dp)
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(56.dp)
                                    .clip(RoundedCornerShape(28.dp))
                                    .background(Color.White.copy(alpha = 0.12f))
                                    .clickable { onOpenPlayer() }
                                    .padding(horizontal = 16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = track.title,
                                    color = Color.White,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    fontFamily = AppFontFamily,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    textAlign = TextAlign.Center
                                )
                            }
                            FlatCircleButton(onClick = { PlayerController.toggleFavorite(track.id) }) {
                                Icon(
                                    imageVector = Icons.Rounded.FavoriteBorder,
                                    contentDescription = "Like",
                                    tint = if (isFavorite) Color(0xFFFF4D67) else Color.White,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                    }
                }
            }

            // ── Animated mood tiles ──
            item {
                Spacer(Modifier.height(8.dp))
                WaveMoodTiles(
                    onSelect = { mood -> viewModel.buildMoodWave(context, mood.query) }
                )
                Spacer(Modifier.height(8.dp))
            }

            // ── Recently played ──
            if (recentlyPlayed.isNotEmpty()) {
                item {
                    WaveSectionHeader("Recently played")
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        items(
                            recentlyPlayed.take(15).distinctBy { it.id },
                            key = { "recent_${it.id}" }
                        ) { recent ->
                            WaveTrackCard(
                                title = recent.title,
                                subtitle = recent.artist,
                                uri = recent.displayArtUri,
                                coverUrl = recent.coverUrl,
                                onClick = { PlayerController.playNext(recent, context) }
                            )
                        }
                    }
                    Spacer(Modifier.height(28.dp))
                }
            }

            // ── Home-блоки (popular / banners / new_releases / recommendations …) ──
            homeBlocks.forEach { block ->
                item(key = "block_${block.id}") {
                    WaveSectionHeader(block.title)
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        items(block.items, key = { "${block.id}_${it.id}" }) { homeItem ->
                            WaveTrackCard(
                                title = homeItem.title,
                                subtitle = homeItem.displayArtist,
                                coverUrl = homeItem.cover,
                                onClick = {
                                    // Есть альбом → открываем альбом; иначе играем трек.
                                    val cid = homeItem.collectionId
                                    if (!cid.isNullOrBlank()) {
                                        onNavigateToAlbum(cid)
                                    } else {
                                        val t = homeItem.toWaveTrack()
                                        scope.launch {
                                            resolveStreamUrl(t)?.let { PlayerController.playNext(it, context) }
                                        }
                                    }
                                }
                            )
                        }
                    }
                    Spacer(Modifier.height(28.dp))
                }
            }

            // ── Charts ──
            if (charts.isNotEmpty()) {
                item {
                    WaveSectionHeader("Charts")
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        items(charts, key = { "chart_${it.id}" }) { chart ->
                            WaveChartCard(
                                chart = chart,
                                onClick = {
                                    val chartTracks = chart.tracks.map { it.toTrack() }
                                    if (chartTracks.isNotEmpty()) {
                                        PlayerController.playFromList(
                                            context = context,
                                            tracks = chartTracks,
                                            startIndex = 0,
                                            autoRefillType = "chart",
                                            autoRefillId = chart.id,
                                            autoRefillName = chart.name
                                        )
                                    }
                                }
                            )
                        }
                    }
                    Spacer(Modifier.height(28.dp))
                }
            }
        }
    }
}

private fun IcmHomeItem.toWaveTrack(): Track = Track(
    id = id,
    title = title,
    artist = displayArtist,
    albumName = album ?: "",
    uri = Uri.parse("https://byicloud.online/track/$id"),
    durationMs = durationMs,
    albumId = collectionId?.hashCode()?.toLong() ?: -1L,
    coverUrl = cover
)

private suspend fun resolveStreamUrl(track: Track): Track? = try {
    val url = IcmRepository.getStreamUrl(track.id, source = track.source)
    if (url != null) track.copy(uri = Uri.parse(url)) else null
} catch (_: Exception) {
    null
}

@Composable
private fun WaveSectionHeader(title: String) {
    Text(
        text = title,
        color = Color.White,
        fontSize = 22.sp,
        fontWeight = FontWeight.Bold,
        fontFamily = AppFontFamily,
        modifier = Modifier
            .padding(horizontal = 20.dp)
            .padding(bottom = 12.dp)
    )
}

@Composable
private fun WaveTrackCard(
    title: String,
    subtitle: String,
    coverUrl: String?,
    uri: Uri? = null,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .width(140.dp)
            .clickable(onClick = onClick)
    ) {
        if (uri != null) {
            AlbumArtImage(
                uri = uri,
                coverUrl = coverUrl,
                modifier = Modifier
                    .size(140.dp)
                    .clip(RoundedCornerShape(12.dp))
            )
        } else {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(coverUrl)
                    .crossfade(true)
                    .build(),
                contentDescription = title,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(140.dp)
                    .clip(RoundedCornerShape(12.dp))
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = title,
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = AppFontFamily,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = subtitle,
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 12.sp,
            fontFamily = AppFontFamily,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun WaveChartCard(
    chart: IcmChart,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .width(160.dp)
            .clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .size(160.dp)
                .clip(RoundedCornerShape(12.dp))
        ) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(chart.cover)
                    .crossfade(true)
                    .build(),
                contentDescription = chart.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f)),
                            startY = 80f
                        )
                    )
            )
            Text(
                text = chart.name,
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = AppFontFamily,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(12.dp)
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = "${chart.tracks.size} tracks",
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 12.sp,
            fontFamily = AppFontFamily,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun FlatCircleButton(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .size(56.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.12f))
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

@Composable
private fun WaveTopBar(onSearch: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(horizontal = 16.dp)
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(
                    Brush.linearGradient(listOf(Color(0xFFFF2D9B), Color(0xFFB14BFF)))
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.PlayArrow,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(22.dp)
            )
        }

        Text(
            text = "My Wave",
            color = WaveAccent,
            fontSize = 18.sp,
            fontWeight = FontWeight.Black,
            fontFamily = AppFontFamily,
            modifier = Modifier.align(Alignment.Center)
        )

        Icon(
            imageVector = Icons.Rounded.Search,
            contentDescription = "Search",
            tint = Color.White,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .size(26.dp)
                .clickable { onSearch() }
        )
    }
}

@Composable
private fun BigPlayButton(loading: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(132.dp)
            .clickable(enabled = !loading) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        if (loading) {
            CircularProgressIndicator(
                color = WaveAccent,
                strokeWidth = 3.dp,
                modifier = Modifier.size(48.dp)
            )
        } else {
            // Просто большой треугольник, без круга/подложки
            Icon(
                imageVector = Icons.Rounded.PlayArrow,
                contentDescription = "Listen",
                tint = WaveAccent,
                modifier = Modifier.size(124.dp)
            )
        }
    }
}

private val WaveAccent = Color(0xFFFFE000)
