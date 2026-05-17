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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.liquidmusicglass.api.icm.IcmArtistResponse
import com.liquidmusicglass.api.icm.IcmRepository
import com.liquidmusicglass.api.icm.toTrack
import com.liquidmusicglass.engine.PlayerController
import com.liquidmusicglass.ui.glass.AlbumArtImage

private val AppleRed = Color(0xFFFC3C44)

@Composable
fun ArtistDetailScreen(
    artistId: String,
    onBack: () -> Unit,
    onNavigateToAlbum: (String) -> Unit = {},
    onNavigateToArtist: (String) -> Unit = {}
) {
    val context = LocalContext.current

    var artist by remember { mutableStateOf<IcmArtistResponse?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(artistId) {
        isLoading = true
        error = null
        try {
            artist = IcmRepository.getArtist(artistId)
        } catch (e: Exception) {
            error = e.message
        } finally {
            isLoading = false
        }
    }

    val artistTracks = remember(artist) {
        artist?.topTracks?.map { it.toTrack() } ?: emptyList()
    }

    val albums = remember(artist) {
        artist?.albums ?: emptyList()
    }

    val artistName = artist?.name ?: "Unknown Artist"
    val coverUrl = artist?.cover

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        when {
            isLoading -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        color = AppleRed,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }
            error != null -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "Error",
                            color = AppleRed,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            error ?: "Unknown error",
                            color = Color.White.copy(alpha = 0.5f),
                            fontSize = 14.sp
                        )
                    }
                }
            }
            else -> {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    item {
                        Spacer(modifier = Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
                        Spacer(modifier = Modifier.height(12.dp))

                        // Back button
                        Box(
                            modifier = Modifier
                                .padding(horizontal = 20.dp)
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF1A1A1A))
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

                        // Artist avatar
                        Box(
                            modifier = Modifier.fillMaxWidth(),
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(160.dp)
                                    .clip(CircleShape)
                            ) {
                                AlbumArtImage(
                                    uri = null,
                                    coverUrl = coverUrl?.replace("1000x1000", "600x600"),
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop
                                )
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

                        // Description / Bio
                        val description = artist?.description
                        if (!description.isNullOrBlank()) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = description,
                                color = Color.White.copy(alpha = 0.60f),
                                fontSize = 14.sp,
                                textAlign = TextAlign.Center,
                                lineHeight = 20.sp,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 24.dp)
                            )
                        }

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
                                    .clip(RoundedCornerShape(50))
                                    .background(AppleRed)
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                        onClick = {
                                            if (artistTracks.isNotEmpty()) {
                                                PlayerController.setQueue(artistTracks)
                                                PlayerController.playTrack(context, 0)
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
                                    .clip(RoundedCornerShape(50))
                                    .background(Color(0xFF1A1A1A))
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                        onClick = {
                                            if (artistTracks.isNotEmpty()) {
                                                val shuffled = artistTracks.shuffled()
                                                PlayerController.setQueue(shuffled)
                                                PlayerController.playTrack(context, 0)
                                                if (!PlayerController.shuffleEnabled.value) {
                                                    PlayerController.toggleShuffle()
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

                        // Similar Artists
                        val similar = artist?.similarArtists ?: emptyList()
                        if (similar.isNotEmpty()) {
                            Text(
                                text = "Similar Artists",
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
                                items(similar, key = { it.id }) { sim ->
                                    Column(
                                        modifier = Modifier
                                            .width(100.dp)
                                            .clickable(
                                                interactionSource = remember { MutableInteractionSource() },
                                                indication = null,
                                                onClick = { onNavigateToArtist(sim.id) }
                                            ),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(100.dp)
                                                .clip(CircleShape)
                                        ) {
                                            AlbumArtImage(
                                                uri = null,
                                                coverUrl = sim.cover?.replace("1000x1000", "600x600"),
                                                modifier = Modifier.fillMaxSize(),
                                                contentScale = ContentScale.Crop
                                            )
                                        }
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Text(
                                            text = sim.name,
                                            color = Color.White,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Medium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            textAlign = TextAlign.Center,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(28.dp))
                        }

                        // Albums section
                        if (albums.isNotEmpty()) {
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
                                items(albums, key = { it.id }) { album ->
                                    Column(
                                        modifier = Modifier
                                            .width(130.dp)
                                            .clickable(
                                                interactionSource = remember { MutableInteractionSource() },
                                                indication = null,
                                                onClick = { onNavigateToAlbum(album.id) }
                                            )
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(130.dp)
                                                .clip(RoundedCornerShape(12.dp))
                                        ) {
                                            AlbumArtImage(
                                                uri = null,
                                                coverUrl = album.cover.replace("1000x1000", "600x600"),
                                                modifier = Modifier.fillMaxSize(),
                                                contentScale = ContentScale.Crop
                                            )
                                        }
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Text(
                                            text = album.title,
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
                                .padding(horizontal = 16.dp)
                                .height(58.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0xFF1A1A1A))
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onClick = {
                                        val idx = artistTracks.indexOfFirst { it.id == track.id }
                                        if (idx >= 0) {
                                            PlayerController.setQueue(artistTracks, idx)
                                            PlayerController.playTrack(context, idx)
                                        }
                                    }
                                )
                                .padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(RoundedCornerShape(8.dp))
                            ) {
                                AlbumArtImage(
                                    uri = null,
                                    coverUrl = track.coverUrl?.replace("1000x1000", "600x600"),
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
                                    text = track.artist,
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
                        Spacer(modifier = Modifier.height(4.dp))
                    }

                    item { Spacer(modifier = Modifier.height(200.dp)) }
                }
            }
        }
    }
}
