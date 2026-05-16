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
fun AlbumDetailScreen(
    albumId: Long,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val allTracks by PlayerController.queueFlow.collectAsState()
    val screenBackdrop = rememberLayerBackdrop()

    val albumTracks = remember(allTracks, albumId) {
        allTracks.filter { it.albumId == albumId }
    }

    val albumName = albumTracks.firstOrNull()?.albumName ?: "Unknown Album"
    val artistName = albumTracks.firstOrNull()?.artist ?: "Unknown Artist"
    val coverTrack = albumTracks.firstOrNull()

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
        // Backdrop source
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

                // Album art
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    if (coverTrack != null) {
                        Box(
                            modifier = Modifier
                                .size(220.dp)
                                .clip(RoundedCornerShape(16.dp))
                        ) {
                            AlbumArtImage(
                                uri = coverTrack.albumArtUri,
                                audioFileUri = coverTrack.uri,
                                albumId = albumId,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        }
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
                Text(
                    text = "$artistName · ${albumTracks.size} tracks",
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
                                    if (albumTracks.isNotEmpty()) {
                                        val idx = allTracks.indexOfFirst { it.id == albumTracks.first().id }
                                        if (idx >= 0) PlayerController.playTrack(context, idx)
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
                                    if (albumTracks.isNotEmpty()) {
                                        val random = albumTracks.random()
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
                                val idx = allTracks.indexOfFirst { it.id == track.id }
                                if (idx >= 0) PlayerController.playTrack(context, idx)
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
