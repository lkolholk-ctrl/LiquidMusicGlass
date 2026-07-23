package com.liquidmusicglass.ui.screens

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.liquidmusicglass.ui.glass.liquidClickable
import com.liquidmusicglass.ui.icons.LiquidGlyphs
import com.liquidmusicglass.ui.theme.LiquidTheme
import com.liquidmusicglass.data.local.LocalStorage
import com.liquidmusicglass.data.local.db.FavoriteTrackEntity
import com.liquidmusicglass.data.local.db.LibraryRepository
import com.liquidmusicglass.engine.PlayerController
import com.liquidmusicglass.engine.Track

// Высота нижнего LandscapeBottomBar — на неё делаем отступ снизу у панелей,
// чтобы последний элемент не уезжал под мини-плеер.
private val BottomBarInset = 88.dp

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

private fun fmtDuration(ms: Long): String {
    if (ms <= 0L) return ""
    val totalSec = ms / 1000
    return "%d:%02d".format(totalSec / 60, totalSec % 60)
}

/**
 * Альбомная/планшетная главная. Раскладка — по референсу друга: слева
 * центральная панель-карточка (Recently Played + Favorites) с шапкой и
 * кнопкой поиска, справа — панель Playing Now + Queue. Сайдбар и нижний
 * мини-плеер рисует AppRoot. Стиль — НАШ ([LiquidTheme.colors]), поэтому
 * корректно и в светлой, и в тёмной теме.
 */
@Composable
fun LandscapeHome(
    onOpenPlayer: () -> Unit,
    onNavigateToArtist: (String) -> Unit,
    profileName: String?,
    onOpenSearch: () -> Unit = {},
) {
    val lc = LiquidTheme.colors
    val context = LocalContext.current
    val libraryRepo = remember { LibraryRepository.getInstance(context) }
    val favorites by libraryRepo.favoritesFlow.collectAsState(initial = emptyList())
    val queue by PlayerController.queueFlow.collectAsState()
    val currentTrack by PlayerController.currentTrack.collectAsState()

    val recent = remember { LocalStorage.getHistory(context).take(12) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(lc.settingsBackground)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // ── Центральная панель ──
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(18.dp))
                    .background(lc.cardSurface)
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = BottomBarInset)
                ) {
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Home", color = lc.textSecondary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                                Text(
                                    profileName?.takeIf { it.isNotBlank() } ?: "LMG",
                                    color = lc.textPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold
                                )
                            }
                            HeaderIconButton(LiquidGlyphs.Search, "Search", onOpenSearch)
                        }
                        Spacer(Modifier.height(16.dp))
                    }

                    if (recent.isNotEmpty()) {
                        item {
                            SectionLabel(LiquidGlyphs.History, "Recently Played")
                            Spacer(Modifier.height(10.dp))
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                items(recent, key = { it.trackId }) { h ->
                                    RecentCard(h.title, h.artist, h.coverUrl) {
                                        PlayerController.playTrackById(context, h.trackId)
                                        onOpenPlayer()
                                    }
                                }
                            }
                            Spacer(Modifier.height(18.dp))
                        }
                    }

                    item {
                        SectionLabel(
                            LiquidGlyphs.Heart,
                            "Favorites" + if (favorites.isNotEmpty()) "  (${favorites.size})" else ""
                        )
                        Spacer(Modifier.height(6.dp))
                    }
                    itemsIndexed(favorites, key = { _, f -> f.trackId }) { index, fav ->
                        TrackRow(
                            fav.title, fav.artistName ?: "Unknown Artist", fav.imageUrl,
                            duration = fmtDuration(fav.durationMs)
                        ) {
                            PlayerController.playFromList(context, favorites.map { it.toTrack() }, index)
                            onOpenPlayer()
                        }
                    }
                    if (favorites.isEmpty()) {
                        item {
                            Text(
                                "No liked tracks yet", color = lc.textTertiary, fontSize = 14.sp,
                                modifier = Modifier.padding(vertical = 24.dp)
                            )
                        }
                    }
                }
            }

            // ── Правая панель: Playing Now + Queue ──
            Box(
                modifier = Modifier
                    .width(280.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(18.dp))
                    .background(lc.cardSurface)
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 14.dp, bottom = BottomBarInset)
                ) {
                    item {
                        SectionLabel(LiquidGlyphs.GraphicEq, "Playing Now", accentLabel = true)
                        Spacer(Modifier.height(8.dp))
                        val cur = currentTrack
                        if (cur != null) {
                            TrackRow(
                                cur.title, cur.artist, cur.coverUrl ?: cur.displayArtUri.toString(),
                                duration = fmtDuration(cur.durationMs), highlight = true, onClick = onOpenPlayer
                            )
                        } else {
                            Text("Nothing playing", color = lc.textTertiary, fontSize = 12.sp)
                        }
                        Spacer(Modifier.height(18.dp))
                        SectionLabel(LiquidGlyphs.QueueMusic, "Queue (Up Next)", accentLabel = true)
                        Spacer(Modifier.height(8.dp))
                    }
                    if (queue.isEmpty()) {
                        item {
                            Text(
                                "Queue is empty", color = lc.textTertiary, fontSize = 12.sp,
                                modifier = Modifier.padding(top = 10.dp)
                            )
                        }
                    }
                    items(queue, key = { it.id }) { t ->
                        TrackRow(
                            t.title, t.artist, t.coverUrl ?: t.displayArtUri.toString(),
                            duration = fmtDuration(t.durationMs)
                        ) {
                            val idx = queue.indexOfFirst { it.id == t.id }
                            if (idx >= 0) PlayerController.playTrack(context, idx)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(icon: ImageVector, text: String, accentLabel: Boolean = false) {
    val lc = LiquidTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = lc.accent, modifier = Modifier.size(13.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            text,
            color = if (accentLabel) lc.accent else lc.textPrimary,
            fontSize = 13.sp, fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun HeaderIconButton(icon: ImageVector, cd: String, onClick: () -> Unit) {
    val lc = LiquidTheme.colors
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(lc.chipBg)
            .liquidClickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, cd, tint = lc.textPrimary, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun RecentCard(title: String, artist: String, cover: String?, onClick: () -> Unit) {
    val lc = LiquidTheme.colors
    Column(
        modifier = Modifier
            .width(112.dp)
            .clip(RoundedCornerShape(11.dp))
            .liquidClickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .size(112.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(if (lc.isDark) Color(0xFF242424) else Color(0xFFE3E3E8))
        ) {
            if (!cover.isNullOrBlank()) {
                AsyncImage(
                    model = cover, contentDescription = null,
                    contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            title, color = lc.textPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis
        )
        Text(artist, color = lc.textSecondary, fontSize = 10.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun TrackRow(
    title: String,
    artist: String,
    cover: String?,
    duration: String = "",
    highlight: Boolean = false,
    onClick: () -> Unit,
) {
    val lc = LiquidTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (highlight) lc.accent.copy(alpha = 0.12f) else Color.Transparent)
            .liquidClickable(onClick = onClick)
            .padding(vertical = 5.dp, horizontal = 6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(if (lc.isDark) Color(0xFF242424) else Color(0xFFE3E3E8))
        ) {
            if (!cover.isNullOrBlank()) {
                AsyncImage(
                    model = cover, contentDescription = null,
                    contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title, color = lc.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Text(artist, color = lc.textSecondary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (duration.isNotEmpty()) {
            Spacer(Modifier.width(6.dp))
            Text(duration, color = lc.textTertiary, fontSize = 10.5.sp)
        }
        if (highlight) {
            Spacer(Modifier.width(6.dp))
            Icon(LiquidGlyphs.Play, null, tint = lc.accent, modifier = Modifier.size(17.dp))
        }
    }
}
