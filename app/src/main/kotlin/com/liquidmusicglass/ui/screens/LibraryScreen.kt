package com.liquidmusicglass.ui.screens

import android.content.Context
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.*
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
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import coil.compose.AsyncImage
import com.liquidmusicglass.data.local.db.FavoriteTrackEntity
import com.liquidmusicglass.data.local.db.LibraryRepository
import com.liquidmusicglass.engine.PlayerController
import com.liquidmusicglass.engine.Track
import com.liquidmusicglass.ui.viewmodel.LibraryViewModel
import kotlinx.coroutines.launch

private enum class LibraryTab { LIKES, SUBSCRIPTIONS }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onNavigateToAlbum: (String) -> Unit = {},
    onNavigateToArtist: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val viewModel = remember { LibraryViewModel(context) }

    var selectedTab by remember { mutableStateOf(LibraryTab.LIKES) }

    val favorites by viewModel.favorites.collectAsState()
    val isSyncing by viewModel.isSyncing.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val favoriteIds by viewModel.favoriteIds.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // Header with refresh button
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.Black)
                .padding(top = 48.dp, bottom = 16.dp, start = 20.dp, end = 20.dp)
        ) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Library",
                        fontSize = 34.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    if (isSyncing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = Color(0xFFFC3C44),
                            strokeWidth = 2.dp
                        )
                    } else {
                        IconButton(
                            onClick = { viewModel.syncWithCloud() },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Refresh,
                                contentDescription = "Refresh",
                                tint = Color.Gray,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TabButton(
                        text = "Likes",
                        icon = Icons.Default.Favorite,
                        isSelected = selectedTab == LibraryTab.LIKES,
                        onClick = { selectedTab = LibraryTab.LIKES },
                        modifier = Modifier.weight(1f)
                    )
                    TabButton(
                        text = "Artists",
                        icon = Icons.Default.Person,
                        isSelected = selectedTab == LibraryTab.SUBSCRIPTIONS,
                        onClick = { selectedTab = LibraryTab.SUBSCRIPTIONS },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // Action buttons for Likes tab
        if (selectedTab == LibraryTab.LIKES && favorites.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                ActionButton(
                    text = "Play All",
                    icon = Icons.Default.PlayArrow,
                    onClick = { viewModel.playAll(context) },
                    modifier = Modifier.weight(1f)
                )
                ActionButton(
                    text = "Shuffle",
                    icon = Icons.Default.Shuffle,
                    onClick = { viewModel.shuffleAndPlay(context) },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // Content
        Box(
            modifier = Modifier.fillMaxSize()
        ) {
            when {
                isSyncing && favorites.isEmpty() -> {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center),
                        color = Color(0xFFFC3C44)
                    )
                }
                selectedTab == LibraryTab.LIKES -> {
                    if (favorites.isEmpty() && !isSyncing) {
                        EmptyState("No liked tracks yet")
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(vertical = 8.dp)
                        ) {
                            items(favorites, key = { it.trackId }) { track ->
                                FavoriteTrackItem(
                                    track = track,
                                    isLiked = track.trackId in favoriteIds,
                                    onClick = { viewModel.playTrack(context, track.trackId) },
                                    onToggleLike = {
                                        scope.launch {
                                            val repo = LibraryRepository.getInstance(context)
                                            val t = Track(
                                                id = track.trackId,
                                                title = track.title,
                                                artist = track.artistName ?: "Unknown Artist",
                                                albumName = track.albumTitle ?: "",
                                                uri = Uri.parse("https://byicloud.online/track/${track.trackId}"),
                                                durationMs = track.durationMs,
                                                albumId = track.collectionId?.hashCode()?.toLong() ?: -1L,
                                                coverUrl = track.imageUrl
                                            )
                                            repo.toggleFavorite(t)
                                        }
                                    },
                                    onNavigateToAlbum = onNavigateToAlbum
                                )
                            }
                        }
                    }
                }
                selectedTab == LibraryTab.SUBSCRIPTIONS -> {
                    // Subscriptions still loaded from cloud API directly
                    SubscriptionsContent(
                        onNavigateToArtist = onNavigateToArtist
                    )
                }
            }
        }

        // Error snackbar
        if (errorMessage != null) {
            LaunchedEffect(errorMessage) {
                kotlinx.coroutines.delay(3000)
                viewModel.clearError()
            }
        }
    }
}

@Composable
private fun ActionButton(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF1C1C1E))
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Color(0xFFFC3C44),
                modifier = Modifier.size(20.dp)
            )
            Text(
                text = text,
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun TabButton(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(
                if (isSelected) Color(0xFFFC3C44).copy(alpha = 0.3f)
                else Color.Transparent
            )
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isSelected) Color.White else Color.Gray,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = text,
                color = if (isSelected) Color.White else Color.Gray,
                fontSize = 14.sp,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
            )
        }
    }
}

@Composable
private fun FavoriteTrackItem(
    track: FavoriteTrackEntity,
    isLiked: Boolean,
    onClick: () -> Unit,
    onToggleLike: () -> Unit,
    onNavigateToAlbum: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color.DarkGray)
        ) {
            if (!track.imageUrl.isNullOrBlank()) {
                AsyncImage(
                    model = track.imageUrl.replace("1000x1000", "300x300"),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = track.title,
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = track.artistName ?: "Unknown Artist",
                color = Color.Gray,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        // Like button
        IconButton(onClick = onToggleLike) {
            Icon(
                imageVector = if (isLiked) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                contentDescription = if (isLiked) "Unlike" else "Like",
                tint = if (isLiked) Color(0xFFFC3C44) else Color.Gray,
                modifier = Modifier.size(22.dp)
            )
        }

        if (track.durationMs > 0) {
            Text(
                text = formatDuration(track.durationMs),
                color = Color.Gray,
                fontSize = 12.sp,
                modifier = Modifier.padding(start = 4.dp)
            )
        }
    }
}

@Composable
private fun SubscriptionsContent(
    onNavigateToArtist: (String) -> Unit
) {
    // Keep existing subscriptions implementation
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var subscriptions by remember { mutableStateOf<List<com.liquidmusicglass.api.icm.IcmLibraryArtist>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        isLoading = true
        scope.launch {
            val response = com.liquidmusicglass.api.icm.IcmRepository.getLibrarySubscriptions(
                limit = 50,
                offset = 0
            )
            if (response != null) {
                subscriptions = response.items
            } else {
                errorMessage = "Failed to load subscriptions"
            }
            isLoading = false
        }
    }

    when {
        isLoading -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color(0xFFFC3C44))
            }
        }
        errorMessage != null -> {
            ErrorState(message = errorMessage ?: "Unknown error", onRetry = {
                errorMessage = null
                isLoading = true
                scope.launch {
                    val response = com.liquidmusicglass.api.icm.IcmRepository.getLibrarySubscriptions(
                        limit = 50, offset = 0
                    )
                    if (response != null) subscriptions = response.items
                    else errorMessage = "Failed to load subscriptions"
                    isLoading = false
                }
            })
        }
        subscriptions.isEmpty() -> {
            EmptyState("No subscriptions yet")
        }
        else -> {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                items(subscriptions, key = { it.id }) { artist ->
                    ArtistSubscriptionItem(
                        artist = artist,
                        onClick = { onNavigateToArtist(artist.id) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ArtistSubscriptionItem(
    artist: com.liquidmusicglass.api.icm.IcmLibraryArtist,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(Color.DarkGray)
        ) {
            if (!artist.displayImage.isNullOrBlank()) {
                AsyncImage(
                    model = artist.displayImage,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Icon(
                    imageVector = Icons.Default.Person,
                    contentDescription = null,
                    tint = Color.Gray,
                    modifier = Modifier
                        .size(28.dp)
                        .align(Alignment.Center)
                )
            }
        }

        Spacer(modifier = Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = artist.displayName,
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
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
                imageVector = Icons.Outlined.FavoriteBorder,
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
                imageVector = Icons.Outlined.FavoriteBorder,
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
                    .background(Color(0xFFFC3C44))
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

private fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}
