package com.liquidmusicglass.ui.screens

import android.content.Context
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.ThumbDown
import androidx.compose.material.icons.rounded.ThumbUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
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
import kotlinx.coroutines.launch

private val AppleRed = Color(0xFFFC3C44)

@Composable
fun WaveScreen(
    onBack: () -> Unit = {},
    onNavigateToAlbum: (String) -> Unit = {},
    onNavigateToArtist: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var waveQueue by remember { mutableStateOf<List<IcmWaveTrack>>(emptyList()) }
    var currentIndex by remember { mutableStateOf(0) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showOnboarding by remember { mutableStateOf(false) }

    fun loadNextTrack() {
        if (isLoading) return
        isLoading = true
        errorMessage = null

        scope.launch {
            val exclude = waveQueue.map { it.id }
            val response = IcmRepository.getWaveNext(
                exclude = exclude.takeIf { it.isNotEmpty() },
                recentSkips = 0
            )

            if (response != null) {
                when (response.status) {
                    "ok" -> {
                        response.track?.let { track ->
                            waveQueue = waveQueue + track
                        }
                    }
                    "empty" -> {
                        showOnboarding = true
                    }
                    else -> {
                        errorMessage = "Wave status: ${response.status}"
                    }
                }
            } else {
                errorMessage = "Failed to load wave"
            }
            isLoading = false
        }
    }

    fun playWaveTrack(index: Int) {
        if (index !in waveQueue.indices) return
        currentIndex = index
        val waveTrack = waveQueue[index]
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

    fun sendFeedback(feedbackType: String, value: String) {
        scope.launch {
            IcmRepository.sendWaveFeedback(feedbackType, value)
        }
    }

    // Load initial tracks
    LaunchedEffect(Unit) {
        repeat(3) { loadNextTrack() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // Header
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.Black)
                .padding(top = 48.dp, bottom = 16.dp, start = 20.dp, end = 20.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(Color(0xFF1C1C1E), CircleShape)
                        .clip(CircleShape)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { onBack() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.AutoMirrored.Rounded.ArrowBack,
                        null,
                        tint = Color.White,
                        modifier = Modifier.size(22.dp)
                    )
                }
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    text = "My Wave",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    modifier = Modifier.weight(1f)
                )
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(Color(0xFF1C1C1E), CircleShape)
                        .clip(CircleShape)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            waveQueue = emptyList()
                            currentIndex = 0
                            scope.launch {
                                IcmRepository.resetWave()
                                repeat(3) { loadNextTrack() }
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Rounded.Refresh,
                        null,
                        tint = AppleRed,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }

        // Content
        Box(modifier = Modifier.fillMaxSize()) {
            when {
                isLoading && waveQueue.isEmpty() -> {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center),
                        color = AppleRed
                    )
                }
                errorMessage != null && waveQueue.isEmpty() -> {
                    ErrorState(message = errorMessage ?: "Unknown error", onRetry = {
                        waveQueue = emptyList()
                        currentIndex = 0
                        repeat(3) { loadNextTrack() }
                    })
                }
                showOnboarding -> {
                    WaveOnboarding(
                        onComplete = {
                            showOnboarding = false
                            waveQueue = emptyList()
                            repeat(3) { loadNextTrack() }
                        }
                    )
                }
                waveQueue.isEmpty() -> {
                    EmptyState("Wave is empty. Start listening to get personalized tracks!")
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(vertical = 8.dp)
                    ) {
                        items(waveQueue, key = { it.id }) { track ->
                            val index = waveQueue.indexOf(track)
                            WaveTrackItem(
                                track = track,
                                isPlaying = index == currentIndex,
                                onPlay = { playWaveTrack(index) },
                                onLike = { sendFeedback("more_track", track.id) },
                                onDislike = { sendFeedback("less_track", track.id) },
                                onNavigateToAlbum = onNavigateToAlbum,
                                onNavigateToArtist = onNavigateToArtist
                            )
                        }

                        // Load more
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                if (isLoading) {
                                    CircularProgressIndicator(
                                        color = AppleRed,
                                        modifier = Modifier.size(24.dp)
                                    )
                                } else {
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(50))
                                            .background(Color(0xFF1C1C1E))
                                            .clickable { loadNextTrack() }
                                            .padding(horizontal = 24.dp, vertical = 12.dp)
                                    ) {
                                        Text(
                                            "Load more",
                                            color = AppleRed,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WaveTrackItem(
    track: IcmWaveTrack,
    isPlaying: Boolean,
    onPlay: () -> Unit,
    onLike: () -> Unit,
    onDislike: () -> Unit,
    onNavigateToAlbum: (String) -> Unit,
    onNavigateToArtist: (String) -> Unit
) {
    val rotation by animateFloatAsState(
        targetValue = if (isPlaying) 360f else 0f,
        animationSpec = tween(2000),
        label = "spin"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .background(Color(0xFF1C1C1E), RoundedCornerShape(12.dp))
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onPlay)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Cover with play indicator
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.DarkGray),
            contentAlignment = Alignment.Center
        ) {
            if (!track.cover.isNullOrBlank()) {
                AsyncImage(
                    model = track.cover.replace("1000x1000", "300x300"),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }
            if (isPlaying) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.4f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Rounded.PlayArrow,
                        null,
                        tint = Color.White,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = track.title,
                color = if (isPlaying) AppleRed else Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = track.artist ?: "Unknown Artist",
                color = Color.Gray,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        // Actions
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            IconButton(onClick = onDislike) {
                Icon(
                    Icons.Rounded.ThumbDown,
                    null,
                    tint = Color.Gray,
                    modifier = Modifier.size(20.dp)
                )
            }
            IconButton(onClick = onLike) {
                Icon(
                    Icons.Rounded.ThumbUp,
                    null,
                    tint = Color.Gray,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
private fun WaveOnboarding(
    onComplete: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var popularArtists by remember { mutableStateOf<List<com.liquidmusicglass.api.icm.IcmWaveOnboardingArtist>>(emptyList()) }
    var selectedArtists by remember { mutableStateOf<Set<String>>(emptySet()) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        popularArtists = IcmRepository.getWavePopularArtists()
        isLoading = false
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(40.dp))
        Text(
            "Personalize Your Wave",
            color = Color.White,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "Select at least 3 artists you like",
            color = Color.Gray,
            fontSize = 16.sp
        )
        Spacer(modifier = Modifier.height(24.dp))

        if (isLoading) {
            CircularProgressIndicator(color = AppleRed)
        } else {
            // Artist grid
            androidx.compose.foundation.lazy.LazyVerticalGrid(
                columns = androidx.compose.foundation.lazy.GridCells.Fixed(3),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.weight(1f)
            ) {
                items(popularArtists, key = { it.id }) { artist ->
                    val isSelected = selectedArtists.contains(artist.id)
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (isSelected) AppleRed.copy(alpha = 0.3f)
                                else Color(0xFF1C1C1E)
                            )
                            .clickable {
                                selectedArtists = if (isSelected) {
                                    selectedArtists - artist.id
                                } else {
                                    selectedArtists + artist.id
                                }
                            }
                            .padding(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .clip(CircleShape)
                                .background(Color.DarkGray)
                        ) {
                            if (!artist.image.isNullOrBlank()) {
                                AsyncImage(
                                    model = artist.image,
                                    contentDescription = null,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            artist.name ?: "Unknown",
                            color = if (isSelected) Color.White else Color.Gray,
                            fontSize = 12.sp,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Done button
            val canComplete = selectedArtists.size >= 3
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .clip(RoundedCornerShape(50))
                    .background(if (canComplete) AppleRed else Color(0xFF1C1C1E))
                    .clickable(enabled = canComplete) {
                        scope.launch {
                            val artists = selectedArtists.map { id ->
                                com.liquidmusicglass.api.icm.IcmWaveOnboardingArtistSave(
                                    id = id,
                                    name = popularArtists.find { it.id == id }?.name ?: ""
                                )
                            }
                            IcmRepository.saveWaveOnboarding(artists)
                            onComplete()
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "Done (${selectedArtists.size})",
                    color = if (canComplete) Color.White else Color.Gray,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun EmptyState(message: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Rounded.PlayArrow,
                contentDescription = null,
                tint = Color.Gray,
                modifier = Modifier.size(48.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = message,
                color = Color.Gray,
                fontSize = 16.sp
            )
        }
    }
}

@Composable
private fun ErrorState(message: String, onRetry: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)
        ) {
            Icon(
                Icons.Rounded.Close,
                contentDescription = null,
                tint = Color.Gray,
                modifier = Modifier.size(48.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = message,
                color = Color.Gray,
                fontSize = 16.sp
            )
            Spacer(modifier = Modifier.height(16.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(AppleRed)
                    .clickable(onClick = onRetry)
                    .padding(horizontal = 24.dp, vertical = 10.dp)
            ) {
                Text(
                    text = "Retry",
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}
