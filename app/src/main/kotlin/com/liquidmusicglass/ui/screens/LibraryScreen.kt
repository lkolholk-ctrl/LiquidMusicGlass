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
import androidx.compose.material.icons.filled.Refresh
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
import coil.compose.AsyncImage
import com.liquidmusicglass.api.icm.IcmLibraryArtist
import com.liquidmusicglass.api.icm.IcmLibraryTrack
import com.liquidmusicglass.api.icm.IcmRepository
import com.liquidmusicglass.engine.PlayerController
import com.liquidmusicglass.engine.Track
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
    var selectedTab by remember { mutableStateOf(LibraryTab.LIKES) }

    // Pagination state
    var likedTracks by remember { mutableStateOf<List<IcmLibraryTrack>>(emptyList()) }
    var subscriptions by remember { mutableStateOf<List<IcmLibraryArtist>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var isRefreshing by remember { mutableStateOf(false) }
    var isLoadingMore by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var hasMore by remember { mutableStateOf(true) }
    var currentOffset by remember { mutableStateOf(0) }

    fun loadData(refresh: Boolean = false) {
        if (isLoading || isLoadingMore) return
        if (refresh) {
            isRefreshing = true
            currentOffset = 0
            hasMore = true
        } else if (currentOffset > 0) {
            isLoadingMore = true
        } else {
            isLoading = true
        }
        errorMessage = null

        scope.launch {
            when (selectedTab) {
                LibraryTab.LIKES -> {
                    val response = IcmRepository.getLibraryLikes(
                        limit = 50,
                        offset = if (refresh) 0 else currentOffset
                    )
                    if (response != null) {
                        val newItems = response.items
                        likedTracks = if (refresh || currentOffset == 0) {
                            newItems
                        } else {
                            likedTracks + newItems
                        }
                        hasMore = newItems.size >= 50
                        if (newItems.isNotEmpty()) {
                            currentOffset += newItems.size
                        }
                    } else {
                        errorMessage = if (currentOffset == 0) "Failed to load likes" else null
                    }
                }
                LibraryTab.SUBSCRIPTIONS -> {
                    val response = IcmRepository.getLibrarySubscriptions(
                        limit = 50,
                        offset = if (refresh) 0 else currentOffset
                    )
                    if (response != null) {
                        val newItems = response.items
                        subscriptions = if (refresh || currentOffset == 0) {
                            newItems
                        } else {
                            subscriptions + newItems
                        }
                        hasMore = newItems.size >= 50
                        if (newItems.isNotEmpty()) {
                            currentOffset += newItems.size
                        }
                    } else {
                        errorMessage = if (currentOffset == 0) "Failed to load subscriptions" else null
                    }
                }
            }
            isLoading = false
            isRefreshing = false
            isLoadingMore = false
        }
    }

    // Load initial data when tab changes
    LaunchedEffect(selectedTab) {
        likedTracks = emptyList()
        subscriptions = emptyList()
        currentOffset = 0
        hasMore = true
        errorMessage = null
        loadData(refresh = true)
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
            Column {
                Text(
                    text = "Library",
                    fontSize = 34.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
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

        // Content
        Box(modifier = Modifier.fillMaxSize()) {
            when {
                isLoading && !isRefreshing -> {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center),
                        color = Color(0xFFFC3C44)
                    )
                }
                errorMessage != null && likedTracks.isEmpty() && subscriptions.isEmpty() -> {
                    ErrorState(
                        message = errorMessage ?: "Unknown error",
                        onRetry = { loadData(refresh = true) }
                    )
                }
                selectedTab == LibraryTab.LIKES -> {
                    if (likedTracks.isEmpty() && !isLoading) {
                        EmptyState("No liked tracks yet")
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(vertical = 8.dp)
                        ) {
                            items(likedTracks, key = { it.id }) { track ->
                                LikedTrackItem(
                                    track = track,
                                    onClick = { playLibraryTrack(context, track) },
                                    onNavigateToAlbum = onNavigateToAlbum
                                )
                            }
                            if (hasMore && !isLoadingMore) {
                                item {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(16.dp)
                                            .clickable { loadData() },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            "Load more",
                                            color = Color(0xFFFC3C44),
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }
                                }
                            }
                            if (isLoadingMore) {
                                item {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(16.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        CircularProgressIndicator(
                                            color = Color(0xFFFC3C44),
                                            modifier = Modifier.size(24.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                selectedTab == LibraryTab.SUBSCRIPTIONS -> {
                    if (subscriptions.isEmpty() && !isLoading) {
                        EmptyState("No subscriptions yet")
                    } else {
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
                            if (hasMore && !isLoadingMore) {
                                item {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(16.dp)
                                            .clickable { loadData() },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            "Load more",
                                            color = Color(0xFFFC3C44),
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }
                                }
                            }
                            if (isLoadingMore) {
                                item {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(16.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        CircularProgressIndicator(
                                            color = Color(0xFFFC3C44),
                                            modifier = Modifier.size(24.dp)
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
private fun LikedTrackItem(
    track: IcmLibraryTrack,
    onClick: () -> Unit,
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
            if (!track.cover.isNullOrBlank()) {
                AsyncImage(
                    model = track.cover.replace("1000x1000", "300x300"),
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
                text = track.artist ?: "Unknown Artist",
                color = Color.Gray,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        track.durationMs.let { duration ->
            if (duration > 0) {
                Text(
                    text = formatDuration(duration),
                    color = Color.Gray,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
        }
    }
}

@Composable
private fun ArtistSubscriptionItem(
    artist: IcmLibraryArtist,
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
            if (!artist.cover.isNullOrBlank()) {
                AsyncImage(
                    model = artist.cover,
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
                text = artist.name ?: "Unknown Artist",
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

private fun playLibraryTrack(context: Context, libraryTrack: IcmLibraryTrack) {
    val track = Track(
        id = libraryTrack.id,
        title = libraryTrack.title,
        artist = libraryTrack.artist ?: "Unknown Artist",
        albumName = "",
        durationMs = libraryTrack.durationMs,
        uri = Uri.parse("https://byicloud.online/track/${libraryTrack.id}"),
        coverUrl = libraryTrack.cover,
        albumId = libraryTrack.collectionId?.hashCode()?.toLong() ?: -1L
    )
    PlayerController.playNext(track, context)
}
