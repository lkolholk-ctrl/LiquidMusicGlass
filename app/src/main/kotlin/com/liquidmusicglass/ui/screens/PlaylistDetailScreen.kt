package com.liquidmusicglass.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlaylistPlay
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.Capsule
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
    val backdrop = rememberLayerBackdrop()
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
        PlaylistManager.getPlaylistTracks(playlistId, allTracks)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp)
    ) {
        // Header
        item {
            Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .drawBackdrop(
                            backdrop = backdrop, shape = { Capsule() },
                            effects = { vibrancy(); blur(4.dp.toPx()); lens(18.dp.toPx(), 24.dp.toPx(), chromaticAberration = true) },
                            highlight = { Highlight.Ambient },
                            shadow = { Shadow(radius = 4.dp, color = Color.Black.copy(alpha = 0.12f)) },
                            innerShadow = { InnerShadow(radius = 3.dp, alpha = 0.2f) },
                            onDrawSurface = { drawRect(Color.White.copy(0.03f)); drawRect(Color.White.copy(0.22f), style = Stroke(1.dp.toPx())) }
                        )
                        .clip(CircleShape)
                        .clickable(remember { MutableInteractionSource() }, null) { onBack() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, null, tint = lc.iconDefault, modifier = Modifier.size(22.dp))
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
                        .drawBackdrop(
                            backdrop = backdrop,
                            shape = { RoundedCornerShape(28.dp) },
                            effects = { vibrancy(); blur(4.dp.toPx()); lens(24.dp.toPx(), 32.dp.toPx(), chromaticAberration = true) },
                            highlight = { Highlight.Ambient },
                            shadow = { Shadow(radius = 10.dp, color = Color.Black.copy(alpha = 0.2f)) },
                            innerShadow = { InnerShadow(radius = 5.dp, alpha = 0.3f) },
                            onDrawSurface = { drawRect(AppleRed.copy(alpha = 0.12f)); drawRect(Color.White.copy(0.20f), style = Stroke(1.dp.toPx())) }
                        )
                        .clip(RoundedCornerShape(28.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Rounded.PlaylistPlay, null, tint = AppleRed, modifier = Modifier.size(56.dp))
                }

                Spacer(Modifier.height(16.dp))
                Text(playlist.name, color = lc.textPrimary, fontSize = 24.sp,
                    fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                Spacer(Modifier.height(4.dp))
                Text("${tracks.size} tracks", color = lc.textSecondary, fontSize = 15.sp)
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
                            .drawBackdrop(
                                backdrop = backdrop, shape = { Capsule() },
                                effects = { vibrancy(); blur(4.dp.toPx()); lens(16.dp.toPx(), 24.dp.toPx(), chromaticAberration = true) },
                                highlight = { Highlight.Default.copy(alpha = 0.7f) },
                                shadow = { Shadow(radius = 6.dp, color = AppleRed.copy(alpha = 0.3f)) },
                                innerShadow = { InnerShadow(radius = 3.dp, alpha = 0.2f) },
                                onDrawSurface = { drawRect(AppleRed) }
                            )
                            .clip(RoundedCornerShape(50))
                            .clickable(remember { MutableInteractionSource() }, null) {
                                if (tracks.isNotEmpty()) {
                                    val idx = allTracks.indexOfFirst { it.id == tracks[0].id }
                                    if (idx >= 0) PlayerController.playTrack(context, idx)
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.PlayArrow, null, tint = Color.White, modifier = Modifier.size(22.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Play", color = Color.White, fontWeight = FontWeight.SemiBold)
                        }
                    }

                    // Shuffle
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .drawBackdrop(
                                backdrop = backdrop, shape = { Capsule() },
                                effects = { vibrancy(); blur(4.dp.toPx()); lens(16.dp.toPx(), 24.dp.toPx(), chromaticAberration = true) },
                                highlight = { Highlight.Ambient },
                                shadow = { Shadow(radius = 4.dp, color = Color.Black.copy(alpha = 0.10f)) },
                                innerShadow = { InnerShadow(radius = 3.dp, alpha = 0.2f) },
                                onDrawSurface = { drawRect(Color.White.copy(0.03f)); drawRect(Color.White.copy(0.20f), style = Stroke(1.dp.toPx())) }
                            )
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
                            Icon(Icons.Rounded.Shuffle, null, tint = AppleRed, modifier = Modifier.size(22.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Shuffle", color = lc.textPrimary, fontWeight = FontWeight.SemiBold)
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
                    .drawBackdrop(
                        backdrop = backdrop,
                        shape = { RoundedCornerShape(14.dp) },
                        effects = { vibrancy(); blur(4.dp.toPx()) },
                        highlight = { Highlight.Ambient.copy(alpha = 0.3f) },
                        onDrawSurface = {
                            drawRect(Color.White.copy(alpha = 0.03f))
                            drawRect(Color.White.copy(alpha = 0.22f), style = Stroke(1.dp.toPx()))
                        }
                    )
                    .clip(RoundedCornerShape(14.dp))
                    .clickable(remember { MutableInteractionSource() }, null) {
                        val idx = allTracks.indexOfFirst { it.id == track.id }
                        if (idx >= 0) PlayerController.playTrack(context, idx)
                    }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("${index + 1}", color = lc.textTertiary, fontSize = 14.sp,
                    modifier = Modifier.width(28.dp))

                AlbumArtImage(
                    uri = track.albumArtUri,
                    audioFileUri = track.uri,
                    albumId = track.albumId,
                    contentDescription = null,
                    modifier = Modifier.size(44.dp).clip(RoundedCornerShape(8.dp))
                )

                Spacer(Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(track.title, color = lc.textPrimary, fontSize = 15.sp,
                        fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(track.artist, color = lc.textSecondary, fontSize = 13.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }

                // Remove from playlist
                Icon(
                    Icons.Rounded.Close, null,
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
                Text("No tracks in this playlist.\nAdd tracks from Library!",
                    color = lc.textTertiary, fontSize = 16.sp,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center)
            }
        }

        item { Spacer(Modifier.height(200.dp)) }
    }
}
