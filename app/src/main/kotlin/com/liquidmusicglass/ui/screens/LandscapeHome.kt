package com.liquidmusicglass.ui.screens

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import coil.compose.AsyncImage
import com.liquidmusicglass.ui.glass.liquidClickable
import com.liquidmusicglass.data.local.LocalStorage
import com.liquidmusicglass.data.local.db.FavoriteTrackEntity
import com.liquidmusicglass.data.local.db.LibraryRepository
import com.liquidmusicglass.engine.PlayerController
import com.liquidmusicglass.engine.Track

// Палитра альбомной главной — фиксированная тёмная (как альбомный плеер),
// не зависит от светлой/тёмной темы приложения. По референсу.
private val ScreenBg = Color(0xFF0A0A0A)
private val PanelBg = Color(0xFF161616)
private val Accent = Color(0xFF88C088)
private val TextPrimary = Color.White
private val TextSecondary = Color.White.copy(alpha = 0.62f)
private val TextTertiary = Color.White.copy(alpha = 0.40f)
private val Tile = Color(0xFF242424)

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
 * Альбомная/планшетная главная (референс друга): карточный layout на тёмном
 * фоне — центр (Recently Played + Favorites) и правая панель (Playing Now +
 * Queue), обе как скруглённые панели. Сайдбар и нижний плеер рисует AppRoot.
 */
@Composable
fun LandscapeHome(
    onOpenPlayer: () -> Unit,
    onNavigateToArtist: (String) -> Unit,
    profileName: String?,
) {
    val context = LocalContext.current
    val libraryRepo = remember { LibraryRepository.getInstance(context) }
    val favorites by libraryRepo.favoritesFlow.collectAsState(initial = emptyList())
    val queue by PlayerController.queueFlow.collectAsState()
    val currentTrack by PlayerController.currentTrack.collectAsState()

    val recent = remember { LocalStorage.getHistory(context).take(12) }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(ScreenBg)
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ── Центральная панель ──
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .clip(RoundedCornerShape(22.dp))
                .background(PanelBg)
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp)
            ) {
                item {
                    Text("Home", color = TextSecondary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Text(
                        profileName?.takeIf { it.isNotBlank() } ?: "LMG",
                        color = TextPrimary, fontSize = 28.sp, fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(20.dp))
                }

                if (recent.isNotEmpty()) {
                    item {
                        Text("Recently Played", color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(12.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            items(recent, key = { it.trackId }) { h ->
                                RecentCard(h.title, h.artist, h.coverUrl) {
                                    PlayerController.playTrackById(context, h.trackId)
                                    onOpenPlayer()
                                }
                            }
                        }
                        Spacer(Modifier.height(24.dp))
                    }
                }

                item {
                    Text(
                        "Favorites  ${if (favorites.isNotEmpty()) "(${favorites.size})" else ""}",
                        color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(8.dp))
                }
                itemsIndexed(favorites, key = { _, f -> f.trackId }) { index, fav ->
                    TrackRow(fav.title, fav.artistName ?: "Unknown Artist", fav.imageUrl) {
                        PlayerController.playFromList(context, favorites.map { it.toTrack() }, index)
                        onOpenPlayer()
                    }
                }
                if (favorites.isEmpty()) {
                    item {
                        Text("No liked tracks yet", color = TextTertiary, fontSize = 14.sp,
                            modifier = Modifier.padding(vertical = 24.dp))
                    }
                }
            }
        }

        // ── Правая панель: Playing Now + Queue ──
        Box(
            modifier = Modifier
                .width(340.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(22.dp))
                .background(PanelBg)
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(18.dp)
            ) {
                item {
                    Text("Playing Now", color = Accent, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(10.dp))
                    val cur = currentTrack
                    if (cur != null) {
                        TrackRow(cur.title, cur.artist, cur.coverUrl ?: cur.albumArtUri.toString(),
                            highlight = true, onClick = onOpenPlayer)
                    } else {
                        Text("Nothing playing", color = TextTertiary, fontSize = 14.sp)
                    }
                    Spacer(Modifier.height(22.dp))
                    Text("Queue (Up Next)", color = Accent, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(10.dp))
                }
                items(queue, key = { it.id }) { t ->
                    TrackRow(t.title, t.artist, t.coverUrl ?: t.albumArtUri.toString()) {
                        val idx = queue.indexOfFirst { it.id == t.id }
                        if (idx >= 0) PlayerController.playTrack(context, idx)
                    }
                }
            }
        }
    }
}

@Composable
private fun RecentCard(title: String, artist: String, cover: String?, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .width(150.dp)
            .clip(RoundedCornerShape(14.dp))
            .liquidClickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .size(150.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Tile)
        ) {
            if (!cover.isNullOrBlank()) {
                AsyncImage(model = cover, contentDescription = null,
                    contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(title, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(artist, color = TextSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun TrackRow(title: String, artist: String, cover: String?, highlight: Boolean = false, onClick: () -> Unit) {
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
                .background(Tile)
        ) {
            if (!cover.isNullOrBlank()) {
                AsyncImage(model = cover, contentDescription = null,
                    contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(artist, color = TextSecondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (highlight) {
            Icon(Icons.Rounded.PlayArrow, null, tint = Accent, modifier = Modifier.size(22.dp))
        }
    }
}
