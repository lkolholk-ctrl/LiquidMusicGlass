package com.liquidmusicglass.ui.screens

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
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import com.liquidmusicglass.engine.PlaylistManager
import com.liquidmusicglass.engine.Track
import com.liquidmusicglass.ui.glass.AlbumArtImage
import com.liquidmusicglass.ui.theme.LiquidTheme

private val AppleRed = Color(0xFFFC3C44)

@Composable
fun PlaylistDetailScreen(
    playlistId: String,
    onBack: () -> Unit
) {
    val lc = LiquidTheme.colors
    val context = LocalContext.current

    val allPlaylists by PlaylistManager.playlists.collectAsState()
    val playlist = allPlaylists.find { it.id == playlistId }
    val allTracks by PlayerController.queueFlow.collectAsState()

    if (playlist == null) {
        onBack()
        return
    }

    val tracks = remember(playlist.trackIds, allTracks) {
        PlaylistManager.getPlaylistTracks(playlistId, allTracks).distinctBy { it.id }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(horizontal = 20.dp)
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
                    playlist.name,
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
                                if (tracks.isNotEmpty()) {
                                    PlayerController.setAutoRefillContext("playlist", playlistId, playlist.name)
                                    val idx = allTracks.indexOfFirst { it.id == tracks[0].id }
                                    if (idx >= 0) PlayerController.playTrack(context, idx)
                                }
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
                                PlayerController.toggleShuffle()
                                if (tracks.isNotEmpty()) {
                                    val idx = allTracks.indexOfFirst { it.id == tracks[0].id }
                                    if (idx >= 0) PlayerController.playTrack(context, idx)
                                }
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
                        val idx = allTracks.indexOfFirst { it.id == track.id }
                        if (idx >= 0) PlayerController.playTrack(context, idx)
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

                // Remove from playlist
                Icon(
                    Icons.Rounded.Close,
                    null,
                    tint = lc.iconMuted,
                    modifier = Modifier
                        .size(18.dp)
                        .clickable(remember { MutableInteractionSource() }, null) {
                            PlaylistManager.removeTrack(playlistId, track.id)
                        }
                )
            }
            Spacer(Modifier.height(6.dp))
        }

        // Empty
        if (tracks.isEmpty()) {
            item {
                Spacer(Modifier.height(40.dp))
                Text(
                    "No tracks in this playlist.\nAdd tracks from Library!",
                    color = lc.textTertiary,
                    fontSize = 16.sp,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
            }
        }

        item { Spacer(Modifier.height(200.dp)) }
    }
}
