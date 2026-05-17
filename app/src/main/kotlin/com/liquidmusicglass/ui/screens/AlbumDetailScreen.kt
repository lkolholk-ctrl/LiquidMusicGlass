package com.liquidmusicglass.ui.screens

import androidx.compose.foundation.background
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
import com.liquidmusicglass.api.icm.IcmAlbumResponse
import com.liquidmusicglass.api.icm.IcmRepository
import com.liquidmusicglass.api.icm.toTrack
import com.liquidmusicglass.engine.PlayerController
import com.liquidmusicglass.engine.Track
import com.liquidmusicglass.ui.glass.AlbumArtImage

private val AppleRed = Color(0xFFFC3C44)

@Composable
fun AlbumDetailScreen(
    albumId: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current

    var album by remember { mutableStateOf<IcmAlbumResponse?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(albumId) {
        isLoading = true
        error = null
        try {
            album = IcmRepository.getAlbum(albumId)
        } catch (e: Exception) {
            error = e.message
        } finally {
            isLoading = false
        }
    }

    val albumTracks = remember(album) {
        album?.tracks?.map { it.toTrack() } ?: emptyList()
    }

    val albumName = album?.album?.title ?: "Unknown Album"
    val artistName = album?.album?.artist ?: "Unknown Artist"
    val coverUrl = album?.album?.cover
    val albumYear = album?.album?.year
    val releaseDate = album?.album?.releaseDate

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // Backdrop source removed — pure black background
        
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

                        // Album art
                        Box(
                            modifier = Modifier.fillMaxWidth(),
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(220.dp)
                                    .clip(RoundedCornerShape(16.dp))
                            ) {
                                AlbumArtImage(
                                    uri = null,
                                    coverUrl = coverUrl?.replace("1000x1000", "600x600")?.replace("600x600", "600x600"),
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(20.dp))

                        // Album info
                        Text(
                            text = albumName,
                            color = Color.White,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        val yearText = albumYear ?: releaseDate?.take(4) ?: ""
                        val subtitle = buildString {
                            append(artistName)
                            if (yearText.isNotBlank()) append(" · $yearText")
                            append(" · ${albumTracks.size} tracks")
                        }
                        Text(
                            text = subtitle,
                            color = Color.White.copy(alpha = 0.50f),
                            fontSize = 14.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp)
                        )

                        Spacer(modifier = Modifier.height(20.dp))

                        // Play All / Shuffle buttons
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            // Play All
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
                                            if (albumTracks.isNotEmpty()) {
                                                PlayerController.setQueue(albumTracks)
                                                PlayerController.playTrack(context, 0)
                                            }
                                        }
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Rounded.PlayArrow, null,
                                        tint = Color.White,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text("Play", color = Color.White, fontWeight = FontWeight.SemiBold)
                                }
                            }

                            // Shuffle
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
                                            if (albumTracks.isNotEmpty()) {
                                                val shuffled = albumTracks.shuffled()
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
                                    Icon(
                                        Icons.Rounded.Shuffle, null,
                                        tint = AppleRed,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text("Shuffle", color = Color.White, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(24.dp))
                    }

                    // Track list
                    itemsIndexed(albumTracks, key = { _, t -> t.id }) { index, track ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onClick = {
                                        // Play immediately without replacing current queue
                                        PlayerController.playNext(track, context)
                                    }
                                )
                                .padding(horizontal = 24.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${index + 1}",
                                color = Color.White.copy(alpha = 0.30f),
                                fontSize = 14.sp,
                                modifier = Modifier.width(28.dp)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = track.title,
                                    color = Color.White,
                                    fontSize = 15.sp,
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
    }
}
