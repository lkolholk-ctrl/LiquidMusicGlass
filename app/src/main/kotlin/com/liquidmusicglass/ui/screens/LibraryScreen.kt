package com.liquidmusicglass.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
import com.kyant.shapes.Capsule
import com.liquidmusicglass.engine.PlayerController
import com.liquidmusicglass.engine.PlaylistManager
import com.liquidmusicglass.engine.Track
import com.liquidmusicglass.ui.glass.AlbumArtImage
import com.liquidmusicglass.ui.theme.LiquidTheme

private enum class LibraryTab(val label: String) {
    Albums("Albums"),
    Tracks("Tracks"),
    Artists("Artists"),
    Playlists("Playlists")
}

@Composable
fun LibraryScreen(
    onNavigateToAlbum: (Long) -> Unit = {},
    onNavigateToArtist: (String) -> Unit = {},
    onNavigateToPlaylist: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val screenBackdrop = rememberLayerBackdrop()

    val allTracks by PlayerController.queueFlow.collectAsState()
    val playlists by PlaylistManager.playlists.collectAsState()

    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = LibraryTab.entries

    val albums = remember(allTracks) {
        allTracks.groupBy { it.albumId }
            .map { (albumId, tracks) ->
                AlbumInfo(
                    albumId = albumId,
                    name = tracks.first().albumName,
                    artist = tracks.first().artist,
                    trackCount = tracks.size,
                    coverTrack = tracks.first()
                )
            }
            .sortedBy { it.name }
    }

    val artists = remember(allTracks) {
        allTracks.groupBy { it.artist }
            .map { (artist, tracks) ->
                ArtistInfo(
                    name = artist,
                    trackCount = tracks.size,
                    albumCount = tracks.map { it.albumId }.distinct().size,
                    coverTrack = tracks.first()
                )
            }
            .sortedBy { it.name }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // ── Ambient background ──
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(LiquidTheme.colors.screenBackground)
                .layerBackdrop(screenBackdrop)
        )

        Column(modifier = Modifier.fillMaxSize()) {
            Spacer(modifier = Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
            Spacer(modifier = Modifier.height(16.dp))

            // Header
            Text(
                text = "Library",
                fontWeight = FontWeight.Bold,
                fontSize = 32.sp,
                color = LiquidTheme.colors.textPrimary,
                modifier = Modifier.padding(horizontal = 20.dp)
            )

            Spacer(modifier = Modifier.height(20.dp))

            // ── Tab bar (glass capsule) ──
            TabBar(
                tabs = tabs.map { it.label },
                selectedIndex = selectedTab,
                onTabSelected = { selectedTab = it },
                backdrop = screenBackdrop,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
            )

            Spacer(modifier = Modifier.height(16.dp))

            // ── Content ──
            when (tabs[selectedTab]) {
                LibraryTab.Albums -> AlbumsGrid(
                    albums = albums,
                    onAlbumClick = { album ->
                        onNavigateToAlbum(album.albumId)
                    }
                )
                LibraryTab.Tracks -> TracksList(
                    tracks = allTracks,
                    screenBackdrop = screenBackdrop,
                    onTrackClick = { track ->
                        val idx = allTracks.indexOfFirst { it.id == track.id }
                        if (idx >= 0) PlayerController.playTrack(context, idx)
                    }
                )
                LibraryTab.Artists -> ArtistsList(
                    artists = artists,
                    screenBackdrop = screenBackdrop,
                    onArtistClick = { artist ->
                        onNavigateToArtist(artist.name)
                    }
                )
                LibraryTab.Playlists -> PlaylistsList(
                    playlists = playlists,
                    onPlaylistClick = { onNavigateToPlaylist(it.id) },
                    onCreatePlaylist = {
                        PlaylistManager.create("Playlist ${playlists.size + 1}")
                    }
                )
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════
//  Data classes
// ═══════════════════════════════════════════════════════════

private data class AlbumInfo(
    val albumId: Long,
    val name: String,
    val artist: String,
    val trackCount: Int,
    val coverTrack: Track
)

private data class ArtistInfo(
    val name: String,
    val trackCount: Int,
    val albumCount: Int,
    val coverTrack: Track
)

// ═══════════════════════════════════════════════════════════
//  Tab Bar
// ═══════════════════════════════════════════════════════════

@Composable
private fun TabBar(
    tabs: List<String>,
    selectedIndex: Int,
    onTabSelected: (Int) -> Unit,
    backdrop: LayerBackdrop,
    modifier: Modifier = Modifier
) {
    val AppleRed = Color(0xFFFC3C44)

    // Animated pill position (0f, 1f, 2f for 3 tabs)
    val animatedIndex by animateFloatAsState(
        targetValue = selectedIndex.toFloat(),
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "tabPill"
    )

    // Outer glass capsule
    Box(
        modifier = modifier
            .height(48.dp)
            .drawBackdrop(
                backdrop = backdrop,
                shape = { Capsule() },
                effects = {
                    vibrancy()
                    blur(4.dp.toPx())
                    lens(24.dp.toPx(), 32.dp.toPx(), chromaticAberration = true)
                },
                highlight = {
                    Highlight.Ambient
                },
                shadow = {
                    Shadow(radius = 8.dp, color = Color.Black.copy(alpha = 0.15f))
                },
                innerShadow = {
                    InnerShadow(radius = 5.dp, alpha = 0.3f)
                },
                onDrawSurface = {
                    drawRect(Color.White.copy(alpha = 0.03f))
                    drawRect(
                        color = Color.White.copy(alpha = 0.10f),
                        style = Stroke(width = 1.dp.toPx())
                    )
                }
            )
            .padding(4.dp)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // ── Sliding red pill ──
            val tabCount = tabs.size
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val tabWidth = size.width / tabCount
                        translationX = animatedIndex * tabWidth
                    }
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(1f / tabCount)
                        .drawBackdrop(
                            backdrop = backdrop,
                            shape = { Capsule() },
                            effects = {
                                vibrancy()
                                blur(4.dp.toPx())
                                lens(14.dp.toPx(), 22.dp.toPx(), chromaticAberration = true)
                            },
                            highlight = {
                                Highlight.Default.copy(alpha = 0.7f)
                            },
                            shadow = {
                                Shadow(radius = 6.dp, color = AppleRed.copy(alpha = 0.4f))
                            },
                            innerShadow = {
                                InnerShadow(radius = 4.dp, alpha = 0.3f)
                            },
                            onDrawSurface = {
                                drawRect(AppleRed.copy(alpha = 0.85f))
                            }
                        )
                )
            }

            // ── Tab labels ──
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                tabs.forEachIndexed { index, label ->
                    val textColor by animateColorAsState(
                        targetValue = if (index == selectedIndex) Color.White
                        else LiquidTheme.colors.textSecondary,
                        animationSpec = tween(200),
                        label = "tabText"
                    )

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxSize()
                            .clip(RoundedCornerShape(50))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = { onTabSelected(index) }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = label,
                            color = textColor,
                            fontSize = 14.sp,
                            fontWeight = if (index == selectedIndex) FontWeight.Bold else FontWeight.Medium
                        )
                    }
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════
//  Albums Grid
// ═══════════════════════════════════════════════════════════

@Composable
private fun AlbumsGrid(
    albums: List<AlbumInfo>,
    onAlbumClick: (AlbumInfo) -> Unit
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        contentPadding = PaddingValues(
            start = 20.dp, end = 20.dp,
            top = 4.dp, bottom = 200.dp
        ),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        items(albums, key = { it.albumId }) { album ->
            AlbumCard(album = album, onClick = { onAlbumClick(album) })
        }
    }
}

@Composable
private fun AlbumCard(
    album: AlbumInfo,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(14.dp)

    Column(
        modifier = Modifier
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(shape)
        ) {
            AlbumArtImage(
                uri = album.coverTrack.albumArtUri,
                audioFileUri = album.coverTrack.uri,
                albumId = album.albumId,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = album.name,
            color = LiquidTheme.colors.textPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = "${album.artist} · ${album.trackCount} tracks",
            color = LiquidTheme.colors.textSecondary,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

// ═══════════════════════════════════════════════════════════
//  Tracks List
// ═══════════════════════════════════════════════════════════

@Composable
private fun TracksList(
    tracks: List<Track>,
    screenBackdrop: LayerBackdrop,
    onTrackClick: (Track) -> Unit
) {
    LazyColumn(
        contentPadding = PaddingValues(
            start = 20.dp, end = 20.dp,
            top = 4.dp, bottom = 200.dp
        ),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        items(tracks, key = { it.id }) { track ->
            TrackRow(track = track, backdrop = screenBackdrop, onClick = { onTrackClick(track) })
        }
    }
}

@Composable
private fun TrackRow(
    track: Track,
    backdrop: LayerBackdrop,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .drawBackdrop(
                backdrop = backdrop,
                shape = { shape },
                effects = {
                    blur(4.dp.toPx())
                    vibrancy()
                    lens(
                        refractionHeight = 18.dp.toPx(),
                        refractionAmount = 28.dp.toPx(),
                        chromaticAberration = true
                    )
                },
                highlight = { Highlight.Ambient.copy(alpha = 0.3f) },
                shadow = { Shadow(radius = 3.dp, color = Color.Black.copy(alpha = 0.08f)) },
                innerShadow = { InnerShadow(radius = 2.dp, alpha = 0.12f) },
                onDrawSurface = {
                    drawRect(Color.White.copy(alpha = 0.03f))
                    drawRect(Color.White.copy(alpha = 0.22f), style = Stroke(width = 1.dp.toPx()))
                }
            )
            .clip(shape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(RoundedCornerShape(10.dp))
        ) {
            AlbumArtImage(
                uri = track.albumArtUri,
                audioFileUri = track.uri,
                albumId = track.albumId,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }

        Spacer(modifier = Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = track.title,
                color = LiquidTheme.colors.textPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "${track.artist} · ${track.albumName}",
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
            fontSize = 12.sp,
            modifier = Modifier.padding(start = 8.dp)
        )
    }
}

// ═══════════════════════════════════════════════════════════
//  Artists List
// ═══════════════════════════════════════════════════════════

@Composable
private fun ArtistsList(
    artists: List<ArtistInfo>,
    screenBackdrop: LayerBackdrop,
    onArtistClick: (ArtistInfo) -> Unit
) {
    LazyColumn(
        contentPadding = PaddingValues(
            start = 20.dp, end = 20.dp,
            top = 4.dp, bottom = 200.dp
        ),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        items(artists, key = { it.name }) { artist ->
            ArtistRow(artist = artist, backdrop = screenBackdrop, onClick = { onArtistClick(artist) })
        }
    }
}

@Composable
private fun ArtistRow(
    artist: ArtistInfo,
    backdrop: LayerBackdrop,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(16.dp)
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
                        refractionHeight = 18.dp.toPx(),
                        refractionAmount = 28.dp.toPx(),
                        chromaticAberration = true
                    )
                },
                highlight = { Highlight.Ambient.copy(alpha = 0.3f) },
                shadow = { Shadow(radius = 3.dp, color = Color.Black.copy(alpha = 0.08f)) },
                innerShadow = { InnerShadow(radius = 2.dp, alpha = 0.12f) },
                onDrawSurface = {
                    drawRect(Color.White.copy(alpha = 0.03f))
                    drawRect(Color.White.copy(alpha = 0.22f), style = Stroke(width = 1.dp.toPx()))
                }
            )
            .clip(shape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Circular avatar with album art
        Box(
            modifier = Modifier
                .size(50.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.06f))
        ) {
            AlbumArtImage(
                uri = artist.coverTrack.albumArtUri,
                audioFileUri = artist.coverTrack.uri,
                albumId = artist.coverTrack.albumId,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }

        Spacer(modifier = Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = artist.name,
                color = LiquidTheme.colors.textPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "${artist.trackCount} tracks · ${artist.albumCount} albums",
                color = LiquidTheme.colors.textSecondary,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}


// ═══════════════════════════════════════════════════════════
//  Playlists list
// ═══════════════════════════════════════════════════════════

@Composable
private fun PlaylistsList(
    playlists: List<PlaylistManager.Playlist>,
    onPlaylistClick: (PlaylistManager.Playlist) -> Unit,
    onCreatePlaylist: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 200.dp)
    ) {
        // Create playlist button
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onCreatePlaylist
                    )
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Rounded.Add,
                    contentDescription = null,
                    tint = Color(0xFFFC3C44),
                    modifier = Modifier.size(28.dp)
                )
                Spacer(Modifier.width(14.dp))
                Text(
                    "Create Playlist",
                    color = Color(0xFFFC3C44),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Spacer(Modifier.height(8.dp))
        }

        // Playlist items
        items(playlists, key = { it.id }) { playlist ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { onPlaylistClick(playlist) }
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Playlist icon
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .background(
                            Color(0xFFFC3C44).copy(alpha = 0.15f),
                            RoundedCornerShape(12.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.MusicNote,
                        contentDescription = null,
                        tint = Color(0xFFFC3C44),
                        modifier = Modifier.size(24.dp)
                    )
                }
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = playlist.name,
                        color = LiquidTheme.colors.textPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${playlist.trackIds.size} tracks",
                        color = LiquidTheme.colors.textSecondary,
                        fontSize = 13.sp
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
        }

        // Empty state
        if (playlists.isEmpty()) {
            item {
                Spacer(Modifier.height(40.dp))
                Text(
                    "No playlists yet.\nTap + to create one!",
                    color = LiquidTheme.colors.textTertiary,
                    fontSize = 15.sp,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}
