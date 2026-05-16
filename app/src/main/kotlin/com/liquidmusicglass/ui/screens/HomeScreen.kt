package com.liquidmusicglass.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Radio
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.liquidmusicglass.engine.PlayerController
import com.liquidmusicglass.engine.Track
import com.liquidmusicglass.ui.glass.AlbumArtImage
import com.liquidmusicglass.ui.theme.LiquidTheme

private val AppleRed = Color(0xFFFC3C44)

@Composable
fun HomeScreen(onOpenStats: () -> Unit = {}, onOpenRadio: () -> Unit = {}) {
    val context = LocalContext.current
    val scroll = rememberScrollState()
    val screenBackdrop = rememberLayerBackdrop()
    val lc = LiquidTheme.colors

    val allTracks by PlayerController.queueFlow.collectAsState()
    val recentlyPlayed by PlayerController.recentlyPlayed.collectAsState()
    val currentTrack by PlayerController.currentTrack.collectAsState()

    // Рекомендации: треки того же артиста + рандомные, исключая недавние
    val recommendations = remember(allTracks, recentlyPlayed, currentTrack) {
        val recentIds = recentlyPlayed.map { it.id }.toSet()
        val currentArtist = currentTrack?.artist

        val sameArtist = if (currentArtist != null) {
            allTracks.filter { it.artist == currentArtist && it.id !in recentIds }
        } else emptyList()

        val others = allTracks.filter { it.id !in recentIds && it !in sameArtist }.shuffled()

        (sameArtist.shuffled().take(8) + others.take(12))
            .distinctBy { it.id }
            .take(15)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // ── Ambient background ──
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(LiquidTheme.colors.screenBackground)
                .layerBackdrop(screenBackdrop)
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .padding(vertical = 16.dp)
        ) {
            Spacer(modifier = Modifier.windowInsetsTopHeight(WindowInsets.statusBars))

            Text(
                text = "Home",
                fontWeight = FontWeight.Bold,
                fontSize = 32.sp,
                color = LiquidTheme.colors.textPrimary,
                modifier = Modifier.padding(horizontal = 20.dp)
            )

            Spacer(modifier = Modifier.height(28.dp))

            // ─── Recently Played ───
            if (recentlyPlayed.isNotEmpty()) {
                SectionHeader(title = "Recently Played")
                Spacer(modifier = Modifier.height(12.dp))
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    items(recentlyPlayed.take(15), key = { "recent_${it.id}" }) { track ->
                        RecentTrackCard(
                            track = track,
                            backdrop = screenBackdrop,
                            onClick = {
                                val idx = allTracks.indexOfFirst { it.id == track.id }
                                if (idx >= 0) PlayerController.playTrack(context, idx)
                            }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(32.dp))
            }

            // ─── For You ───
            if (recommendations.isNotEmpty()) {
                SectionHeader(title = "For You")
                Spacer(modifier = Modifier.height(12.dp))
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    items(recommendations, key = { "rec_${it.id}" }) { track ->
                        RecommendationCard(
                            track = track,
                            backdrop = screenBackdrop,
                            onClick = {
                                val idx = allTracks.indexOfFirst { it.id == track.id }
                                if (idx >= 0) PlayerController.playTrack(context, idx)
                            }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(32.dp))
            }

            // ─── Mixes ───
            if (allTracks.isNotEmpty()) {
                SectionHeader(title = "Mixes")
                Spacer(modifier = Modifier.height(12.dp))

                val artistGroups = remember(allTracks) {
                    allTracks.groupBy { it.artist }
                        .filter { it.value.size >= 2 }
                        .entries
                        .sortedByDescending { it.value.size }
                        .take(8)
                }

                LazyRow(
                    contentPadding = PaddingValues(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    items(artistGroups, key = { it.key }) { (artist, tracks) ->
                        ArtistMixCard(
                            artistName = artist,
                            tracks = tracks,
                            backdrop = screenBackdrop,
                            onClick = {
                                val idx = allTracks.indexOfFirst { it.id == tracks.first().id }
                                if (idx >= 0) PlayerController.playTrack(context, idx)
                            }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(32.dp))
            }

            // ─── Quick Picks (вертикальный список) ───
            if (allTracks.isNotEmpty()) {
                SectionHeader(title = "Quick Picks")
                Spacer(modifier = Modifier.height(12.dp))

                val quickPicks = remember(allTracks) {
                    allTracks.shuffled().take(6)
                }

                Column(
                    modifier = Modifier.padding(horizontal = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    quickPicks.forEach { track ->
                        QuickPickRow(
                            track = track,
                            backdrop = screenBackdrop,
                            onClick = {
                                val idx = allTracks.indexOfFirst { it.id == track.id }
                                if (idx >= 0) PlayerController.playTrack(context, idx)
                            }
                        )
                    }
                }
            }

            // ─── Your Stats ───
            Spacer(modifier = Modifier.height(16.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .drawBackdrop(
                        backdrop = screenBackdrop,
                        shape = { RoundedCornerShape(18.dp) },
                        effects = {
                            vibrancy(); blur(4.dp.toPx())
                            lens(24.dp.toPx(), 34.dp.toPx(), chromaticAberration = true)
                        },
                        highlight = { Highlight.Ambient },
                        shadow = { Shadow(radius = 6.dp, color = Color.Black.copy(alpha = 0.12f)) },
                        innerShadow = { InnerShadow(radius = 3.dp, alpha = 0.2f) },
                        onDrawSurface = {
                            drawRect(Color.White.copy(alpha = 0.03f))
                            drawRect(Color.White.copy(alpha = 0.22f), style = Stroke(width = 1.dp.toPx()))
                        }
                    )
                    .clip(RoundedCornerShape(18.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onOpenStats
                    )
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Rounded.Equalizer,
                        contentDescription = null,
                        tint = AppleRed,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Text("Your Statistics", color = lc.textPrimary, fontSize = 16.sp,
                        fontWeight = FontWeight.Medium)
                    Spacer(Modifier.weight(1f))
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                        contentDescription = null,
                        tint = lc.iconMuted,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            // ─── Radio ───
            Spacer(modifier = Modifier.height(10.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .drawBackdrop(
                        backdrop = screenBackdrop,
                        shape = { RoundedCornerShape(18.dp) },
                        effects = {
                            vibrancy(); blur(4.dp.toPx())
                            lens(20.dp.toPx(), 32.dp.toPx(), chromaticAberration = true)
                        },
                        highlight = { Highlight.Ambient },
                        shadow = { Shadow(radius = 6.dp, color = Color.Black.copy(alpha = 0.12f)) },
                        innerShadow = { InnerShadow(radius = 3.dp, alpha = 0.2f) },
                        onDrawSurface = {
                            drawRect(Color.White.copy(alpha = 0.03f))
                            drawRect(Color.White.copy(alpha = 0.22f), style = Stroke(width = 1.dp.toPx()))
                        }
                    )
                    .clip(RoundedCornerShape(18.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onOpenRadio
                    )
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Rounded.Radio,
                        contentDescription = null,
                        tint = AppleRed,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Text("Online Radio", color = lc.textPrimary, fontSize = 16.sp,
                        fontWeight = FontWeight.Medium)
                    Spacer(Modifier.weight(1f))
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                        contentDescription = null,
                        tint = lc.iconMuted,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(200.dp))
        }
    }
}

// ═══════════════════════════════════════════════════════════
//  Components
// ═══════════════════════════════════════════════════════════

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        color = LiquidTheme.colors.textPrimary,
        modifier = Modifier.padding(horizontal = 20.dp)
    )
}

@Composable
private fun RecentTrackCard(
    track: Track,
    backdrop: LayerBackdrop,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(16.dp)

    Column(
        modifier = Modifier
            .width(140.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
    ) {
        Box(
            modifier = Modifier
                .size(140.dp)
                .clip(shape)
        ) {
            AlbumArtImage(
                uri = track.albumArtUri,
                audioFileUri = track.uri,
                albumId = track.albumId,
                coverUrl = track.coverUrl,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )

            // Bottom fade
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .align(Alignment.BottomCenter)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.5f))
                        )
                    )
            )

            // Play button
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp)
                    .size(28.dp)
                    .drawBackdrop(
                        backdrop = backdrop,
                        shape = { RoundedCornerShape(50) },
                        effects = {
                            blur(4.dp.toPx())
                            vibrancy()
                        },
                        onDrawSurface = { drawRect(Color.White.copy(alpha = 0.25f)) }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = Color.White
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = track.title,
            color = LiquidTheme.colors.textPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = track.artist,
            color = LiquidTheme.colors.textSecondary,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun RecommendationCard(
    track: Track,
    backdrop: LayerBackdrop,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(20.dp)

    Column(
        modifier = Modifier
            .width(160.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
    ) {
        Box(
            modifier = Modifier
                .size(160.dp)
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { shape },
                    effects = {
                        blur(4.dp.toPx())
                        vibrancy()
                        lens(
                            refractionHeight = 16.dp.toPx(),
                            refractionAmount = -40.dp.toPx(),
                            chromaticAberration = true
                        )
                    },
                    highlight = {
                        Highlight.Ambient.copy(alpha = 0.4f)
                    },
                    shadow = {
                        Shadow(radius = 6.dp, color = Color.Black.copy(alpha = 0.12f))
                    },
                    innerShadow = {
                        InnerShadow(radius = 3.dp, alpha = 0.2f)
                    },
                    onDrawSurface = {
                        drawRect(Color.White.copy(alpha = 0.06f))
                        drawRect(Color.White.copy(alpha = 0.22f), style = Stroke(width = 1.dp.toPx()))
                    }
                )
                .clip(shape)
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

        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = track.title,
            color = LiquidTheme.colors.textPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = track.artist,
            color = LiquidTheme.colors.textSecondary,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ArtistMixCard(
    artistName: String,
    tracks: List<Track>,
    backdrop: LayerBackdrop,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(24.dp)
    val coverTrack = tracks.first()

    Box(
        modifier = Modifier
            .width(200.dp)
            .height(120.dp)
            .drawBackdrop(
                backdrop = backdrop,
                shape = { shape },
                effects = {
                    blur(4.dp.toPx())
                    vibrancy()
                    lens(
                        refractionHeight = 20.dp.toPx(),
                        refractionAmount = -50.dp.toPx(),
                        chromaticAberration = true
                    )
                },
                highlight = {
                    Highlight.Ambient.copy(alpha = 0.4f)
                },
                shadow = {
                    Shadow(radius = 6.dp, color = Color.Black.copy(alpha = 0.15f))
                },
                innerShadow = {
                    InnerShadow(radius = 3.dp, alpha = 0.2f)
                },
                onDrawSurface = {
                    drawRect(Color.White.copy(alpha = 0.08f))
                    drawRect(Color.White.copy(alpha = 0.22f), style = Stroke(width = 1.dp.toPx()))
                }
            )
            .clip(shape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
    ) {
        AlbumArtImage(
            uri = coverTrack.albumArtUri,
            audioFileUri = coverTrack.uri,
            albumId = coverTrack.albumId,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.45f))
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.Bottom
        ) {
            Text(
                text = artistName,
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${tracks.size} tracks",
                color = Color.White.copy(alpha = 0.65f),
                fontSize = 12.sp
            )
        }
    }
}

@Composable
private fun QuickPickRow(
    track: Track,
    backdrop: LayerBackdrop,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(18.dp)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(68.dp)
            .drawBackdrop(
                backdrop = backdrop,
                shape = { shape },
                effects = {
                    blur(4.dp.toPx())
                    vibrancy()
                    lens(
                        refractionHeight = 20.dp.toPx(),
                        refractionAmount = 32.dp.toPx(),
                        chromaticAberration = true
                    )
                },
                highlight = {
                    Highlight.Ambient.copy(alpha = 0.3f)
                },
                shadow = {
                    Shadow(radius = 4.dp, color = Color.Black.copy(alpha = 0.10f))
                },
                innerShadow = {
                    InnerShadow(radius = 2.dp, alpha = 0.15f)
                },
                onDrawSurface = {
                    drawRect(Color.White.copy(alpha = 0.04f))
                    drawRect(Color.White.copy(alpha = 0.25f), style = Stroke(width = 1.dp.toPx()))
                }
            )
            .clip(shape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(12.dp))
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
                color = LiquidTheme.colors.textPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = track.artist,
                color = LiquidTheme.colors.textSecondary,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        val minutes = (track.durationMs / 1000 / 60).toInt()
        val seconds = ((track.durationMs / 1000) % 60).toInt()
        Text(
            text = "$minutes:${seconds.toString().padStart(2, '0')}",
            color = LiquidTheme.colors.textTertiary,
            fontSize = 12.sp
        )
    }
}
