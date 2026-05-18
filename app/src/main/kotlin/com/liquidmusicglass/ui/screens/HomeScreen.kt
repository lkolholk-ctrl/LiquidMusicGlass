package com.liquidmusicglass.ui.screens

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.ThumbDown
import androidx.compose.material.icons.rounded.ThumbUp
import androidx.compose.material.icons.rounded.Waves
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
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
import com.liquidmusicglass.api.icm.IcmRepository
import com.liquidmusicglass.api.icm.IcmWaveTrack
import com.liquidmusicglass.engine.PlayerController
import com.liquidmusicglass.engine.Track
import com.liquidmusicglass.ui.glass.AlbumArtImage
import com.liquidmusicglass.ui.theme.LiquidTheme
import kotlinx.coroutines.launch

private val AppleRed = Color(0xFFFC3C44)

@Composable
fun HomeScreen(
    onNavigateToAlbum: (String) -> Unit = {},
    onNavigateToArtist: (String) -> Unit = {},
    onNavigateToPlaylist: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scroll = rememberScrollState()

    val allTracks by PlayerController.queueFlow.collectAsState()
    val recentlyPlayed by PlayerController.recentlyPlayed.collectAsState()
    val currentTrack by PlayerController.currentTrack.collectAsState()
    val favoriteIds by PlayerController.favoriteIds.collectAsState()
    val favoriteTracks = remember(allTracks, favoriteIds) {
        allTracks.filter { it.id in favoriteIds }
    }

    // Wave state
    var waveTracks by remember { mutableStateOf<List<IcmWaveTrack>>(emptyList()) }
    var waveLoading by remember { mutableStateOf(false) }
    var waveError by remember { mutableStateOf<String?>(null) }

    fun loadWave() {
        if (waveLoading) return
        waveLoading = true
        waveError = null
        scope.launch {
            val exclude = waveTracks.map { it.id }
            val response = IcmRepository.getWaveNext(
                exclude = exclude.takeIf { it.isNotEmpty() },
                recentSkips = 0
            )
            if (response != null && response.status == "ok" && response.track != null) {
                waveTracks = waveTracks + response.track
            } else if (response?.status == "empty") {
                waveError = "empty"
            } else {
                waveError = "Failed to load"
            }
            waveLoading = false
        }
    }

    fun playWaveTrack(waveTrack: IcmWaveTrack) {
        val track = Track(
            id = waveTrack.id,
            title = waveTrack.title,
            artist = waveTrack.artist ?: "Unknown Artist",
            albumName = "",
            durationMs = waveTrack.durationMs,
            uri = Uri.parse("https://byicloud.online/track/${waveTrack.id}"),
            coverUrl = waveTrack.cover,
            albumId = waveTrack.collectionId?.hashCode()?.toLong() ?: -1L
        )
        PlayerController.playNext(track, context)
    }

    fun sendWaveFeedback(feedbackType: String, value: String) {
        scope.launch { IcmRepository.sendWaveFeedback(feedbackType, value) }
    }

    // Load initial wave tracks
    LaunchedEffect(Unit) {
        repeat(5) { loadWave() }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .padding(vertical = 16.dp)
        ) {
            Spacer(modifier = Modifier.windowInsetsTopHeight(WindowInsets.statusBars))

            Text(
                text = "Home",
                fontWeight = FontWeight.Bold,
                fontSize = 32.sp,
                color = LiquidTheme.colors.textPrimary,
                modifier = Modifier.padding(horizontal = 20.dp)
            )

            Spacer(modifier = Modifier.height(28.dp))

            // ─── My Wave ───
            SectionHeader(title = "My Wave")
            Spacer(modifier = Modifier.height(12.dp))

            if (waveTracks.isNotEmpty()) {
                Box(modifier = Modifier.height(210.dp)) {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        items(waveTracks.distinctBy { it.id }, key = { "wave_${it.id}" }) { track ->
                            WaveTrackCard(
                                track = track,
                                onPlay = { playWaveTrack(track) },
                                onLike = { sendWaveFeedback("more_track", track.id) },
                                onDislike = { sendWaveFeedback("less_track", track.id) }
                            )
                        }
                        if (!waveLoading) {
                            item {
                                Box(
                                    modifier = Modifier
                                        .width(130.dp)
                                        .height(210.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(Color(0xFF1C1C1E))
                                        .clickable { loadWave() },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Icon(
                                            Icons.Rounded.Refresh,
                                            null,
                                            tint = AppleRed,
                                            modifier = Modifier.size(32.dp)
                                        )
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text(
                                            "More",
                                            color = AppleRed,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            } else if (waveLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = AppleRed, modifier = Modifier.size(32.dp))
                }
            } else if (waveError != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp)
                        .padding(horizontal = 20.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF1C1C1E))
                        .clickable { loadWave() },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (waveError == "empty") "Select artists to start your wave" else "Tap to retry",
                        color = Color.Gray,
                        fontSize = 14.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            // ─── Recently Played ───
            if (recentlyPlayed.isNotEmpty()) {
                SectionHeader(title = "Recently Played")
                Spacer(modifier = Modifier.height(12.dp))
                Box(modifier = Modifier.height(190.dp)) {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        items(recentlyPlayed.take(15).distinctBy { it.id }, key = { "recent_${it.id}" }) { track ->
                            RecentTrackCard(
                                track = track,
                                onClick = { PlayerController.playNext(track, context) }
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(32.dp))
            }

            // ─── For You ───
            if (allTracks.isNotEmpty()) {
                SectionHeader(title = "For You")
                Spacer(modifier = Modifier.height(12.dp))
                val recs = remember(allTracks) { allTracks.distinctBy { it.id }.shuffled().take(15) }
                Box(modifier = Modifier.height(190.dp)) {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        items(recs, key = { "foryou_${it.id}" }) { track ->
                            RecommendationCard(
                                track = track,
                                onClick = { PlayerController.playNext(track, context) }
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(32.dp))
            }

            // ─── Mixes ───
            if (allTracks.isNotEmpty()) {
                SectionHeader(title = "Mixes")
                Spacer(modifier = Modifier.height(12.dp))
                val artistGroups = remember(allTracks) {
                    allTracks.groupBy { it.artist }
                        .filter { it.value.size >= 2 }
                        .entries
                        .sortedByDescending { it.value.size }
                        .take(8)
                }
                Box(modifier = Modifier.height(220.dp)) {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        items(artistGroups, key = { it.key }) { (artist, tracks) ->
                            ArtistMixCard(
                                artistName = artist,
                                tracks = tracks,
                                onClick = {
                                    PlayerController.setQueue(tracks)
                                    PlayerController.playTrack(context, 0)
                                }
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(32.dp))
            }

            // ─── Favorites ───
            if (favoriteTracks.isNotEmpty()) {
                SectionHeader(title = "Favorites")
                Spacer(modifier = Modifier.height(12.dp))
                Box(modifier = Modifier.height(190.dp)) {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        items(favoriteTracks.take(15).distinctBy { it.id }, key = { "fav_${it.id}" }) { track ->
                            RecentTrackCard(
                                track = track,
                                onClick = { PlayerController.playNext(track, context) }
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(32.dp))
            }

            // ─── Quick Picks ───
            if (allTracks.isNotEmpty()) {
                SectionHeader(title = "Quick Picks")
                Spacer(modifier = Modifier.height(12.dp))
                val quickPicks = remember(allTracks) { allTracks.shuffled().take(6) }
                Column(
                    modifier = Modifier.padding(horizontal = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    quickPicks.forEach { track ->
                        QuickPickRow(
                            track = track,
                            onClick = { PlayerController.playNext(track, context) }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(200.dp))
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        color = LiquidTheme.colors.textPrimary,
        fontSize = 20.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(horizontal = 20.dp)
    )
}

@Composable
private fun WaveTrackCard(
    track: IcmWaveTrack,
    onPlay: () -> Unit,
    onLike: () -> Unit,
    onDislike: () -> Unit
) {
    Column(
        modifier = Modifier
            .width(150.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onPlay
            )
    ) {
        Box(
            modifier = Modifier
                .size(150.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF1A1A1A))
        ) {
            if (!track.cover.isNullOrBlank()) {
                AsyncImage(
                    model = track.cover.replace("1000x1000", "600x600"),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }
            // Gradient overlay
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.7f)
                            ),
                            startY = 80f
                        )
                    )
            )
            // Action buttons at bottom
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                IconButton(
                    onClick = onDislike,
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        Icons.Rounded.ThumbDown,
                        null,
                        tint = Color.White.copy(alpha = 0.8f),
                        modifier = Modifier.size(18.dp)
                    )
                }
                IconButton(
                    onClick = onLike,
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        Icons.Rounded.ThumbUp,
                        null,
                        tint = Color.White.copy(alpha = 0.8f),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = track.title,
            color = LiquidTheme.colors.textPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = track.artist ?: "Unknown Artist",
            color = LiquidTheme.colors.textSecondary,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun RecentTrackCard(
    track: Track,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .width(130.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
    ) {
        Box(
            modifier = Modifier
                .size(130.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF1A1A1A))
        ) {
            AlbumArtImage(
                uri = null,
                coverUrl = track.coverUrl?.replace("1000x1000", "600x600"),
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = track.title,
            color = LiquidTheme.colors.textPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = track.artist,
            color = LiquidTheme.colors.textSecondary,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun RecommendationCard(
    track: Track,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .width(130.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
    ) {
        Box(
            modifier = Modifier
                .size(130.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF1A1A1A))
        ) {
            AlbumArtImage(
                uri = null,
                coverUrl = track.coverUrl?.replace("1000x1000", "600x600"),
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = track.title,
            color = LiquidTheme.colors.textPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = track.artist,
            color = LiquidTheme.colors.textSecondary,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ArtistMixCard(
    artistName: String,
    tracks: List<Track>,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .width(180.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
    ) {
        Box(
            modifier = Modifier
                .size(180.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF1A1A1A))
        ) {
            AlbumArtImage(
                uri = null,
                coverUrl = tracks.firstOrNull()?.coverUrl?.replace("1000x1000", "600x600"),
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.3f)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = artistName,
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${tracks.size} tracks",
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun QuickPickRow(
    track: Track,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF1A1A1A))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF2A2A2A))
        ) {
            AlbumArtImage(
                uri = null,
                coverUrl = track.coverUrl?.replace("1000x1000", "600x600"),
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = track.title,
                color = LiquidTheme.colors.textPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = track.artist,
                color = LiquidTheme.colors.textSecondary,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Icon(
            imageVector = Icons.Rounded.PlayArrow,
            contentDescription = null,
            tint = AppleRed,
            modifier = Modifier.size(20.dp)
        )
    }
}
