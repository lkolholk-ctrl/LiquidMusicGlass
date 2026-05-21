package com.liquidmusicglass.ui.player

import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.liquidmusicglass.engine.PlayerController
import com.liquidmusicglass.engine.Track
import com.liquidmusicglass.ui.glass.AlbumArtImage
import com.liquidmusicglass.ui.glass.AlbumColors

private val AppleRed = Color(0xFFFC3C44)

/**
 * Queue overlay — открывается как LyricsScreen: fadeIn/fadeOut поверх FullPlayer.
 * НЕ использует slideIn/slideOut — только прозрачность.
 *
 * Фон: динамический из обложки (как LyricsBackground).
 * Кнопки управления: те же что в FullPlayer (Shuffle, Prev, Play/Pause, Next, Repeat).
 * Без ползунка громкости.
 */
@Composable
fun QueueSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    albumArtUri: Uri? = null,
    coverUrl: String? = null,
    audioFileUri: Uri? = null,
    albumId: Long = -1L,
    albumColors: AlbumColors? = null,
    currentTrack: Track? = null,
    isPlaying: Boolean = false,
    onPlayPause: () -> Unit = {},
    onSkipNext: () -> Unit = {},
    onSkipPrevious: () -> Unit = {},
    onSeek: (Long) -> Unit = {},
    currentPositionMs: Long = 0L,
    durationMs: Long = 0L
) {
    val context = LocalContext.current
    val shuffleEnabled by PlayerController.shuffleEnabled.collectAsState()
    val repeatMode by PlayerController.repeatMode.collectAsState()

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(400)),
        exit = fadeOut(tween(350))
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Dynamic background from album art
            if (albumColors != null) {
                AnimatedPlayerBackground(
                    albumArtUri = albumArtUri,
                    coverUrl = coverUrl,
                    audioFileUri = audioFileUri,
                    albumId = albumId,
                    albumColors = albumColors
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF1C1C2E))
                )
            }

            // Dark scrim for readability
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.35f))
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .windowInsetsPadding(WindowInsets.navigationBars)
            ) {
                // Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Queue",
                        color = Color.White,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(50))
                            .background(Color.White.copy(alpha = 0.10f))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = onDismiss
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }

                val queue = PlayerController.getCurrentQueue()
                val currentIndex = PlayerController.getCurrentIndex()

                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(0.dp)
                ) {
                    // Now Playing section
                    if (currentTrack != null) {
                        item(key = "now_playing") {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 20.dp)
                            ) {
                                // Current track row
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(60.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                    ) {
                                        AlbumArtImage(
                                            uri = currentTrack.albumArtUri,
                                            coverUrl = currentTrack.coverUrl,
                                            audioFileUri = currentTrack.uri,
                                            albumId = currentTrack.albumId,
                                            modifier = Modifier.fillMaxSize(),
                                            contentScale = ContentScale.Crop
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(14.dp))
                                    Column(
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text(
                                            text = currentTrack.title,
                                            color = Color.White,
                                            fontSize = 16.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = currentTrack.artist,
                                            color = Color.White.copy(alpha = 0.60f),
                                            fontSize = 14.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(20.dp))
                            }
                        }
                    }

                    // Up Next section
                    val upNext = if (currentIndex >= 0 && currentIndex < queue.lastIndex) {
                        queue.subList(currentIndex + 1, queue.size)
                    } else emptyList()

                    if (upNext.isNotEmpty()) {
                        item(key = "upnext_header") {
                            Text(
                                text = "Up Next",
                                color = Color.White.copy(alpha = 0.50f),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(
                                    horizontal = 20.dp,
                                    vertical = 8.dp
                                )
                            )
                        }
                        itemsIndexed(
                            upNext,
                            key = { index, track -> "upnext_${index}_${track.id}" }
                        ) { idx, track ->
                            QueueTrackRow(
                                track = track,
                                isPlaying = false,
                                showDragHandle = true,
                                onClick = {
                                    PlayerController.playTrack(
                                        context,
                                        currentIndex + 1 + idx
                                    )
                                }
                            )
                        }
                    }

                    if (queue.isEmpty()) {
                        item {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(
                                        imageVector = Icons.Rounded.MusicNote,
                                        contentDescription = null,
                                        tint = Color.White.copy(alpha = 0.25f),
                                        modifier = Modifier.size(48.dp)
                                    )
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Text(
                                        text = "Queue is empty",
                                        color = Color.White.copy(alpha = 0.40f),
                                        fontSize = 16.sp
                                    )
                                }
                            }
                        }
                    }

                    item { Spacer(modifier = Modifier.height(16.dp)) }
                }

                // Bottom controls — same as FullPlayer
                if (currentTrack != null && durationMs > 0) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp)
                            .padding(bottom = 16.dp)
                    ) {
                        // Progress bar
                        QueueProgressBar(
                            positionMs = currentPositionMs,
                            durationMs = durationMs,
                            onSeek = onSeek
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        // Transport controls — FullPlayer style
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Shuffle
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null
                                    ) { PlayerController.toggleShuffle() },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Shuffle,
                                    contentDescription = null,
                                    tint = if (shuffleEnabled) AppleRed
                                    else Color.White.copy(alpha = 0.40f),
                                    modifier = Modifier.size(24.dp)
                                )
                            }

                            // Previous
                            Box(
                                modifier = Modifier
                                    .size(56.dp)
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                        onClick = onSkipPrevious
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.FastRewind,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(36.dp)
                                )
                            }

                            // Play/Pause
                            Box(
                                modifier = Modifier
                                    .size(72.dp)
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                        onClick = onPlayPause
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(48.dp)
                                )
                            }

                            // Next
                            Box(
                                modifier = Modifier
                                    .size(56.dp)
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                        onClick = onSkipNext
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.FastForward,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(36.dp)
                                )
                            }

                            // Repeat
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null
                                    ) { PlayerController.cycleRepeatMode() },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = if (repeatMode == 2) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                                    contentDescription = null,
                                    tint = if (repeatMode > 0) AppleRed
                                    else Color.White.copy(alpha = 0.40f),
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

@Composable
private fun QueueTrackRow(
    track: Track,
    isPlaying: Boolean,
    showDragHandle: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(6.dp))
        ) {
            AlbumArtImage(
                uri = track.albumArtUri,
                coverUrl = track.coverUrl,
                audioFileUri = track.uri,
                albumId = track.albumId,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = track.title,
                color = if (isPlaying) AppleRed else Color.White,
                fontSize = 14.sp,
                fontWeight = if (isPlaying) FontWeight.SemiBold else FontWeight.Normal,
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

        val minutes = (track.durationMs / 1000 / 60).toInt()
        val seconds = ((track.durationMs / 1000) % 60).toInt()
        Text(
            text = "$minutes:${seconds.toString().padStart(2, '0')}",
            color = Color.White.copy(alpha = 0.30f),
            fontSize = 12.sp
        )

        if (showDragHandle) {
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                imageVector = Icons.Rounded.DragHandle,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.25f),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun QueueProgressBar(
    positionMs: Long,
    durationMs: Long,
    onSeek: (Long) -> Unit
) {
    val progress = if (durationMs > 0) positionMs.toFloat() / durationMs.toFloat() else 0f

    Column {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Color.White.copy(alpha = 0.15f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { /* TODO: implement seek on click */ }
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(progress.coerceIn(0f, 1f))
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color.White)
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            val curMin = (positionMs / 1000 / 60).toInt()
            val curSec = ((positionMs / 1000) % 60).toInt()
            val durMin = (durationMs / 1000 / 60).toInt()
            val durSec = ((durationMs / 1000) % 60).toInt()

            Text(
                text = "$curMin:${curSec.toString().padStart(2, '0')}",
                color = Color.White.copy(alpha = 0.40f),
                fontSize = 11.sp
            )
            Text(
                text = "$durMin:${durSec.toString().padStart(2, '0')}",
                color = Color.White.copy(alpha = 0.40f),
                fontSize = 11.sp
            )
        }
    }
}
