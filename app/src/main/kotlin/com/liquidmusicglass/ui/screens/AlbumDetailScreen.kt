package com.liquidmusicglass.ui.screens

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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.liquidmusicglass.api.icm.IcmAlbumResponse
import com.liquidmusicglass.api.icm.IcmRepository
import com.liquidmusicglass.api.icm.toTrack
import com.liquidmusicglass.engine.PlayerController
import com.liquidmusicglass.ui.components.DetailHeader
import com.liquidmusicglass.ui.components.DetailTopBar
import com.liquidmusicglass.ui.components.DetailTrackRow
import com.liquidmusicglass.ui.components.formatTotalDuration
import com.liquidmusicglass.ui.components.toDetailThumb
import com.liquidmusicglass.ui.theme.LiquidSurfaces
import com.liquidmusicglass.ui.theme.LiquidTheme

/**
 * Экран альбома.
 *
 * Разметка собрана из общих частей — тех же, что у плейлиста и остальных
 * подборок. Отличие альбома одно: в строках показывается номер, а не обложка,
 * потому что обложка здесь одна на все треки.
 */
@Composable
fun AlbumDetailScreen(
    albumId: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val colors = LiquidTheme.colors
    val isDark = colors.isDark

    var album by remember { mutableStateOf<IcmAlbumResponse?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(albumId) {
        isLoading = true
        error = null
        try {
            album = IcmRepository.getAlbum(albumId)
            if (album == null) error = IcmRepository.lastError.value ?: "Album not found"
        } catch (e: Exception) {
            error = e.message
        } finally {
            isLoading = false
        }
    }

    val albumTracks = remember(album) {
        album?.tracks?.map { it.toTrack() }?.distinctBy { it.id } ?: emptyList()
    }

    val listState = rememberLazyListState()
    val showTopBarTitle by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 320
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(LiquidSurfaces.sheet(isDark))) {
        when {
            isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = colors.accent, modifier = Modifier.size(32.dp))
            }

            error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = error.orEmpty(),
                    color = LiquidSurfaces.textSecondary(isDark),
                    fontSize = 14.sp
                )
            }

            else -> {
                val info = album?.album
                LazyColumn(
                    state = listState,
                    // Ограничение ширины для планшетов и ландшафта: без него строка
                    // растягивается на весь экран, и номер с длительностью
                    // оказываются в разных его концах.
                    modifier = Modifier
                        .fillMaxSize()
                        .widthIn(max = 640.dp)
                        .align(Alignment.TopCenter),
                    contentPadding = PaddingValues(bottom = 140.dp)
                ) {
                    item {
                        DetailHeader(
                            title = info?.title.orEmpty(),
                            subtitle = info?.artist.orEmpty(),
                            facts = buildList {
                                info?.year?.takeIf { it.isNotBlank() }?.let(::add)
                                if (albumTracks.isNotEmpty()) add("${albumTracks.size} songs")
                                // Общее время каталог не отдаёт — считаем по трекам.
                                val total = albumTracks.sumOf { it.durationMs }
                                if (total > 0) add(formatTotalDuration(total))
                            },
                            coverUrl = info?.cover.toDetailThumb(),
                            isDark = isDark,
                            onPlay = {
                                if (albumTracks.isNotEmpty()) {
                                    PlayerController.play(context, albumTracks, 0)
                                }
                            },
                            onShuffle = {
                                if (albumTracks.isNotEmpty()) {
                                    PlayerController.play(context, albumTracks.shuffled(), 0)
                                }
                            }
                        )
                    }

                    itemsIndexed(albumTracks, key = { _, track -> track.id }) { index, track ->
                        DetailTrackRow(
                            position = index + 1,
                            title = track.title,
                            subtitle = null,
                            durationMs = track.durationMs,
                            // Обложка здесь одна на все треки — номер полезнее.
                            coverUrl = null,
                            isDark = isDark,
                            showDivider = index < albumTracks.lastIndex,
                            onClick = { PlayerController.play(context, albumTracks, index) }
                        )
                    }
                }
            }
        }

        DetailTopBar(
            title = album?.album?.title.orEmpty(),
            showTitle = showTopBarTitle,
            isDark = isDark,
            onBack = onBack
        )
    }
}
