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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.ThumbDown
import androidx.compose.material.icons.rounded.ThumbUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
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

// Mood categories with gradient colors (like Apple Music screenshot)
private data class MoodCategory(
    val id: String,
    val title: String,
    val gradientColors: List<Color>,
    val icon: String // Simple unicode/icon identifier
)

private val moodCategories = listOf(
    MoodCategory("melancholy", "Меланхолия", listOf(Color(0xFF1E3A5F), Color(0xFF2D5A87)), "🌊"),
    MoodCategory("good_mood", "Хорошее настроение", listOf(Color(0xFFD4730E), Color(0xFFF5A623)), "✦"),
    MoodCategory("broken_heart", "Для разбитых сердец", listOf(Color(0xFF8B1538), Color(0xFFC41E3A)), "💔"),
    MoodCategory("focus", "Концентрация", listOf(Color(0xFF2D5016), Color(0xFF4A7C23)), "◎"),
    MoodCategory("energy", "Энергия", listOf(Color(0xFF8B4513), Color(0xFFD2691E)), "⚡"),
    MoodCategory("night", "Ночная волна", listOf(Color(0xFF1A1A2E), Color(0xFF16213E)), "🌙"),
    MoodCategory("workout", "Тренировка", listOf(Color(0xFF4A0000), Color(0xFF8B0000)), "💪"),
    MoodCategory("chill", "Чилл", listOf(Color(0xFF483D8B), Color(0xFF6A5ACD)), "☁"),
)

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

    // Wave state - active mood station
    var activeMoodId by remember { mutableStateOf<String?>(null) }
    var moodTracks by remember { mutableStateOf<Map<String, List<IcmWaveTrack>>>(emptyMap()) }
    var moodLoading by remember { mutableStateOf<Set<String>>(emptySet()) }
    var isPlayingMood by remember { mutableStateOf(false) }

    fun waveTrackToTrack(waveTrack: IcmWaveTrack): Track {
        return Track(
            id = waveTrack.id,
            title = waveTrack.title,
            artist = waveTrack.artist ?: "Unknown Artist",
            albumName = "",
            durationMs = waveTrack.durationMs,
            uri = Uri.parse("https://byicloud.online/track/${waveTrack.id}"),
            coverUrl = waveTrack.cover,
            albumId = waveTrack.collectionId?.hashCode()?.toLong() ?: -1L
        )
    }

    fun loadMoreMoodTracks(moodId: String, existing: List<IcmWaveTrack>) {
        if (moodId in moodLoading) return
        moodLoading = moodLoading + moodId
        scope.launch {
            val waveTracks = existing.toMutableList()
            repeat(5) {
                val exclude = waveTracks.map { it.id }
                val response = IcmRepository.getWaveNext(
                    exclude = exclude.takeIf { it.isNotEmpty() },
                    recentSkips = 0
                )
                if (response != null && response.status == "ok" && response.track != null) {
                    waveTracks.add(response.track)
                }
            }
            moodTracks = moodTracks + (moodId to waveTracks)
            // Append new tracks to player queue
            val newTracks = waveTracks.drop(existing.size).map { waveTrackToTrack(it) }
            newTracks.forEach { PlayerController.addToQueue(it) }
            moodLoading = moodLoading - moodId
        }
    }

    fun playMoodStation(moodId: String) {
        PlayerController.setAutoRefillContext("wave", moodId, moodCategories.find { it.id == moodId }?.title)
        val existing = moodTracks[moodId]
        if (!existing.isNullOrEmpty()) {
            // Already loaded — start playing immediately
            activeMoodId = moodId
            isPlayingMood = true
            scope.launch {
                val tracks = existing.map { waveTrackToTrack(it) }
                // Resolve first track URL immediately for fast start
                val firstResolved = resolveTrackUrl(tracks.firstOrNull())
                if (firstResolved != null) {
                    val resolvedTracks = tracks.toMutableList()
                    resolvedTracks[0] = firstResolved
                    PlayerController.setQueue(resolvedTracks)
                    PlayerController.playTrack(context, 0)
                }
                // Preload more in background
                loadMoreMoodTracks(moodId, existing)
            }
            return
        }

        // Need to load first
        activeMoodId = moodId
        isPlayingMood = true
        moodLoading = moodLoading + moodId
        scope.launch {
            val waveTracks = mutableListOf<IcmWaveTrack>()
            repeat(5) {
                val exclude = waveTracks.map { it.id }
                val response = IcmRepository.getWaveNext(
                    exclude = exclude.takeIf { it.isNotEmpty() },
                    recentSkips = 0
                )
                if (response != null && response.status == "ok" && response.track != null) {
                    waveTracks.add(response.track)
                }
            }
            moodTracks = moodTracks + (moodId to waveTracks)
            moodLoading = moodLoading - moodId

            if (waveTracks.isNotEmpty()) {
                val tracks = waveTracks.map { waveTrackToTrack(it) }
                // Resolve first track URL immediately for fast start
                val firstResolved = resolveTrackUrl(tracks.firstOrNull())
                if (firstResolved != null) {
                    val resolvedTracks = tracks.toMutableList()
                    resolvedTracks[0] = firstResolved
                    PlayerController.setQueue(resolvedTracks)
                    PlayerController.playTrack(context, 0)
                }
                // Preload next batch
                loadMoreMoodTracks(moodId, waveTracks)
            }
        }
    }

    private suspend fun resolveTrackUrl(track: Track?): Track? {
        if (track == null) return null
        return try {
            val url = IcmRepository.getStreamUrl(track.id)
            if (url != null) {
                track.copy(uri = Uri.parse(url))
            } else {
                track
            }
        } catch (_: Exception) {
            track
        }
    }

    fun sendWaveFeedback(feedbackType: String, value: String) {
        scope.launch { IcmRepository.sendWaveFeedback(feedbackType, value) }
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

            // ─── My Wave - Mood Categories ───
            SectionHeader(title = "Под настроение")
            Spacer(modifier = Modifier.height(12.dp))

            LazyRow(
                contentPadding = PaddingValues(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(moodCategories, key = { it.id }) { mood ->
                    val isLoading = mood.id in moodLoading && moodTracks[mood.id].isNullOrEmpty()
                    MoodCard(
                        mood = mood,
                        isActive = activeMoodId == mood.id,
                        isLoading = isLoading,
                        onClick = {
                            if (activeMoodId == mood.id) {
                                // Stop / collapse
                                activeMoodId = null
                                isPlayingMood = false
                                PlayerController.clearAutoRefillContext()
                            } else {
                                playMoodStation(mood.id)
                            }
                        }
                    )
                }
            }

            // Playing indicator
            if (isPlayingMood && activeMoodId != null) {
                Spacer(modifier = Modifier.height(12.dp))
                val moodTitle = moodCategories.find { it.id == activeMoodId }?.title ?: ""
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Color(0xFF1C1C1E))
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(AppleRed, RoundedCornerShape(50))
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Playing: $moodTitle",
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
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
private fun MoodCard(
    mood: MoodCategory,
    isActive: Boolean,
    isLoading: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .width(160.dp)
            .height(100.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(
                brush = Brush.linearGradient(
                    colors = mood.gradientColors,
                    start = androidx.compose.ui.geometry.Offset(0f, 0f),
                    end = androidx.compose.ui.geometry.Offset(160f, 100f)
                )
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(12.dp)
    ) {
        // Decorative icon (top-right)
        Text(
            text = mood.icon,
            fontSize = 24.sp,
            modifier = Modifier.align(Alignment.TopEnd),
            color = Color.White.copy(alpha = 0.6f)
        )

        // Title (bottom-left)
        Text(
            text = mood.title,
            color = Color.White,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.BottomStart)
        )

        // Active / loading indicator
        Box(
            modifier = Modifier.align(Alignment.BottomEnd)
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    color = Color.White,
                    strokeWidth = 2.dp
                )
            } else if (isActive) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(AppleRed, RoundedCornerShape(50))
                )
            }
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
