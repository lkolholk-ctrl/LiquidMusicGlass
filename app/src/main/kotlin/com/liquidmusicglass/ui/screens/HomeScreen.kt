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
import coil.request.ImageRequest
import com.liquidmusicglass.api.icm.IcmAuthRepository
import com.liquidmusicglass.api.icm.IcmHomeBlock
import com.liquidmusicglass.api.icm.IcmHomeItem
import com.liquidmusicglass.api.icm.IcmRepository
import com.liquidmusicglass.api.icm.IcmWaveTrack
import com.liquidmusicglass.engine.PlayerController
import com.liquidmusicglass.engine.Track
import com.liquidmusicglass.ui.glass.AlbumArtImage
import com.liquidmusicglass.ui.theme.LiquidTheme
import com.liquidmusicglass.ui.viewmodel.HomeViewModel
import kotlinx.coroutines.launch

private val AppleRed = Color(0xFFFC3C44)

private suspend fun resolveWaveTrackUrl(track: Track?): Track? {
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

private fun IcmHomeItem.toTrack(): Track {
    return Track(
        id = id,
        title = title,
        artist = displayArtist,
        albumName = album ?: "",
        uri = Uri.parse("https://byicloud.online/track/$id"),
        durationMs = durationMs,
        albumId = collectionId?.hashCode()?.toLong() ?: -1L,
        coverUrl = cover
    )
}

// Mood categories with gradient colors (like Apple Music screenshot)
private data class MoodCategory(
    val id: String,
    val title: String,
    val gradientColors: List<Color>,
    val icon: String,
    /** Seed queries used to pick a representative track for the wave station. */
    val seedQueries: List<String>
)

private val moodCategories = listOf(
    MoodCategory(
        "melancholy", "Меланхолия",
        listOf(Color(0xFF1E3A5F), Color(0xFF2D5A87)), "🌊",
        listOf("melancholy", "sad indie", "lo-fi sad")
    ),
    MoodCategory(
        "good_mood", "Хорошее настроение",
        listOf(Color(0xFFD4730E), Color(0xFFF5A623)), "✦",
        listOf("happy pop hits", "feel good", "summer hits")
    ),
    MoodCategory(
        "broken_heart", "Для разбитых сердец",
        listOf(Color(0xFF8B1538), Color(0xFFC41E3A)), "💔",
        listOf("breakup songs", "heartbreak", "sad love songs")
    ),
    MoodCategory(
        "focus", "Концентрация",
        listOf(Color(0xFF2D5016), Color(0xFF4A7C23)), "◎",
        listOf("focus instrumental", "deep focus", "study beats")
    ),
    MoodCategory(
        "energy", "Энергия",
        listOf(Color(0xFF8B4513), Color(0xFFD2691E)), "⚡",
        listOf("high energy", "power hits", "edm energy")
    ),
    MoodCategory(
        "night", "Ночная волна",
        listOf(Color(0xFF1A1A2E), Color(0xFF16213E)), "🌙",
        listOf("late night", "night drive", "synthwave night")
    ),
    MoodCategory(
        "workout", "Тренировка",
        listOf(Color(0xFF4A0000), Color(0xFF8B0000)), "💪",
        listOf("workout", "gym motivation", "running mix")
    ),
    MoodCategory(
        "chill", "Чилл",
        listOf(Color(0xFF483D8B), Color(0xFF6A5ACD)), "☁",
        listOf("chillhop", "chill lofi", "ambient chill")
    ),
)

/** Picks a seed track id for a mood, or null if the search returned nothing. */
private suspend fun resolveMoodSeedTrackId(mood: MoodCategory): String? {
    for (query in mood.seedQueries) {
        val tracks = IcmRepository.searchTracks(query, limit = 5)
        if (tracks.isNotEmpty()) return tracks.first().id
    }
    return null
}

/** Returns a list of tracks for a mood when the personal wave is unavailable. */
private suspend fun loadMoodFallbackTracks(mood: MoodCategory, count: Int): List<Track> {
    val collected = mutableListOf<Track>()
    val seen = mutableSetOf<String>()
    for (query in mood.seedQueries) {
        if (collected.size >= count) break
        val tracks = IcmRepository.searchTracks(query, limit = count * 2)
        for (track in tracks) {
            if (collected.size >= count) break
            if (seen.add(track.id)) collected.add(track)
        }
    }
    return collected
}

@Composable
fun HomeScreen(
    onNavigateToAlbum: (String) -> Unit = {},
    onNavigateToArtist: (String) -> Unit = {},
    onNavigateToPlaylist: (String) -> Unit = {}
) {
    val viewModel = remember { HomeViewModel() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scroll = rememberScrollState()

    val homeContent by viewModel.homeContent.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()

    val allTracks by PlayerController.queueFlow.collectAsState()
    val recentlyPlayed by PlayerController.recentlyPlayed.collectAsState()
    val currentTrack by PlayerController.currentTrack.collectAsState()
    val favoriteIds by PlayerController.favoriteIds.collectAsState()
    val favoriteTracks = remember(allTracks, favoriteIds) {
        allTracks.filter { it.id in favoriteIds }
    }

    val isLoggedIn by IcmAuthRepository.isLoggedIn.collectAsState()

    // Load home content on first composition or when login state changes
    LaunchedEffect(isLoggedIn) {
        viewModel.loadHomeContent()
    }

    // Extract blocks from home content
    val bannerBlock = remember(homeContent) { homeContent?.blocks?.find { it.type == "banner" } }
    val newReleasesBlock = remember(homeContent) { homeContent?.blocks?.find { it.type == "new_releases" } }
    val chartsBlock = remember(homeContent) { homeContent?.blocks?.find { it.type == "charts" } }
    val recommendationsBlock = remember(homeContent) { homeContent?.blocks?.find { it.type == "recommendations" } }

    // Wave state - active mood station
    var activeMoodId by remember { mutableStateOf<String?>(null) }
    var moodTracks by remember { mutableStateOf<Map<String, List<IcmWaveTrack>>>(emptyMap()) }
    var moodLoading by remember { mutableStateOf<Set<String>>(emptySet()) }
    var isPlayingMood by remember { mutableStateOf(false) }

    fun waveTrackToTrack(waveTrack: IcmWaveTrack): Track {
        return waveTrack.toTrack()
    }

    // Cached seed_track_id per mood so wave refills stay on-genre.
    val moodSeeds = remember { mutableStateMapOf<String, String?>() }

    fun loadMoreMoodTracks(moodId: String, existing: List<IcmWaveTrack>) {
        if (moodId in moodLoading) return
        moodLoading = moodLoading + moodId
        scope.launch {
            val waveTracks = existing.toMutableList()
            val seed = moodSeeds[moodId]
            repeat(5) {
                val exclude = waveTracks.map { it.id }
                val response = IcmRepository.getWaveNext(
                    seedTrackId = seed,
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
        val mood = moodCategories.find { it.id == moodId } ?: return
        PlayerController.setAutoRefillContext(
            type = "wave",
            id = moodId,
            name = mood.title,
            seedTrackId = moodSeeds[moodId]
        )
        val existing = moodTracks[moodId]
        if (!existing.isNullOrEmpty()) {
            // Already loaded — start playing immediately
            activeMoodId = moodId
            isPlayingMood = true
            scope.launch {
                val tracks = existing.map { waveTrackToTrack(it) }
                // Resolve first track URL immediately for fast start
                val firstResolved = resolveWaveTrackUrl(tracks.firstOrNull())
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
            // Each mood needs its own seed so the wave really differs.
            val seed = moodSeeds.getOrPut(moodId) { resolveMoodSeedTrackId(mood) }
            // Refresh refill context with the resolved seed so auto-refill stays on-genre.
            PlayerController.setAutoRefillContext(
                type = "wave",
                id = moodId,
                name = mood.title,
                seedTrackId = seed
            )

            val waveTracks = mutableListOf<IcmWaveTrack>()
            repeat(5) {
                val exclude = waveTracks.map { it.id }
                val response = IcmRepository.getWaveNext(
                    seedTrackId = seed,
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
                val firstResolved = resolveWaveTrackUrl(tracks.firstOrNull())
                if (firstResolved != null) {
                    val resolvedTracks = tracks.toMutableList()
                    resolvedTracks[0] = firstResolved
                    PlayerController.setQueue(resolvedTracks)
                    PlayerController.playTrack(context, 0)
                }
                // Preload next batch
                loadMoreMoodTracks(moodId, waveTracks)
            } else {
                // Wave is empty or user is not linked — fall back to a plain
                // search-driven mood playlist so the card still works.
                val fallback = loadMoodFallbackTracks(mood, count = 12)
                if (fallback.isNotEmpty()) {
                    val firstResolved = resolveWaveTrackUrl(fallback.firstOrNull())
                    val resolvedTracks = fallback.toMutableList()
                    if (firstResolved != null) resolvedTracks[0] = firstResolved
                    PlayerController.clearAutoRefillContext()
                    PlayerController.setQueue(resolvedTracks)
                    PlayerController.playTrack(context, 0)
                } else {
                    activeMoodId = null
                    isPlayingMood = false
                    PlayerController.clearAutoRefillContext()
                }
            }
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

            // Header with refresh
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Listen Now",
                    fontWeight = FontWeight.Bold,
                    fontSize = 32.sp,
                    color = LiquidTheme.colors.textPrimary
                )
                IconButton(onClick = { viewModel.refresh() }) {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = "Refresh",
                        tint = LiquidTheme.colors.textSecondary
                    )
                }
            }

            Spacer(modifier = Modifier.height(28.dp))

            // Loading state
            if (isLoading && homeContent == null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(400.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = AppleRed)
                }
            }

            // Error state
            error?.let { errorMsg ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF1C1C1E))
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = errorMsg,
                        color = Color(0xFFFF453A),
                        fontSize = 14.sp
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            // ─── Banners (Featured) ───
            bannerBlock?.let { block ->
                if (block.items.isNotEmpty()) {
                    SectionHeader(title = block.title)
                    Spacer(modifier = Modifier.height(12.dp))
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        items(block.items, key = { "banner_${it.id}" }) { item ->
                            BannerCard(
                                item = item,
                                onClick = {
                                    val track = item.toTrack()
                                    scope.launch {
                                        val resolved = resolveWaveTrackUrl(track)
                                        PlayerController.playNext(resolved ?: track, context)
                                    }
                                }
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(32.dp))
                }
            }

            // ─── New Releases ───
            newReleasesBlock?.let { block ->
                if (block.items.isNotEmpty()) {
                    SectionHeader(title = block.title)
                    Spacer(modifier = Modifier.height(12.dp))
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        items(block.items, key = { "new_${it.id}" }) { item ->
                            AlbumCard(
                                item = item,
                                onClick = {
                                    item.collectionId?.let { onNavigateToAlbum(it) }
                                }
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(32.dp))
                }
            }

            // ─── Charts (Top Tracks) ───
            chartsBlock?.let { block ->
                if (block.items.isNotEmpty()) {
                    SectionHeader(title = block.title)
                    Spacer(modifier = Modifier.height(12.dp))
                    // Charts displayed as a horizontal carousel with 3 tracks per column
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        val chunked = block.items.chunked(3)
                        items(chunked, key = { "chart_col_${it.first().id}" }) { chunk ->
                            Column(
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.width(280.dp)
                            ) {
                                chunk.forEach { item ->
                                    ChartTrackRow(
                                        item = item,
                                        onClick = {
                                            val track = item.toTrack()
                                            scope.launch {
                                                val resolved = resolveWaveTrackUrl(track)
                                                PlayerController.playNext(resolved ?: track, context)
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(32.dp))
                }
            }

            // ─── Recommendations (Made For You) ───
            recommendationsBlock?.let { block ->
                if (block.items.isNotEmpty()) {
                    SectionHeader(title = block.title)
                    Spacer(modifier = Modifier.height(12.dp))
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        items(block.items, key = { "rec_${it.id}" }) { item ->
                            RecommendationCard(
                                item = item,
                                onClick = {
                                    val track = item.toTrack()
                                    scope.launch {
                                        val resolved = resolveWaveTrackUrl(track)
                                        PlayerController.playNext(resolved ?: track, context)
                                    }
                                }
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(32.dp))
                }
            }

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

            Spacer(modifier = Modifier.height(200.dp))
        }
    }
}

// ═══════════════════════════════════════════════════════════
//  UI Components
// ═══════════════════════════════════════════════════════════

@Composable
private fun BannerCard(
    item: IcmHomeItem,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .width(320.dp)
            .height(180.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
    ) {
        // Background image
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(item.cover)
                .crossfade(true)
                .build(),
            contentDescription = item.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        // Dark gradient overlay
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
        // Text content
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(16.dp)
        ) {
            Text(
                text = item.title,
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = item.genre ?: item.displayArtist,
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        // Play button
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(12.dp)
                .size(36.dp)
                .clip(RoundedCornerShape(50))
                .background(AppleRed)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.PlayArrow,
                contentDescription = "Play",
                tint = Color.White,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun AlbumCard(
    item: IcmHomeItem,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .width(140.dp)
            .clickable(onClick = onClick)
    ) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(item.cover)
                .crossfade(true)
                .build(),
            contentDescription = item.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(140.dp)
                .clip(RoundedCornerShape(8.dp))
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = item.title,
            color = LiquidTheme.colors.textPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = item.displayArtist,
            color = LiquidTheme.colors.textSecondary,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ChartTrackRow(
    item: IcmHomeItem,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Rank number
        Text(
            text = "${item.rank ?: 0}",
            color = LiquidTheme.colors.textSecondary,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.width(28.dp)
        )
        // Mini cover
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(item.cover)
                .crossfade(true)
                .build(),
            contentDescription = item.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(6.dp))
        )
        Spacer(modifier = Modifier.width(12.dp))
        // Track info
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.title,
                color = LiquidTheme.colors.textPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = item.displayArtist,
                color = LiquidTheme.colors.textSecondary,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun RecommendationCard(
    item: IcmHomeItem,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .width(160.dp)
            .clickable(onClick = onClick)
    ) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(item.cover)
                .crossfade(true)
                .build(),
            contentDescription = item.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(160.dp)
                .clip(RoundedCornerShape(8.dp))
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = item.title,
            color = LiquidTheme.colors.textPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = item.displayArtist,
            color = LiquidTheme.colors.textSecondary,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        color = LiquidTheme.colors.textPrimary,
        modifier = Modifier.padding(horizontal = 20.dp)
    )
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
            .clip(RoundedCornerShape(16.dp))
            .background(Brush.linearGradient(mood.gradientColors))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(16.dp)
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp).align(Alignment.BottomEnd),
                color = Color.White,
                strokeWidth = 2.dp
            )
        }
        Text(
            text = mood.icon,
            fontSize = 24.sp,
            modifier = Modifier.align(Alignment.TopEnd)
        )
        Text(
            text = mood.title,
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.align(Alignment.BottomStart)
        )
        if (isActive) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(AppleRed, RoundedCornerShape(50))
                    .align(Alignment.TopStart)
            )
        }
    }
}

@Composable
private fun RecentTrackCard(
    track: Track,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .width(140.dp)
            .clickable(onClick = onClick)
    ) {
        AlbumArtImage(
            uri = track.displayArtUri,
            coverUrl = track.coverUrl,
            modifier = Modifier
                .size(140.dp)
                .clip(RoundedCornerShape(8.dp))
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = track.title,
            color = LiquidTheme.colors.textPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = track.artist,
            color = LiquidTheme.colors.textSecondary,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
