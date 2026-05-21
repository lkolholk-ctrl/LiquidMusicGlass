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
import androidx.compose.material.icons.rounded.Share
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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.liquidmusicglass.ui.glass.GlassKit
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
            if (!IcmRepository.isInitialized.value) {
                error = "API not initialized. Check API key in Settings."
            } else {
                val result = IcmRepository.getArtist(artistId)
                if (result == null) {
                    val lastErr = IcmRepository.lastError.value
                    error = lastErr ?: "Artist not found (ID: $artistId)"
                } else {
                    artist = result
                }
            }
        } catch (e: Exception) {
            error = e.message
        } finally {
            isLoading = false
        }
    }

    var artistTracks by remember { mutableStateOf<List<com.liquidmusicglass.engine.Track>>(emptyList()) }
    var trackDurations by remember { mutableStateOf<Map<String, Long>>(emptyMap()) }
    var isLoadingTracks by remember { mutableStateOf(false) }

    // Загружаем ВСЕ треки артиста: топ + все альбомы + синглы + featuring
    LaunchedEffect(artist) {
        val art = artist ?: return@LaunchedEffect
        isLoadingTracks = true

        // Стартуем с topSongs
        val allTracks = mutableListOf<com.liquidmusicglass.engine.Track>()
        art.topSongs.mapTo(allTracks) { it.toTrack() }

        // Собираем все album IDs: albums + singles + featuring + appearsOn + latestRelease
        val albumIds = mutableListOf<String>()
        art.albums.mapTo(albumIds) { it.id }
        art.singles.mapTo(albumIds) { it.id }
        art.featuring.mapTo(albumIds) { it.id }
        art.appearsOn.mapTo(albumIds) { it.id }
        art.latestRelease?.let { albumIds.add(it.id) }

        // Загружаем каждый альбом параллельно и собираем треки
        if (albumIds.isNotEmpty() && IcmRepository.isInitialized.value) {
            kotlinx.coroutines.coroutineScope {
                val albumResults = albumIds.map { albumId ->
                    async(Dispatchers.IO) {
                        try {
                            IcmRepository.getAlbum(albumId)
                        } catch (_: Exception) { null }
                    }
                }.awaitAll()

                for (album in albumResults.filterNotNull()) {
                    album.tracks.mapTo(allTracks) { track ->
                        com.liquidmusicglass.engine.Track(
                            id = track.id,
                            title = track.title,
                            artist = track.artist,
                            albumName = album.album.title,
                            uri = android.net.Uri.parse("https://byicloud.online/track/${track.id}"),
                            durationMs = track.durationMs,
                            albumId = album.album.id.hashCode().toLong(),
                            coverUrl = album.album.cover.replace("1000x1000", "600x600")
                        )
                    }
                }
            }
        }

        // Убираем дубликаты по ID
        val uniqueTracks = allTracks.distinctBy { it.id }
        artistTracks = uniqueTracks

        // Batch meta для длительностей (chunks по 50)
        if (uniqueTracks.isNotEmpty() && IcmRepository.isInitialized.value) {
            try {
                val durMap = mutableMapOf<String, Long>()
                uniqueTracks.map { it.id }.chunked(50).forEach { chunk ->
                    try {
                        val batch = IcmRepository.getBatchTrackMeta(chunk)
                        batch?.items?.forEach { item ->
                            if (item.duration != null && item.duration > 0) {
                                durMap[item.id] = item.durationMs
                            }
                        }
                    } catch (_: Exception) {}
                }
                trackDurations = durMap
            } catch (_: Exception) {}
        }

        isLoadingTracks = false
    }

    val albums = remember(artist) {
        (artist?.albums ?: emptyList()).distinctBy { it.id }
    }

    val similarArtistsList = remember(artist) {
        (artist?.similarArtists ?: emptyList()).distinctBy { it.id }
    }

    val artistName = artist?.name ?: "Unknown Artist"
    val coverUrl = artist?.image

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
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "Artist ID: $artistId",
                            color = Color.White.copy(alpha = 0.3f),
                            fontSize = 12.sp
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        // Retry button
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(AppleRed)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onClick = {
                                        isLoading = true
                                        error = null
                                    }
                                )
                                .padding(horizontal = 24.dp, vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("Retry", color = Color.White, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
            else -> {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    item {
                        Spacer(modifier = Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
                        Spacer(modifier = Modifier.height(12.dp))

                        // Back + Share
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Box(
                                modifier = Modifier
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
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF1A1A1A))
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                        onClick = {
                                            artist?.let { a ->
                                                val shareText = "${a.name} on Liquid Music Glass\n\nhttps://music.apple.com/artist/${a.id}"
                                                val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                                    type = "text/plain"
                                                    putExtra(android.content.Intent.EXTRA_TEXT, shareText)
                                                }
                                                val chooser = android.content.Intent.createChooser(intent, "Share Artist")
                                                chooser.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                                context.startActivity(chooser)
                                            }
                                        }
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Share,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
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
                                    coverUrl = coverUrl?.replace("1000x1000", "600x600")?.replace("1500x1500", "600x600")?.replace("300x300", "600x600"),
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

                        // Genre
                        val genre = artist?.genre
                        if (!genre.isNullOrBlank()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = genre,
                                color = Color.White.copy(alpha = 0.50f),
                                fontSize = 13.sp,
                                textAlign = TextAlign.Center,
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
                                                PlayerController.playFromList(
                                                    context = context,
                                                    tracks = artistTracks,
                                                    startIndex = 0,
                                                    autoRefillType = "artist",
                                                    autoRefillId = artistId,
                                                    autoRefillName = artist?.name
                                                )
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
                                                PlayerController.playFromList(
                                                    context = context,
                                                    tracks = shuffled,
                                                    startIndex = 0,
                                                    autoRefillType = "artist",
                                                    autoRefillId = artistId,
                                                    autoRefillName = artist?.name
                                                )
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
                        val similar = similarArtistsList
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
                                                coverUrl = sim.cover?.replace("1000x1000", "600x600")?.replace("1500x1500", "600x600")?.replace("300x300", "600x600"),
                                                modifier = Modifier.fillMaxSize(),
                                                contentScale = ContentScale.Crop
                                            )
                                        }
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Text(
                                            text = sim.displayName,
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
                    if (isLoadingTracks) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 20.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(
                                    color = AppleRed,
                                    modifier = Modifier.size(24.dp),
                                    strokeWidth = 2.dp
                                )
                            }
                        }
                    }

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
                                        // Load all artist tracks as the queue so the player
                                        // continues to the next song after this one ends.
                                        val startIdx = artistTracks.indexOfFirst { it.id == track.id }
                                            .coerceAtLeast(0)
                                        PlayerController.playFromList(
                                            context = context,
                                            tracks = artistTracks,
                                            startIndex = startIdx,
                                            autoRefillType = "artist",
                                            autoRefillId = artistId,
                                            autoRefillName = artist?.name
                                        )
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
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = track.title,
                                        color = Color.White,
                                        fontSize = 15.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false)
                                    )
                                    if (track.isExplicit) {
                                        Spacer(modifier = Modifier.width(6.dp))
                                        GlassKit.ExplicitBadge()
                                    }
                                    if (track.isCustom) {
                                        Spacer(modifier = Modifier.width(6.dp))
                                        GlassKit.VerifiedBadge()
                                    }
                                }
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = track.artist,
                                    color = Color.White.copy(alpha = 0.45f),
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            val dur = trackDurations[track.id] ?: track.durationMs
                            val min = (dur / 1000 / 60).toInt()
                            val sec = ((dur / 1000) % 60).toInt()
                            if (dur > 0) {
                                Text(
                                    text = "$min:${sec.toString().padStart(2, '0')}",
                                    color = Color.White.copy(alpha = 0.30f),
                                    fontSize = 12.sp
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                    }

                    item { Spacer(modifier = Modifier.height(200.dp)) }
                }
            }
        }
    }
}
