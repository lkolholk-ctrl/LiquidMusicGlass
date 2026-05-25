package com.liquidmusicglass.ui.screens

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.liquidmusicglass.ui.glass.GlassKit
import com.liquidmusicglass.engine.PlayerController
import com.liquidmusicglass.engine.Track
import com.liquidmusicglass.ui.glass.AlbumArtImage
import com.liquidmusicglass.ui.theme.LiquidTheme
import com.liquidmusicglass.api.icm.IcmRepository
import kotlinx.coroutines.launch

private val AppleRed = Color(0xFFFC3C44)

@Composable
fun PlaylistDetailScreen(
    playlistId: String,
    onBack: () -> Unit
) {
    val lc = LiquidTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var playlistInfo by remember { mutableStateOf<com.liquidmusicglass.api.icm.IcmUserPlaylistInfo?>(null) }
    var tracks by remember { mutableStateOf<List<Track>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    // Load cloud playlist info & tracks
    LaunchedEffect(playlistId) {
        if (playlistId.isNotBlank()) {
            isLoading = true
            errorMsg = null
            scope.launch {
                val response = IcmRepository.getUserPlaylistTracks(playlistId)
                if (response != null) {
                    playlistInfo = response.playlist
                    tracks = response.tracks.mapNotNull { tr ->
                        val trackIdStr = tr.trackId?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                        val durationSec = tr.duration ?: 0L
                        val dMs = if (durationSec < 1000L) durationSec * 1000L else durationSec
                        Track(
                            id = trackIdStr,
                            title = tr.title.orEmpty(),
                            artist = tr.artist.orEmpty(),
                            albumName = "",
                            uri = Uri.parse("https://byicloud.online/track/$trackIdStr"),
                            durationMs = dMs,
                            albumId = tr.collectionId?.hashCode()?.toLong() ?: trackIdStr.hashCode().toLong(),
                            coverUrl = tr.cover?.replace("1000x1000", "600x600")
                        )
                    }
                } else {
                    errorMsg = "Failed to load cloud playlist."
                }
                isLoading = false
            }
        } else {
            errorMsg = "Invalid playlist ID."
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(horizontal = 20.dp),
        contentPadding = PaddingValues(bottom = 120.dp)
    ) {
        // Header
        item {
            Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(Color(0xFF1C1C1E), CircleShape)
                        .clip(CircleShape)
                        .clickable(remember { MutableInteractionSource() }, null) { onBack() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.AutoMirrored.Rounded.ArrowBack,
                        null,
                        tint = lc.iconDefault,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
            Spacer(Modifier.height(20.dp))
        }

        if (isLoading) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 100.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = AppleRed)
                }
            }
        } else if (errorMsg != null) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 100.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = errorMsg ?: "Unknown error occurred.",
                        color = Color.Red,
                        fontSize = 16.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            val name = playlistInfo?.name ?: "Playlist"
            // Playlist icon + name
            item {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .size(120.dp)
                            .background(Color(0xFF1C1C1E), RoundedCornerShape(28.dp))
                            .clip(RoundedCornerShape(28.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.AutoMirrored.Rounded.PlaylistPlay,
                            null,
                            tint = AppleRed,
                            modifier = Modifier.size(56.dp)
                        )
                    }

                    Spacer(Modifier.height(16.dp))
                    Text(
                        name,
                        color = lc.textPrimary,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${tracks.size} tracks",
                        color = lc.textSecondary,
                        fontSize = 15.sp
                    )
                }
                Spacer(Modifier.height(20.dp))
            }

            // Play / Shuffle buttons
            if (tracks.isNotEmpty()) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Play
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .background(AppleRed, RoundedCornerShape(50))
                                .clip(RoundedCornerShape(50))
                                .clickable(remember { MutableInteractionSource() }, null) {
                                    PlayerController.playFromList(
                                        context = context,
                                        tracks = tracks,
                                        startIndex = 0,
                                        autoRefillType = "playlist",
                                        autoRefillId = playlistId,
                                        autoRefillName = name
                                    )
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Rounded.PlayArrow,
                                    null,
                                    tint = Color.White,
                                    modifier = Modifier.size(22.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text("Play", color = Color.White, fontWeight = FontWeight.SemiBold)
                            }
                        }

                        // Shuffle
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .background(Color(0xFF1C1C1E), RoundedCornerShape(50))
                                .clip(RoundedCornerShape(50))
                                .clickable(remember { MutableInteractionSource() }, null) {
                                    PlayerController.playFromList(
                                        context = context,
                                        tracks = tracks.shuffled(),
                                        startIndex = 0,
                                        autoRefillType = "playlist",
                                        autoRefillId = playlistId,
                                        autoRefillName = name
                                    )
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Rounded.Shuffle,
                                    null,
                                    tint = AppleRed,
                                    modifier = Modifier.size(22.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    "Shuffle",
                                    color = lc.textPrimary,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }

            // Tracks
            itemsIndexed(tracks, key = { _, t -> t.id }) { index, track ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF1C1C1E), RoundedCornerShape(14.dp))
                        .clip(RoundedCornerShape(14.dp))
                        .clickable(remember { MutableInteractionSource() }, null) {
                            PlayerController.playFromList(
                                context = context,
                                tracks = tracks,
                                startIndex = index,
                                autoRefillType = "playlist",
                                autoRefillId = playlistId,
                                autoRefillName = name
                            )
                        }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "${index + 1}",
                        color = lc.textTertiary,
                        fontSize = 14.sp,
                        modifier = Modifier.width(28.dp)
                    )

                    AlbumArtImage(
                        uri = track.albumArtUri,
                        audioFileUri = track.uri,
                        albumId = track.albumId,
                        coverUrl = track.coverUrl,
                        contentDescription = null,
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(8.dp))
                    )

                    Spacer(Modifier.width(12.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                track.title,
                                color = lc.textPrimary,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            if (track.isExplicit) {
                                Spacer(Modifier.width(6.dp))
                                GlassKit.ExplicitBadge()
                            }
                            if (track.isCustom) {
                                Spacer(Modifier.width(6.dp))
                                GlassKit.VerifiedBadge()
                            }
                        }
                        Text(
                            track.artist,
                            color = lc.textSecondary,
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    if (track.durationMs > 0) {
                        Text(
                            text = formatDuration(track.durationMs),
                            color = lc.textSecondary,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
            }

            // Empty
            if (tracks.isEmpty()) {
                item {
                    Spacer(Modifier.height(40.dp))
                    Text(
                        "No tracks in this playlist.",
                        color = lc.textTertiary,
                        fontSize = 16.sp,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center
                    )
                }
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
