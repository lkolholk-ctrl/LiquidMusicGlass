package com.liquidmusicglass.ui.screens

import android.net.Uri
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.liquidmusicglass.api.icm.IcmRepository
import com.liquidmusicglass.api.icm.toTrack
import com.liquidmusicglass.data.local.db.AppDatabase
import com.liquidmusicglass.engine.PlayerController
import com.liquidmusicglass.engine.Track
import com.liquidmusicglass.ui.glass.AlbumArtImage
import com.liquidmusicglass.ui.theme.AppFontFamily
import com.liquidmusicglass.ui.theme.LiquidTheme
import com.liquidmusicglass.ui.viewmodel.HomeViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Таб «New» — все предложки (recently played / home-блоки / charts), перенесённые
 * с экрана Wave, плюс реальная история прослушивания (Room). Контентный экран:
 * следует теме приложения (Светлая/Тёмная), карточки themed (не белым по тёмному).
 */
@Composable
fun NewScreen(
    viewModel: HomeViewModel,
    onNavigateToAlbum: (String) -> Unit = {},
    onNavigateToArtist: (String) -> Unit = {},
) {
    val context = LocalContext.current
    DisposableEffect(viewModel) {
        onDispose {
            viewModel.cancelHomeLoad()
            viewModel.cancelChartsLoad()
        }
    }
    LaunchedEffect(viewModel) { viewModel.loadHomeContent() }

    val recentlyPlayed by PlayerController.recentlyPlayed.collectAsState()
    val isPlaying by PlayerController.isPlaying.collectAsState()
    val homeContent by viewModel.homeContent.collectAsState()
    val charts by viewModel.charts.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val isLoadingCharts by viewModel.isLoadingCharts.collectAsState()
    val homeBlocks = remember(homeContent) {
        homeContent?.blocks?.filter { it.type != "charts" && it.items.isNotEmpty() } ?: emptyList()
    }

    val dao = remember { AppDatabase.getInstance(context).listenHistoryDao() }
    val history by remember { dao.observe() }.collectAsState(initial = emptyList())

    // Long-press по мудкарточке → предпросмотр станции (сеть только на IO).
    // Переехали сюда с Wave вместе с карточками.
    var previewMood by remember { mutableStateOf<WaveMood?>(null) }
    var previewTracks by remember { mutableStateOf<List<Track>?>(null) }
    LaunchedEffect(previewMood) {
        val mood = previewMood ?: return@LaunchedEffect
        previewTracks = null
        previewTracks = withContext(Dispatchers.IO) {
            runCatching { IcmRepository.searchTracks(mood.query, limit = 4) }.getOrDefault(emptyList())
        }
    }

    val lc = LiquidTheme.colors
    // Широкое окно (телефон-альбом ИЛИ планшет): ограничиваем ширину списка
    // и центрируем — плоские строки/карусели не растягиваются на весь экран.
    val win = com.liquidmusicglass.ui.rememberWindowInfo()
    // Альбом/планшет: делаем всё компактнее (шрифты/карточки/отступы ~20-30%),
    // как в LandscapeHome. В портрете compact=false → всё как было.
    val compact = win.useSideBySide
    val sectionGap = if (compact) 18.dp else 28.dp
    val rowGap = if (compact) 10.dp else 14.dp

    Box(modifier = Modifier.fillMaxSize().background(lc.settingsBackground)) {
        LazyColumn(
            modifier = if (win.useSideBySide)
                Modifier.fillMaxHeight().widthIn(max = 900.dp).fillMaxWidth().align(Alignment.TopCenter)
            else Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 178.dp)
        ) {
            item { Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars)) }
            item {
                Text(
                    text = "New",
                    color = lc.textPrimary,
                    fontSize = if (compact) 20.sp else 28.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = AppFontFamily,
                    modifier = Modifier.padding(
                        start = 20.dp,
                        top = if (compact) 8.dp else 12.dp,
                        bottom = if (compact) 10.dp else 16.dp
                    )
                )
            }

            // ── Волны по настроению (мудкарточки, переехали с экрана Wave) ──
            item {
                NewSectionHeader("Waves by mood", compact)
                WaveMoodTiles(
                    onSelect = { mood -> viewModel.buildMoodWave(context, mood.query, mood.label) },
                    playing = isPlaying,
                    onPreview = { mood -> previewMood = mood }
                )
                Spacer(Modifier.height(sectionGap))
            }

            // ── Recently played ──
            if (recentlyPlayed.isNotEmpty()) {
                item {
                    NewSectionHeader("Recently played", compact)
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(rowGap)
                    ) {
                        items(recentlyPlayed.take(15).distinctBy { it.id }, key = { "recent_${it.id}" }) { recent ->
                            NewTrackCard(
                                title = recent.title,
                                subtitle = recent.artist,
                                uri = recent.displayArtUri,
                                coverUrl = recent.coverUrl,
                                compact = compact,
                                onClick = { PlayerController.playFromList(context, listOf(recent)) }
                            )
                        }
                    }
                    Spacer(Modifier.height(sectionGap))
                }
            }

            // ── Скелетоны на время первой загрузки: пока нет ни блоков, ни чартов
            // (кэш пуст), вместо пустоты — пульсирующие плейсхолдеры секций,
            // в том же стиле, что шиммер в поиске. ──
            if (homeBlocks.isEmpty() && charts.isEmpty() && (isLoading || isLoadingCharts)) {
                items(count = 2, key = { "skeleton_$it" }) {
                    NewSectionSkeleton()
                    Spacer(Modifier.height(sectionGap))
                }
            }

            // ── Home-блоки (popular / new_releases / recommendations …) ──
            homeBlocks.forEach { block ->
                item(key = "block_${block.id}") {
                    NewSectionHeader(block.title, compact)
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(rowGap)
                    ) {
                        items(block.items, key = { "${block.id}_${it.id}" }) { homeItem ->
                            NewTrackCard(
                                title = homeItem.title,
                                subtitle = homeItem.displayArtist,
                                coverUrl = homeItem.cover,
                                compact = compact,
                                onClick = {
                                    when {
                                        homeItem.isArtist -> onNavigateToArtist(homeItem.artistId ?: homeItem.id)
                                        homeItem.isAlbum -> onNavigateToAlbum(homeItem.collectionId ?: homeItem.id)
                                        else -> PlayerController.playFromList(context, listOf(homeItem.toWaveTrack()))
                                    }
                                }
                            )
                        }
                    }
                    Spacer(Modifier.height(sectionGap))
                }
            }

            // ── Charts ──
            if (charts.isNotEmpty()) {
                item {
                    NewSectionHeader("Charts", compact)
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(rowGap)
                    ) {
                        items(charts, key = { "chart_${it.id}" }) { chart ->
                            NewChartCard(
                                name = chart.name,
                                cover = chart.cover,
                                trackCount = chart.tracks.size,
                                compact = compact,
                                onClick = {
                                    val tracks = chart.tracks.map { it.toTrack() }
                                    if (tracks.isNotEmpty()) {
                                        PlayerController.playFromList(
                                            context = context,
                                            tracks = tracks,
                                            startIndex = 0,
                                            autoRefillType = "chart",
                                            autoRefillId = chart.id,
                                            autoRefillName = chart.name
                                        )
                                    }
                                }
                            )
                        }
                    }
                    Spacer(Modifier.height(sectionGap))
                }
            }

            // ── История прослушивания (Room) ──
            if (history.isNotEmpty()) {
                item { NewSectionHeader("History", compact) }
                items(history, key = { "hist_${it.trackId}" }) { entry ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .animateItem()
                            .clickable {
                                PlayerController.playFromList(
                                    context,
                                    listOf(
                                        Track(
                                            id = entry.trackId,
                                            title = entry.title,
                                            artist = entry.artist,
                                            albumName = "",
                                            uri = Uri.parse("https://byicloud.online/track/${entry.trackId}"),
                                            durationMs = entry.durationMs,
                                            albumId = -1L,
                                            coverUrl = entry.coverUrl
                                        )
                                    )
                                )
                            }
                            .padding(horizontal = 20.dp, vertical = if (compact) 6.dp else 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AlbumArtImage(
                            uri = null,
                            coverUrl = entry.coverUrl,
                            contentDescription = entry.title,
                            modifier = Modifier.size(if (compact) 40.dp else 52.dp).clip(RoundedCornerShape(10.dp))
                        )
                        Spacer(Modifier.width(if (compact) 10.dp else 12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = entry.title,
                                color = lc.textPrimary,
                                fontSize = if (compact) 13.sp else 16.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = entry.artist,
                                color = lc.textSecondary,
                                fontSize = if (compact) 11.5.sp else 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }

        // ── Long-press по мудкарточке: предпросмотр станции перед запуском ──
        previewMood?.let { mood ->
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { previewMood = null },
                title = { Text(mood.label) },
                text = {
                    val tracks = previewTracks
                    when {
                        tracks == null -> Text("Loading…")
                        tracks.isEmpty() -> Text("Nothing found for this mood.")
                        else -> Column {
                            tracks.forEach { t ->
                                Text(
                                    text = "${t.title} — ${t.artist}",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(Modifier.height(6.dp))
                            }
                        }
                    }
                },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = {
                        previewMood = null
                        viewModel.buildMoodWave(context, mood.query, mood.label)
                    }) { Text("Play station") }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(onClick = { previewMood = null }) { Text("Close") }
                }
            )
        }
    }
}

/** Пульсирующий плейсхолдер секции: плашка заголовка + ряд карточек 140dp. */
@Composable
private fun NewSectionSkeleton() {
    val pulse by androidx.compose.animation.core.rememberInfiniteTransition(label = "newSkeleton")
        .animateFloat(
            initialValue = 0.45f,
            targetValue = 1f,
            animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                animation = androidx.compose.animation.core.tween(650),
                repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
            ),
            label = "newSkeletonPulse"
        )
    val base = if (LiquidTheme.colors.isDark) Color(0xFF1A1A1A) else Color(0xFFEAEAEF)
    Column {
        Box(
            modifier = Modifier
                .padding(horizontal = 20.dp)
                .padding(bottom = 12.dp)
                .size(width = 130.dp, height = 20.dp)
                .clip(RoundedCornerShape(50))
                .background(base.copy(alpha = pulse))
        )
        Row(
            modifier = Modifier.padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            repeat(3) {
                Column {
                    Box(
                        modifier = Modifier
                            .size(140.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(base.copy(alpha = pulse))
                    )
                    Spacer(Modifier.height(8.dp))
                    Box(
                        modifier = Modifier
                            .size(width = 96.dp, height = 12.dp)
                            .clip(RoundedCornerShape(50))
                            .background(base.copy(alpha = pulse))
                    )
                    Spacer(Modifier.height(4.dp))
                    Box(
                        modifier = Modifier
                            .size(width = 64.dp, height = 11.dp)
                            .clip(RoundedCornerShape(50))
                            .background(base.copy(alpha = pulse))
                    )
                }
            }
        }
    }
}

@Composable
private fun NewSectionHeader(title: String, compact: Boolean = false) {
    Text(
        text = title,
        color = LiquidTheme.colors.textPrimary,
        fontSize = if (compact) 15.sp else 20.sp,
        fontWeight = FontWeight.Bold,
        fontFamily = AppFontFamily,
        modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = if (compact) 8.dp else 12.dp)
    )
}

@Composable
private fun NewTrackCard(
    title: String,
    subtitle: String,
    coverUrl: String?,
    uri: Uri? = null,
    compact: Boolean = false,
    onClick: () -> Unit
) {
    val lc = LiquidTheme.colors
    val cardSize = if (compact) 110.dp else 140.dp
    Column(modifier = Modifier.width(cardSize).clickable(onClick = onClick)) {
        if (uri != null) {
            AlbumArtImage(
                uri = uri,
                coverUrl = coverUrl,
                modifier = Modifier.size(cardSize).clip(RoundedCornerShape(12.dp))
            )
        } else {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current).data(coverUrl).crossfade(true).build(),
                contentDescription = title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(cardSize).clip(RoundedCornerShape(12.dp))
            )
        }
        Spacer(Modifier.height(if (compact) 6.dp else 8.dp))
        Text(
            text = title,
            color = lc.textPrimary,
            fontSize = if (compact) 12.5.sp else 13.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = AppFontFamily,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = subtitle,
            color = lc.textSecondary,
            fontSize = if (compact) 11.sp else 12.sp,
            fontFamily = AppFontFamily,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun NewChartCard(
    name: String,
    cover: String?,
    trackCount: Int,
    compact: Boolean = false,
    onClick: () -> Unit
) {
    val lc = LiquidTheme.colors
    val cardSize = if (compact) 128.dp else 160.dp
    Column(modifier = Modifier.width(cardSize).clickable(onClick = onClick)) {
        Box(modifier = Modifier.size(cardSize).clip(RoundedCornerShape(12.dp))) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current).data(cover).crossfade(true).build(),
                contentDescription = name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f)), startY = 80f))
            )
            Text(
                text = name,
                color = Color.White,
                fontSize = if (compact) 13.sp else 14.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = AppFontFamily,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.BottomStart).padding(if (compact) 10.dp else 12.dp)
            )
        }
        Spacer(Modifier.height(if (compact) 6.dp else 8.dp))
        Text(
            text = "$trackCount tracks",
            color = lc.textSecondary,
            fontSize = if (compact) 11.sp else 12.sp,
            fontFamily = AppFontFamily,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
