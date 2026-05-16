package com.liquidmusicglass.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.backdrops.layerBackdrop
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
import com.liquidmusicglass.ui.glass.AlbumArtImage

private val AppleRed = Color(0xFFFC3C44)

@Composable
fun ArtistDetailScreen(
    artistName: String,
    onBack: () -> Unit,
    onNavigateToAlbum: (Long) -> Unit
) {
    val context = LocalContext.current
    val allTracks by PlayerController.queueFlow.collectAsState()
    val screenBackdrop = rememberLayerBackdrop()

    val artistTracks = remember(allTracks, artistName) {
        allTracks.filter { it.artist == artistName }
    }

    val albums = remember(artistTracks) {
        artistTracks.groupBy { it.albumId }.map { (albumId, tracks) ->
            Triple(albumId, tracks.first().albumName, tracks.first())
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF1A1A2E),
                        Color(0xFF0F0F1A),
                        Color(0xFF080A0F)
                    )
                )
            )
    ) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .layerBackdrop(screenBackdrop)
        )

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            item {
                Spacer(modifier = Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
                Spacer(modifier = Modifier.height(12.dp))

                // Back button
                Box(
                    modifier = Modifier
                        .padding(horizontal = 20.dp)
                        .size(40.dp)
                        .drawBackdrop(
                            backdrop = screenBackdrop,
                            shape = { Capsule() },
                            effects = {
                                vibrancy()
                                blur(4.dp.toPx())
                                lens(18.dp.toPx(), 24.dp.toPx(), chromaticAberration = true)
                            },
                            highlight = {
                                Highlight.Ambient.copy(alpha = 0.5f)
                            },
                            shadow = {
                                Shadow(radius = 4.dp, color = Color.Black.copy(alpha = 0.12f))
                            },
                            innerShadow = {
                                InnerShadow(radius = 3.dp, alpha = 0.2f)
                            },
                            onDrawSurface = {
                                drawRect(Color.White.copy(alpha = 0.04f))
                                drawRect(
                                    color = Color.White.copy(alpha = 0.10f),
                                    style = Stroke(width = 1.dp.toPx())
                                )
                            }
                        )
                        .clip(CircleShape)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onBack
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(22.dp)
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Artist avatar (circular album art)
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    val coverTrack = artistTracks.firstOrNull()
                    if (coverTrack != null) {
                        Box(
                            modifier = Modifier
                                .size(160.dp)
                                .clip(CircleShape)
                        ) {
                            AlbumArtImage(
                                uri = coverTrack.albumArtUri,
                                audioFileUri = coverTrack.uri,
                                albumId = coverTrack.albumId,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = artistName,
                    color = Color.White,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "${artistTracks.size} tracks · ${albums.size} albums",
                    color = Color.White.copy(alpha = 0.50f),
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
                )

                Spacer(modifier = Modifier.height(20.dp))

                // Play All / Shuffle
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp)
                            .drawBackdrop(
                                backdrop = screenBackdrop,
                                shape = { Capsule() },
                                effects = {
                                    vibrancy()
                                    blur(4.dp.toPx())
                                    lens(16.dp.toPx(), 24.dp.toPx(), chromaticAberration = true)
                                },
                                highlight = {
                                    Highlight.Default.copy(alpha = 0.7f)
                                },
                                shadow = {
                                    Shadow(radius = 6.dp, color = AppleRed.copy(alpha = 0.3f))
                                },
                                innerShadow = {
                                    InnerShadow(radius = 3.dp, alpha = 0.2f)
                                },
                                onDrawSurface = { drawRect(AppleRed) }
                            )
                            .clip(RoundedCornerShape(50))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = {
                                    if (artistTracks.isNotEmpty()) {
                                        val idx = allTracks.indexOfFirst { it.id == artistTracks.first().id }
                                        if (idx >= 0) PlayerController.playTrack(context, idx)
                                    }
                                }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.PlayArrow, null, tint = Color.White, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Play", color = Color.White, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp)
                            .drawBackdrop(
                                backdrop = screenBackdrop,
                                shape = { Capsule() },
                                effects = {
                                    vibrancy()
                                    blur(4.dp.toPx())
                                    lens(16.dp.toPx(), 24.dp.toPx(), chromaticAberration = true)
                                },
                                onDrawSurface = {
                                    drawRect(Color.White.copy(alpha = 0.04f))
                                    drawRect(
                                        color = Color.White.copy(alpha = 0.10f),
                                        style = Stroke(width = 1.dp.toPx())
                                    )
                                }
                            )
                            .clip(RoundedCornerShape(50))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = {
                                    if (artistTracks.isNotEmpty()) {
                                        val random = artistTracks.random()
                                        val idx = allTracks.indexOfFirst { it.id == random.id }
                                        if (idx >= 0) {
                                            PlayerController.playTrack(context, idx)
                                            if (!PlayerController.shuffleEnabled.value) {
                                                PlayerController.toggleShuffle()
                                            }
                                        }
                                    }
                                }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Shuffle, null, tint = AppleRed, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Shuffle", color = Color.White, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(28.dp))

                // Albums section
                if (albums.size > 1) {
                    Text(
                        text = "Albums",
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 20.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        items(albums, key = { it.first }) { (albumId, albumName, coverTrack) ->
                            Column(
                                modifier = Modifier
                                    .width(130.dp)
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                        onClick = { onNavigateToAlbum(albumId) }
                                    )
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(130.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                ) {
                                    AlbumArtImage(
                                        uri = coverTrack.albumArtUri,
                                        audioFileUri = coverTrack.uri,
                                        albumId = albumId,
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop
                                    )
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = albumName,
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(28.dp))
                }

                // All tracks header
                Text(
                    text = "Songs",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 20.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            // Track list
            items(artistTracks, key = { it.id }) { track ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(58.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {
                                val idx = allTracks.indexOfFirst { it.id == track.id }
                                if (idx >= 0) PlayerController.playTrack(context, idx)
                            }
                        )
                        .padding(horizontal = 20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(8.dp))
                    ) {
                        AlbumArtImage(
                            uri = track.albumArtUri,
                            audioFileUri = track.uri,
                            albumId = track.albumId,
                coverUrl = track.coverUrl,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    }
                    Spacer(modifier = Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = track.title,
                            color = Color.White,
                            fontSize = 15.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = track.albumName,
                            color = Color.White.copy(alpha = 0.45f),
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    val min = (track.durationMs / 1000 / 60).toInt()
                    val sec = ((track.durationMs / 1000) % 60).toInt()
                    Text(
                        text = "$min:${sec.toString().padStart(2, '0')}",
                        color = Color.White.copy(alpha = 0.30f),
                        fontSize = 12.sp
                    )
                }
            }

            item { Spacer(modifier = Modifier.height(200.dp)) }
        }
    }
}
