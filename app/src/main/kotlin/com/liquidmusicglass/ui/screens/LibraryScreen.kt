package com.liquidmusicglass.ui.screens

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
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
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
import com.liquidmusicglass.ui.theme.LiquidTheme
import kotlinx.coroutines.launch

private enum class LibraryTab { LIKES, SUBSCRIPTIONS }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onNavigateToAlbum: (String) -> Unit = {},
    onNavigateToArtist: (String) -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    var selectedTab by remember { mutableStateOf(LibraryTab.LIKES) }

    var likedTracks by remember { mutableStateOf<List<IcmLibraryTrack>>(emptyList()) }
    var subscriptions by remember { mutableStateOf<List<IcmLibraryArtist>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(selectedTab) {
        isLoading = true
        errorMessage = null
        when (selectedTab) {
            LibraryTab.LIKES -> {
                val result = IcmRepository.getLibraryLikes(limit = 200)
                result.onSuccess { response ->
                    likedTracks = response.items
                }.onFailure { e ->
                    errorMessage = e.message
                }
            }
            LibraryTab.SUBSCRIPTIONS -> {
                val result = IcmRepository.getLibrarySubscriptions(limit = 200)
                result.onSuccess { response ->
                    subscriptions = response.items
                }.onFailure { e ->
                    errorMessage = e.message
                }
            }
        }
        isLoading = false
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
                .background(Color(0xFF1C1C1E))
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

                // Tabs
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
                isLoading -> {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center),
                        color = LiquidTheme.colors.primary
                    )
                }
                errorMessage != null -> {
                    Column(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.FavoriteBorder,
                            contentDescription = null,
                            tint = Color.Gray,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = errorMessage ?: "Unknown error",
                            color = Color.Gray,
                            fontSize = 16.sp
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Link your account to see your library",
                            color = Color.DarkGray,
                            fontSize = 14.sp
                        )
                    }
                }
                selectedTab == LibraryTab.LIKES -> {
                    if (likedTracks.isEmpty()) {
                        EmptyState("No liked tracks yet")
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(vertical = 8.dp)
                        ) {
                            items(likedTracks, key = { it.id }) { track ->
                                LikedTrackItem(
                                    track = track,
                                    onClick = {
                                        scope.launch {
                                            playLibraryTrack(track)
                                        }
                                    },
                                    onNavigateToAlbum = onNavigateToAlbum
                                )
                            }
                        }
                    }
                }
                selectedTab == LibraryTab.SUBSCRIPTIONS -> {
                    if (subscriptions.isEmpty()) {
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
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (isSelected) LiquidTheme.colors.primary.copy(alpha = 0.3f)
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
        // Cover
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

        // Duration
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
        // Avatar
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

private fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}

private suspend fun playLibraryTrack(libraryTrack: IcmLibraryTrack) {
    val track = Track(
        id = libraryTrack.id.hashCode().toLong(),
        title = libraryTrack.title,
        artist = libraryTrack.artist ?: "Unknown Artist",
        album = "",
        duration = libraryTrack.durationMs,
        uri = "https://byicloud.online/track/${libraryTrack.id}",
        coverUrl = libraryTrack.cover,
        albumId = libraryTrack.collectionId?.hashCode()?.toLong() ?: -1L
    )
    PlayerController.playTrack(track)
}
