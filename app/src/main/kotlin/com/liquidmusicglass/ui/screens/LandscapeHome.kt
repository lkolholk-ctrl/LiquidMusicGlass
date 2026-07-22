package com.liquidmusicglass.ui.screens

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.liquidmusicglass.data.local.LocalStorage
import com.liquidmusicglass.data.local.db.FavoriteTrackEntity
import com.liquidmusicglass.data.local.db.LibraryRepository
import com.liquidmusicglass.engine.PlayerController
import com.liquidmusicglass.engine.Track
import com.liquidmusicglass.ui.glass.liquidClickable
import com.liquidmusicglass.ui.glass.rememberAlbumColors
import com.liquidmusicglass.ui.theme.LiquidTheme

private val Accent = Color(0xFF88C088)

private fun FavoriteTrackEntity.toTrack(): Track = Track(
    id = trackId,
    title = title,
    artist = artistName ?: "Unknown Artist",
    albumName = albumTitle ?: "",
    uri = Uri.parse("https://byicloud.online/track/$trackId"),
    durationMs = durationMs,
    albumId = -1L,
    coverUrl = imageUrl,
    source = source,
)

/**
 * Альбомная/планшетная главная (референс друга): две колонки —
 *   центр: приветствие, Recently Played (ряд карточек), список избранного;
 *   справа (~340dp): Playing Now + очередь (Up Next).
 * Боковую навигацию и нижний мини-плеер рисует AppRoot. Дым волны здесь не
 * используется — это медиатечный layout для широкого экрана.
 */
@Composable
fun LandscapeHome(
    onOpenPlayer: () -> Unit,
    onNavigateToArtist: (String) -> Unit,
    profileName: String?,
) {
    val lc = LiquidTheme.colors
    val context = LocalContext.current
    val libraryRepo = remember { LibraryRepository.getInstance(context) }
    val favorites by libraryRepo.favoritesFlow.collectAsState(initial = emptyList())
    val queue by PlayerController.queueFlow.collectAsState()
    val currentTrack by PlayerController.currentTrack.collectAsState()
    val isPlaying by PlayerController.isPlaying.collectAsState()

    val recent = remember { LocalStorage.getHistory(context).take(12) }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(lc.settingsBackground)
            .windowInsetsPadding(WindowInsets.statusBars)
    ) {
        // ── Центральная колонка ──
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(horizontal = 24.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 20.dp, bottom = 120.dp)
        ) {
            item {
                Text("Home", color = lc.textSecondary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Text(
                    profileName?.takeIf { it.isNotBlank() } ?: "LMG",
                    color = lc.textPrimary, fontSize = 30.sp, fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(20.dp))
            }

            // Recently Played — ряд крупных карточек
            if (recent.isNotEmpty()) {
                item {
                    Text("Recently Played", color = lc.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(12.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        items(recent, key = { it.trackId }) { h ->
                            RecentCard(
                                title = h.title,
                                artist = h.artist,
                                cover = h.coverUrl,
                                onClick = {
                                    PlayerController.playTrackById(context, h.trackId)
                                    onOpenPlayer()
                                }
                            )
                        }
                    }
                    Spacer(Modifier.height(26.dp))
                }
            }

            // Favorites — вертикальный список (аналог All Songs в референсе)
            item {
                Text(
                    "Favorites  ${if (favorites.isNotEmpty()) "(${favorites.size})" else ""}",
                    color = lc.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(10.dp))
            }
            itemsIndexed(favorites) { index, fav ->
                TrackRow(
                    title = fav.title,
                    artist = fav.artistName ?: "Unknown Artist",
                    cover = fav.imageUrl,
                    onClick = {
                        PlayerController.playFromList(context, favorites.map { it.toTrack() }, index)
                        onOpenPlayer()
                    }
                )
            }
            if (favorites.isEmpty()) {
                item {
                    Text("No liked tracks yet", color = lc.textTertiary, fontSize = 14.sp,
                        modifier = Modifier.padding(vertical = 24.dp))
                }
            }
        }

        // ── Правая колонка: Playing Now + Queue ──
        Column(
            modifier = Modifier
                .width(340.dp)
                .fillMaxHeight()
                .padding(horizontal = 16.dp)
                .padding(top = 20.dp)
        ) {
            Text("Playing Now", color = Accent, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            val cur = currentTrack
            if (cur != null) {
                TrackRow(
                    title = cur.title,
                    artist = cur.artist,
                    cover = cur.coverUrl ?: cur.albumArtUri.toString(),
                    highlight = true,
                    onClick = onOpenPlayer
                )
            } else {
                Text("Nothing playing", color = lc.textTertiary, fontSize = 14.sp)
            }

            Spacer(Modifier.height(22.dp))
            Text("Queue (Up Next)", color = Accent, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            LazyColumn(
                modifier = Modifier.fillMaxHeight(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 120.dp)
            ) {
                items(queue, key = { it.id }) { t ->
                    TrackRow(
                        title = t.title,
                        artist = t.artist,
                        cover = t.coverUrl ?: t.albumArtUri.toString(),
                        onClick = {
                            val idx = queue.indexOfFirst { it.id == t.id }
                            if (idx >= 0) PlayerController.playTrack(context, idx)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun RecentCard(title: String, artist: String, cover: String?, onClick: () -> Unit) {
    val lc = LiquidTheme.colors
    Column(
        modifier = Modifier
            .width(150.dp)
            .liquidClickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .size(150.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(if (lc.isDark) Color(0xFF1C1C1E) else Color(0xFFE5E5EA))
        ) {
            if (!cover.isNullOrBlank()) {
                AsyncImage(model = cover, contentDescription = null,
                    contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(title, color = lc.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(artist, color = lc.textSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun TrackRow(
    title: String,
    artist: String,
    cover: String?,
    highlight: Boolean = false,
    onClick: () -> Unit,
) {
    val lc = LiquidTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (highlight) Accent.copy(alpha = 0.12f) else Color.Transparent)
            .liquidClickable(onClick = onClick)
            .padding(vertical = 6.dp, horizontal = 8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(if (lc.isDark) Color(0xFF1C1C1E) else Color(0xFFE5E5EA))
        ) {
            if (!cover.isNullOrBlank()) {
                AsyncImage(model = cover, contentDescription = null,
                    contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = lc.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(artist, color = lc.textSecondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (highlight) {
            Icon(Icons.Rounded.PlayArrow, null, tint = Accent, modifier = Modifier.size(22.dp))
        }
    }
}
