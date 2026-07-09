package com.liquidmusicglass.ui.screens

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.filled.Favorite
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
import androidx.compose.ui.graphics.drawscope.Stroke
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
import com.liquidmusicglass.api.icm.WaveSignalQueue
import com.liquidmusicglass.api.icm.toTrack
import com.liquidmusicglass.data.local.WaveRepository
import com.liquidmusicglass.data.wave.WaveMode
import com.liquidmusicglass.engine.PlayerController
import com.liquidmusicglass.engine.Track
import com.liquidmusicglass.ui.glass.AlbumArtImage
import com.liquidmusicglass.ui.glass.liquidClickable
import com.liquidmusicglass.ui.theme.LiquidMotion
import com.liquidmusicglass.ui.glass.rememberAlbumColors
import com.liquidmusicglass.ui.player.AuraBackground
import com.liquidmusicglass.ui.theme.LiquidTheme
import com.liquidmusicglass.ui.viewmodel.HomeViewModel
import kotlinx.coroutines.launch

private val AppleRed = Color(0xFFFC3C44)

private suspend fun resolveWaveTrackUrl(track: Track?): Track? {
    if (track == null) return null

    return try {
        val url = IcmRepository.getStreamUrl(track.id, source = track.source)
        if (url != null) {
            track.copy(uri = Uri.parse(url))
        } else {
            android.util.Log.w("HomeScreen", "Wave track ${track.id} (${track.title}) failed URL resolution, skipping")
            null
        }
    } catch (e: Exception) {
        android.util.Log.w("HomeScreen", "Wave track ${track.id} (${track.title}) URL resolve exception: ${e.message}")
        null
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
        coverUrl = cover,
        isExplicit = isExplicit,
        source = source,
        genre = genre
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
        "my_wave", "My Wave",
        listOf(Color(0xFFFC3C44), Color(0xFFFF6B6B)), "",
        listOf("") // Personal wave — no seed queries, uses getWaveNext directly
    ),
    MoodCategory(
        "melancholy", "Melancholy",
        listOf(Color(0xFF1E3A5F), Color(0xFF2D5A87)), "",
        listOf("melancholy", "sad indie", "lo-fi sad")
    ),
    MoodCategory(
        "good_mood", "Good Mood",
        listOf(Color(0xFFD4730E), Color(0xFFF5A623)), "",
        listOf("happy pop hits", "feel good", "summer hits")
    ),
    MoodCategory(
        "broken_heart", "Broken Heart",
        listOf(Color(0xFF8B1538), Color(0xFFC41E3A)), "",
        listOf("breakup songs", "heartbreak", "sad love songs")
    ),
    MoodCategory(
        "focus", "Focus",
        listOf(Color(0xFF2D5016), Color(0xFF4A7C23)), "",
        listOf("focus instrumental", "deep focus", "study beats")
    ),
    MoodCategory(
        "energy", "Energy",
        listOf(Color(0xFF8B4513), Color(0xFFD2691E)), "",
        listOf("high energy", "power hits", "edm energy")
    ),
    MoodCategory(
        "night", "Night Wave",
        listOf(Color(0xFF1A1A2E), Color(0xFF16213E)), "",
        listOf("late night", "night drive", "synthwave night")
    ),
    MoodCategory(
        "workout", "Workout",
        listOf(Color(0xFF4A0000), Color(0xFF8B0000)), "",
        listOf("workout", "gym motivation", "running mix")
    ),
    MoodCategory(
        "chill", "Chill",
        listOf(Color(0xFF483D8B), Color(0xFF6A5ACD)), "",
        listOf("chillhop", "chill lofi", "ambient chill")
    ),
)

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
    val charts by viewModel.charts.collectAsState()
    val isLoadingCharts by viewModel.isLoadingCharts.collectAsState()

    val allTracks by PlayerController.queueFlow.collectAsState()
    val recentlyPlayed by PlayerController.recentlyPlayed.collectAsState()
    val currentTrack by PlayerController.currentTrack.collectAsState()
    // Цвета для фона-ауры — из обложки текущего трека (подстраивается под трек).
    val auraColors = rememberAlbumColors(uri = null, coverUrl = currentTrack?.coverUrl)
    val favoriteIds by PlayerController.favoriteIds.collectAsState()
    val favoriteTracks = remember(allTracks, favoriteIds) {
        allTracks.filter { it.id in favoriteIds }
    }

    val isLoggedIn by IcmAuthRepository.isLoggedIn.collectAsState()

    // Load home content
    LaunchedEffect(isLoggedIn) {
        viewModel.loadHomeContent()
    }

    // Extract blocks from home content
    val popularBlock = remember(homeContent) { homeContent?.blocks?.find { it.type == "popular" } }
    val bannerBlock = remember(homeContent) { homeContent?.blocks?.find { it.type == "banner" } }
    val newReleasesBlock = remember(homeContent) { homeContent?.blocks?.find { it.type == "new_releases" } }
    val chartsBlock = remember(homeContent) { homeContent?.blocks?.find { it.type == "charts" } }
    val recommendationsBlock = remember(homeContent) { homeContent?.blocks?.find { it.type == "recommendations" } }

    // Wave state - active mood station
    var activeMoodId by remember { mutableStateOf<String?>(null) }
    var moodTracks by remember { mutableStateOf<Map<String, List<Track>>>(emptyMap()) }
    var moodLoading by remember { mutableStateOf<Set<String>>(emptySet()) }
    var isPlayingMood by remember { mutableStateOf(false) }
    var showWaveOnboarding by remember { mutableStateOf(false) }
    var pendingMoodId by remember { mutableStateOf<String?>(null) }

    fun moodApiTerm(mood: MoodCategory): String =
        mood.seedQueries.firstOrNull()?.takeIf { it.isNotBlank() } ?: mood.id

    fun loadMoreMoodTracks(moodId: String, existing: List<Track>) {
        if (moodId in moodLoading) return
        val mood = moodCategories.find { it.id == moodId } ?: return
        moodLoading = moodLoading + moodId
        scope.launch {
            val repo = WaveRepository.getInstance(context)
            val newTracks = repo.buildWaveModeQueue(
                mode = WaveMode.Mood(
                    mood = moodApiTerm(mood),
                    displayName = mood.title,
                    source = "apple",
                    diversity = 0.5
                ),
                count = 10,
                exclude = existing.map { it.id }
            )
            moodTracks = moodTracks + (moodId to (existing + newTracks).distinctBy { it.id })
            val resolvedNewTracks = newTracks
                .mapNotNull { resolveWaveTrackUrl(it) }
            resolvedNewTracks.forEach { PlayerController.addToQueue(it) }
            moodLoading = moodLoading - moodId
        }
    }

    fun playMoodStation(moodId: String) {
        val mood = moodCategories.find { it.id == moodId } ?: return
        PlayerController.setAutoRefillContext(
            type = if (moodId == "my_wave") "wave" else "mood",
            id = if (moodId == "my_wave") moodId else moodApiTerm(mood),
            name = mood.title
        )
        val existing = moodTracks[moodId]
        if (!existing.isNullOrEmpty()) {
            // Already loaded — start playing immediately
            activeMoodId = moodId
            isPlayingMood = true
            scope.launch {
                val resolvedTracks = existing.mapNotNull { resolveWaveTrackUrl(it) }
                if (resolvedTracks.isNotEmpty()) {
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
            if (moodId == "my_wave") {
                // === ICM МОЯ ВОЛНА ===
                val waveRepo = WaveRepository.getInstance(context)
                val queue = waveRepo.buildWaveQueue(count = WaveRepository.WAVE_QUEUE_SIZE)
                moodLoading = moodLoading - moodId

                if (queue.isNotEmpty()) {
                    val resolvedQueue = queue.mapNotNull { resolveWaveTrackUrl(it) }
                    if (resolvedQueue.isNotEmpty()) {
                        PlayerController.setQueue(resolvedQueue)
                        PlayerController.playTrack(context, 0)
                    } else {
                        pendingMoodId = moodId
                        activeMoodId = null
                        isPlayingMood = false
                        PlayerController.clearAutoRefillContext()
                        showWaveOnboarding = true
                    }
                } else {
                    pendingMoodId = moodId
                    activeMoodId = null
                    isPlayingMood = false
                    PlayerController.clearAutoRefillContext()
                    showWaveOnboarding = true
                }
            } else {
                PlayerController.setAutoRefillContext(
                    type = "mood",
                    id = moodApiTerm(mood),
                    name = mood.title
                )

                val repo = WaveRepository.getInstance(context)
                val waveTracks = repo.buildWaveModeQueue(
                    mode = WaveMode.Mood(
                        mood = moodApiTerm(mood),
                        displayName = mood.title,
                        source = "apple",
                        diversity = 0.5
                    ),
                    count = WaveRepository.WAVE_QUEUE_SIZE
                )
                moodTracks = moodTracks + (moodId to waveTracks)
                moodLoading = moodLoading - moodId

                if (waveTracks.isNotEmpty()) {
                    // Resolve all track URLs and filter out those that fail
                    val resolvedTracks = waveTracks
                        .mapNotNull { resolveWaveTrackUrl(it) }
                    
                    if (resolvedTracks.isNotEmpty()) {
                        PlayerController.setQueue(resolvedTracks)
                        PlayerController.playTrack(context, 0)
                        // Preload next batch
                        loadMoreMoodTracks(moodId, waveTracks)
                    } else {
                        // All wave tracks failed to resolve — fall back to search
                        val fallback = loadMoodFallbackTracks(mood, count = 12)
                        if (fallback.isNotEmpty()) {
                            val fallbackResolved = fallback
                                .mapNotNull { resolveWaveTrackUrl(it) }
                            if (fallbackResolved.isNotEmpty()) {
                                PlayerController.clearAutoRefillContext()
                                PlayerController.setQueue(fallbackResolved)
                                PlayerController.playTrack(context, 0)
                            } else {
                                activeMoodId = null
                                isPlayingMood = false
                                PlayerController.clearAutoRefillContext()
                            }
                        } else {
                            activeMoodId = null
                            isPlayingMood = false
                            PlayerController.clearAutoRefillContext()
                        }
                    }
                } else {
                    // Wave is empty — fall back to search-driven playlist
                    val fallback = loadMoodFallbackTracks(mood, count = 12)
                    if (fallback.isNotEmpty()) {
                        val resolvedTracks = fallback.mapNotNull { resolveWaveTrackUrl(it) }
                        if (resolvedTracks.isNotEmpty()) {
                            PlayerController.clearAutoRefillContext()
                            PlayerController.setQueue(resolvedTracks)
                            PlayerController.playTrack(context, 0)
                        } else {
                            activeMoodId = null
                            isPlayingMood = false
                            PlayerController.clearAutoRefillContext()
                        }
                    } else {
                        activeMoodId = null
                        isPlayingMood = false
                        PlayerController.clearAutoRefillContext()
                    }
                }
            }
        }
    }

    fun sendWaveFeedback(feedbackType: String, value: String) {
        scope.launch { WaveSignalQueue.sendFeedback(feedbackType, value) }
    }

    Box(modifier = Modifier.fillMaxSize().background(LiquidTheme.colors.settingsBackground)) {
        // Фон-аура (Liquid Aurora) — первым слоем, фиксирована; контент скроллится поверх.
        AuraBackground(
            albumColors = auraColors,
            modifier = Modifier.fillMaxSize(),
            smokeSaturation = 1.22f,
            smokeContrast = 1.16f
        )
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

            // ─── My Wave - Mood Categories (Moods to the Top) ───
            SectionHeader(title = "Moods")
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

            // ─── Popular Tracks ───
            popularBlock?.let { block ->
                if (block.items.isNotEmpty()) {
                    SectionHeader(title = block.title)
                    Spacer(modifier = Modifier.height(12.dp))
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        items(block.items, key = { "popular_${it.id}" }) { item ->
                            RecommendationCard(
                                item = item,
                                onClick = {
                                    val track = item.toTrack()
                                    scope.launch {
                                        val resolved = resolveWaveTrackUrl(track)
                                        if (resolved != null) {
                                            PlayerController.playNext(resolved, context)
                                        }
                                    }
                                }
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(32.dp))
                }
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
                                        if (resolved != null) {
                                            PlayerController.playNext(resolved, context)
                                        }
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

            // ─── Charts (Top Charts from Apple Music) ───
            if (charts.isNotEmpty()) {
                SectionHeader(title = "Charts")
                Spacer(modifier = Modifier.height(12.dp))
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    items(charts, key = { "chart_${it.id}" }) { chart ->
                        ChartCard(
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
                Spacer(modifier = Modifier.height(32.dp))
            } else if (isLoadingCharts) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 20.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        color = AppleRed,
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp
                    )
                }
                Spacer(modifier = Modifier.height(32.dp))
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
                                        if (resolved != null) {
                                            PlayerController.playNext(resolved, context)
                                        }
                                    }
                                }
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(32.dp))
                }
            }

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

    // Wave Onboarding overlay
    if (showWaveOnboarding) {
        WaveOnboardingScreen(
            onComplete = {
                showWaveOnboarding = false
                // Retry the pending mood after onboarding
                pendingMoodId?.let { moodId ->
                    pendingMoodId = null
                    playMoodStation(moodId)
                }
            },
            onDismiss = {
                showWaveOnboarding = false
                pendingMoodId = null
            }
        )
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
            .liquidClickable(onClick = onClick)
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
                .liquidClickable(pressedScale = LiquidMotion.PressIcon, onClick = onClick),
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
            .liquidClickable(onClick = onClick)
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
            .liquidClickable(onClick = onClick)
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
private fun ChartCard(
    chart: com.liquidmusicglass.api.icm.IcmChart,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .width(160.dp)
            .liquidClickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .size(160.dp)
                .clip(RoundedCornerShape(8.dp))
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
            // Gradient overlay at bottom
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.7f)
                            ),
                            startY = 80f
                        )
                    )
            )
            // Chart name at bottom
            Text(
                text = chart.name,
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(12.dp)
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "${chart.tracks.size} tracks",
            color = LiquidTheme.colors.textSecondary,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
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
            .liquidClickable(onClick = onClick)
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
            .size(130.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(Brush.linearGradient(mood.gradientColors))
            .drawBehind {
                val w = size.width
                val h = size.height
                val color = Color.White.copy(alpha = 0.08f)
                val color2 = Color.White.copy(alpha = 0.04f)
                
                when (mood.id) {
                    "my_wave" -> {
                        val path = androidx.compose.ui.graphics.Path()
                        path.moveTo(0f, h * 0.7f)
                        path.quadraticTo(w * 0.25f, h * 0.4f, w * 0.5f, h * 0.6f)
                        path.quadraticTo(w * 0.75f, h * 0.8f, w, h * 0.5f)
                        drawPath(path, color, style = Stroke(width = 4.dp.toPx()))

                        val path2 = androidx.compose.ui.graphics.Path()
                        path2.moveTo(0f, h * 0.8f)
                        path2.quadraticTo(w * 0.3f, h * 0.5f, w * 0.6f, h * 0.7f)
                        path2.quadraticTo(w * 0.8f, h * 0.9f, w, h * 0.6f)
                        drawPath(path2, color2, style = Stroke(width = 3.dp.toPx()))
                    }
                    "melancholy" -> {
                        for (i in 0..6) {
                            val startX = w * (i * 0.2f - 0.2f)
                            drawLine(
                                color = color,
                                start = androidx.compose.ui.geometry.Offset(startX, 0f),
                                end = androidx.compose.ui.geometry.Offset(startX + w * 0.4f, h),
                                strokeWidth = 1.5.dp.toPx()
                            )
                        }
                    }
                    "good_mood" -> {
                        drawCircle(
                            color = color,
                            radius = w * 0.6f,
                            center = androidx.compose.ui.geometry.Offset(w, 0f),
                            style = Stroke(width = 2.dp.toPx())
                        )
                        drawCircle(
                            color = color,
                            radius = w * 0.4f,
                            center = androidx.compose.ui.geometry.Offset(w, 0f),
                            style = Stroke(width = 2.dp.toPx())
                        )
                        drawCircle(
                            color = color2,
                            radius = w * 0.8f,
                            center = androidx.compose.ui.geometry.Offset(w, 0f),
                            style = Stroke(width = 2.dp.toPx())
                        )
                    }
                    "broken_heart" -> {
                        val path = androidx.compose.ui.graphics.Path()
                        path.moveTo(w * 0.4f, 0f)
                        path.lineTo(w * 0.6f, h * 0.4f)
                        path.lineTo(w * 0.3f, h * 0.6f)
                        path.lineTo(w * 0.5f, h)
                        drawPath(path, color, style = Stroke(width = 2.dp.toPx()))
                    }
                    "focus" -> {
                        for (i in 1..4) {
                            drawCircle(
                                color = color,
                                radius = i * 20.dp.toPx(),
                                center = androidx.compose.ui.geometry.Offset(w / 2f, h / 2f),
                                style = Stroke(width = 1.dp.toPx())
                            )
                        }
                    }
                    "energy" -> {
                        val path = androidx.compose.ui.graphics.Path()
                        path.moveTo(w * 0.7f, 0f)
                        path.lineTo(w * 0.3f, h * 0.55f)
                        path.lineTo(w * 0.6f, h * 0.55f)
                        path.lineTo(w * 0.2f, h)
                        drawPath(path, color, style = Stroke(width = 2.dp.toPx()))
                    }
                    "night" -> {
                        drawCircle(
                            color = color,
                            radius = w * 0.35f,
                            center = androidx.compose.ui.geometry.Offset(w * 0.25f, h * 0.25f),
                            style = Stroke(width = 1.5.dp.toPx())
                        )
                        drawCircle(
                            color = color2,
                            radius = w * 0.32f,
                            center = androidx.compose.ui.geometry.Offset(w * 0.28f, h * 0.22f),
                            style = Stroke(width = 1.5.dp.toPx())
                        )
                    }
                    "workout" -> {
                        for (i in 0..2) {
                            val offset = i * 20.dp.toPx()
                            val path = androidx.compose.ui.graphics.Path()
                            path.moveTo(0f, offset)
                            path.lineTo(w * 0.4f, offset + h * 0.3f)
                            path.lineTo(w * 0.8f, offset)
                            drawPath(path, color, style = Stroke(width = 2.dp.toPx()))
                        }
                    }
                    "chill" -> {
                        drawCircle(
                            color = color,
                            radius = w * 0.45f,
                            center = androidx.compose.ui.geometry.Offset(w * 0.2f, h * 0.8f)
                        )
                        drawCircle(
                            color = color2,
                            radius = w * 0.35f,
                            center = androidx.compose.ui.geometry.Offset(w * 0.8f, h * 0.7f)
                        )
                    }
                    else -> {
                        drawLine(
                            color = color,
                            start = androidx.compose.ui.geometry.Offset(0f, h),
                            end = androidx.compose.ui.geometry.Offset(w, 0f),
                            strokeWidth = 2.dp.toPx()
                        )
                    }
                }
            }
            .liquidClickable(onClick = onClick)
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
            .liquidClickable(onClick = onClick)
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
