package com.liquidmusicglass.ui.screens

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.liquidmusicglass.api.icm.IcmRepository
import com.liquidmusicglass.engine.PlayerController
import com.liquidmusicglass.engine.Track
import com.liquidmusicglass.ui.components.DetailHeader
import com.liquidmusicglass.ui.components.DetailTopBar
import com.liquidmusicglass.ui.components.DetailTrackRow
import com.liquidmusicglass.ui.components.formatTotalDuration
import com.liquidmusicglass.ui.components.toDetailThumb
import com.liquidmusicglass.ui.theme.LiquidSurfaces
import com.liquidmusicglass.ui.theme.LiquidTheme
import kotlinx.coroutines.launch

/**
 * Экран плейлиста — на тех же общих частях, что альбом.
 *
 * Отличие от альбома: в строках показывается обложка трека, а не номер. В
 * плейлисте песни разные, и обложка узнаётся быстрее порядкового номера, тогда
 * как у альбома обложка одна на всех и номер полезнее.
 */
@Composable
fun PlaylistDetailScreen(
    playlistId: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val colors = LiquidTheme.colors
    val isDark = colors.isDark
    val scope = rememberCoroutineScope()

    var playlistInfo by remember {
        mutableStateOf<com.liquidmusicglass.api.icm.IcmUserPlaylistInfo?>(null)
    }
    var tracks by remember { mutableStateOf<List<Track>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    // Локальные плейлисты живут в приложении, облачные — на сервере. Отличаются
    // по префиксу идентификатора.
    val isLocalPlaylist = playlistId.startsWith("pl_")

    LaunchedEffect(playlistId) {
        if (playlistId.isBlank()) {
            errorMsg = "Invalid playlist"
            return@LaunchedEffect
        }

        isLoading = true
        errorMsg = null

        if (isLocalPlaylist) {
            val localPlaylist = com.liquidmusicglass.engine.PlaylistManager.getById(playlistId)
            if (localPlaylist == null) {
                errorMsg = "Playlist not found"
                isLoading = false
                return@LaunchedEffect
            }
            tracks = localPlaylist.tracks.map { pt ->
                Track(
                    id = pt.id,
                    title = pt.title,
                    artist = pt.artist,
                    albumName = "",
                    uri = Uri.parse("https://byicloud.online/track/${pt.id}"),
                    durationMs = pt.durationMs,
                    albumId = pt.id.hashCode().toLong(),
                    coverUrl = pt.coverUrl
                )
            }
            isLoading = false
        } else {
            scope.launch {
                val allTracks = mutableListOf<Track>()
                var offset = 0
                val limit = 200
                var totalExpected: Int? = null
                var page = 0

                while (true) {
                    page++
                    val response =
                        IcmRepository.getUserPlaylistTracks(playlistId, limit = limit, offset = offset)
                    if (response == null) {
                        if (page == 1) errorMsg = "Failed to load playlist"
                        break
                    }

                    if (page == 1) {
                        playlistInfo = response.playlist
                        totalExpected = response.playlist?.trackCount
                    }

                    val pageTracks = response.tracks.mapNotNull { tr ->
                        val trackIdStr = tr.trackId?.takeIf { it.isNotBlank() }
                            ?: return@mapNotNull null
                        val durationSec = tr.duration ?: 0L
                        // Часть источников отдаёт секунды, часть миллисекунды —
                        // различаем по величине.
                        val durationMs = if (durationSec < 10_000L) durationSec * 1000L else durationSec
                        Track(
                            id = trackIdStr,
                            title = tr.title.orEmpty(),
                            artist = tr.artist.orEmpty(),
                            albumName = "",
                            uri = Uri.parse("https://byicloud.online/track/$trackIdStr"),
                            durationMs = durationMs,
                            albumId = tr.collectionId?.hashCode()?.toLong()
                                ?: trackIdStr.hashCode().toLong(),
                            coverUrl = tr.cover.toDetailThumb()
                        )
                    }

                    allTracks.addAll(pageTracks)

                    if (response.tracks.size < limit) break
                    if (totalExpected != null && allTracks.size >= totalExpected) break
                    offset += limit
                }

                tracks = allTracks
                isLoading = false
            }
        }
    }

    val name = remember(playlistId, playlistInfo) {
        if (isLocalPlaylist) {
            com.liquidmusicglass.engine.PlaylistManager.getById(playlistId)?.name ?: "Playlist"
        } else {
            playlistInfo?.name ?: "Playlist"
        }
    }

    val listState = rememberLazyListState()
    val showTopBarTitle by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 320
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(LiquidSurfaces.sheet(isDark))) {
        when {
            isLoading && tracks.isEmpty() ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = colors.accent, modifier = Modifier.size(32.dp))
                }

            errorMsg != null && tracks.isEmpty() ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = errorMsg.orEmpty(),
                        color = LiquidSurfaces.textSecondary(isDark),
                        fontSize = 14.sp
                    )
                }

            else -> {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .widthIn(max = 640.dp)
                        .align(Alignment.TopCenter),
                    contentPadding = PaddingValues(bottom = 140.dp)
                ) {
                    item {
                        DetailHeader(
                            title = name,
                            // Обложки у плейлиста нет — берём обложку первого трека:
                            // пустой квадрат смотрелся бы как ошибка загрузки.
                            subtitle = if (isLocalPlaylist) "Your playlist" else "Playlist",
                            facts = buildList {
                                if (tracks.isNotEmpty()) add("${tracks.size} songs")
                                val total = tracks.sumOf { it.durationMs }
                                if (total > 0) add(formatTotalDuration(total))
                            },
                            coverUrl = tracks.firstOrNull()?.coverUrl,
                            isDark = isDark,
                            onPlay = {
                                if (tracks.isNotEmpty()) PlayerController.play(context, tracks, 0)
                            },
                            onShuffle = {
                                if (tracks.isNotEmpty()) {
                                    PlayerController.play(context, tracks.shuffled(), 0)
                                }
                            }
                        )
                    }

                    itemsIndexed(tracks, key = { index, track -> "${track.id}-$index" }) { index, track ->
                        DetailTrackRow(
                            position = index + 1,
                            title = track.title,
                            subtitle = track.artist,
                            durationMs = track.durationMs,
                            // В плейлисте песни разные — обложка узнаётся быстрее номера.
                            coverUrl = track.coverUrl,
                            isDark = isDark,
                            showDivider = index < tracks.lastIndex,
                            onClick = { PlayerController.play(context, tracks, index) }
                        )
                    }
                }
            }
        }

        DetailTopBar(
            title = name,
            showTitle = showTopBarTitle,
            isDark = isDark,
            onBack = onBack
        )
    }
}
