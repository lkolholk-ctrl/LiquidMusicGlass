package com.liquidmusicglass.ui.screens

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.liquidmusicglass.data.local.LocalLibraryIndexer
import com.liquidmusicglass.data.local.LocalLibraryStore
import com.liquidmusicglass.data.local.db.AlbumAgg
import com.liquidmusicglass.data.local.db.AppDatabase
import com.liquidmusicglass.data.local.db.ArtistAgg
import com.liquidmusicglass.data.local.db.LocalTrackEntity
import com.liquidmusicglass.ui.theme.LiquidColors
import com.liquidmusicglass.ui.theme.LiquidTheme
import kotlinx.coroutines.delay

private fun albumArt(albumId: Long): Uri = Uri.parse("content://media/external/audio/albumart/$albumId")

private fun fmtDuration(ms: Long): String {
    val total = (ms / 1000).toInt()
    val m = total / 60
    val s = total % 60
    return "%d:%02d".format(m, s)
}

// ══════════════════════════════════════════════════════════════════════════════
//  Главный экран: поиск + Артисты / Альбомы / Треки
// ══════════════════════════════════════════════════════════════════════════════
@Composable
fun LocalLibraryScreen(
    onBack: () -> Unit,
    onOpenArtist: (String) -> Unit,
    onOpenAlbum: (Long, String) -> Unit
) {
    val lc = LiquidTheme.colors
    val context = LocalContext.current
    val dao = remember { AppDatabase.getInstance(context).localTracksDao() }

    LaunchedEffectIndex(context)
    val status by LocalLibraryIndexer.status.collectAsState()
    val progress by LocalLibraryIndexer.progress.collectAsState()

    var tab by remember { mutableStateOf(0) }              // 0=артисты 1=альбомы 2=треки
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<LocalLibraryStore.SearchResults?>(null) }

    // ── множественный выбор (вкладка «Треки») для массового редактирования тегов ──
    val selected = remember { mutableStateMapOf<String, LocalTrackEntity>() }
    var selectionMode by remember { mutableStateOf(false) }
    var bulkOpen by remember { mutableStateOf(false) }
    fun exitSelection() { selectionMode = false; selected.clear() }

    androidx.compose.runtime.LaunchedEffect(query) {
        if (query.isBlank()) { results = null; return@LaunchedEffect }
        delay(250)                                          // debounce
        results = LocalLibraryStore.search(context, query)
    }
    // выходим из режима выбора при смене вкладки или входе в поиск
    androidx.compose.runtime.LaunchedEffect(tab, query) {
        if (tab != 2 || query.isNotBlank()) exitSelection()
    }

    BackHandler(enabled = bulkOpen) { bulkOpen = false }
    BackHandler(enabled = selectionMode && !bulkOpen) { exitSelection() }

    Box(Modifier.fillMaxSize().background(lc.settingsBackground)) {
        Column(Modifier.fillMaxSize()) {
            Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
            Spacer(Modifier.height(12.dp))

            if (selectionMode) {
                SelectionHeader(selected.size, lc, onClose = { exitSelection() })
            } else {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircleBack(lc, onBack)
                    Spacer(Modifier.width(14.dp))
                    Text("Медиатека", color = lc.textPrimary, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(12.dp))

            if (!selectionMode) {
                SearchField(query, lc, onChange = { query = it }, onClear = { query = "" })
                Spacer(Modifier.height(12.dp))
            }

            if (query.isBlank()) {
                if (!selectionMode) {
                    Segments(tab, lc) { tab = it }
                    Spacer(Modifier.height(8.dp))
                }
                when (tab) {
                    0 -> ArtistsList(dao, lc, onOpenArtist)
                    1 -> AlbumsGrid(dao, lc, onOpenAlbum)
                    else -> TracksTab(
                        dao, lc, context, selectionMode, selected,
                        onLongPress = { e -> selectionMode = true; selected[e.id] = e },
                        onToggle = { e -> if (selected.containsKey(e.id)) selected.remove(e.id) else selected[e.id] = e },
                        onSelectAllVisible = { list -> list.forEach { selected[it.id] = it } },
                        onClearVisible = { exitSelection() }
                    )
                }
            } else {
                SearchResultsView(results, lc, context, onOpenArtist, onOpenAlbum)
            }
        }

        // нижняя панель действий в режиме выбора
        if (selectionMode && selected.isNotEmpty()) {
            Row(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .background(lc.cardSurface)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Выбрано: ${selected.size}", color = lc.textSecondary, fontSize = 14.sp, modifier = Modifier.weight(1f))
                Row(
                    Modifier.clip(CircleShape).background(lc.accent)
                        .clickable(remember { MutableInteractionSource() }, null) { bulkOpen = true }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.Edit, null, tint = Color.White, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Редактировать теги", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        if (status == LocalLibraryIndexer.Status.SCANNING && !selectionMode) {
            Box(
                Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp)
                    .clip(CircleShape).background(lc.cardSurface).padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                Text("Индексация медиатеки… $progress", color = lc.textSecondary, fontSize = 13.sp)
            }
        }

        // оверлей массового редактирования
        if (bulkOpen) {
            Box(Modifier.fillMaxSize()) {
                BulkTagEditScreen(
                    tracks = selected.values.map { LocalLibraryStore.toTrack(it) },
                    onBack = { bulkOpen = false; exitSelection() }
                )
            }
        }
    }
}

@Composable
private fun SelectionHeader(count: Int, lc: LiquidColors, onClose: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(40.dp).background(if (lc.isDark) Color(0xFF1C1C1E) else Color(0xFFF2F2F7), CircleShape)
                .clip(CircleShape).clickable(remember { MutableInteractionSource() }, null, onClick = onClose),
            contentAlignment = Alignment.Center
        ) { Icon(Icons.Rounded.Close, null, tint = lc.iconDefault, modifier = Modifier.size(22.dp)) }
        Spacer(Modifier.width(14.dp))
        Text(if (count == 0) "Выбор треков" else "Выбрано: $count",
            color = lc.textPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun LaunchedEffectIndex(context: android.content.Context) {
    androidx.compose.runtime.LaunchedEffect(Unit) { LocalLibraryIndexer.ensureIndexed(context) }
}

// ── Списки (главный экран) ───────────────────────────────────────────────────
@Composable
private fun ArtistsList(
    dao: com.liquidmusicglass.data.local.db.LocalTracksDao,
    lc: LiquidColors,
    onOpenArtist: (String) -> Unit
) {
    val flow = remember { dao.artists() }
    val artists by flow.collectAsState(initial = emptyList())
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 120.dp)) {
        items(artists, key = { it.name }) { a -> ArtistRow(a, lc) { onOpenArtist(a.name) } }
    }
}

@Composable
private fun AlbumsGrid(
    dao: com.liquidmusicglass.data.local.db.LocalTracksDao,
    lc: LiquidColors,
    onOpenAlbum: (Long, String) -> Unit
) {
    val flow = remember { dao.albums() }
    val albums by flow.collectAsState(initial = emptyList())
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(albums, key = { it.albumId }) { al -> AlbumCard(al, lc) { onOpenAlbum(al.albumId, al.name) } }
    }
}

@Composable
private fun TracksTab(
    dao: com.liquidmusicglass.data.local.db.LocalTracksDao,
    lc: LiquidColors,
    context: android.content.Context,
    selectionMode: Boolean,
    selected: Map<String, LocalTrackEntity>,
    onLongPress: (LocalTrackEntity) -> Unit,
    onToggle: (LocalTrackEntity) -> Unit,
    onSelectAllVisible: (List<LocalTrackEntity>) -> Unit,
    onClearVisible: () -> Unit
) {
    var sort by remember { mutableStateOf(0) } // 0 title,1 artist,2 album,3 added,4 duration
    val flow = remember(sort) {
        when (sort) {
            1 -> dao.tracksByArtist(); 2 -> dao.tracksByAlbum()
            3 -> dao.tracksByDateAdded(); 4 -> dao.tracksByDuration(); else -> dao.tracksByTitle()
        }
    }
    val tracks by flow.collectAsState(initial = emptyList())
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (selectionMode) {
                SortChip("Выбрать все (${tracks.size})", false, lc) { onSelectAllVisible(tracks) }
                SortChip("Снять", false, lc) { onClearVisible() }
            }
            val labels = listOf("Название", "Артист", "Альбом", "Добавлены", "Длительность")
            labels.forEachIndexed { i, t -> SortChip(t, sort == i, lc) { sort = i } }
        }
        LazyColumn(Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = if (selectionMode) 96.dp else 120.dp)) {
            itemsIndexed(tracks) { index, e ->
                SelectableTrackRow(
                    e, lc,
                    selectionMode = selectionMode,
                    selectedNow = selected.containsKey(e.id),
                    onClick = { if (selectionMode) onToggle(e) else LocalLibraryStore.play(context, tracks, index) },
                    onLongPress = { onLongPress(e) }
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SelectableTrackRow(
    e: LocalTrackEntity, lc: LiquidColors,
    selectionMode: Boolean, selectedNow: Boolean,
    onClick: () -> Unit, onLongPress: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(if (selectedNow) lc.glassTint else Color.Transparent)
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() }, indication = null,
                onClick = onClick, onLongClick = onLongPress
            )
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (selectionMode) {
            if (selectedNow) {
                Icon(Icons.Rounded.CheckCircle, null, tint = lc.accent, modifier = Modifier.size(24.dp))
            } else {
                Box(Modifier.size(24.dp).clip(CircleShape).border(2.dp, lc.iconMuted, CircleShape))
            }
            Spacer(Modifier.width(10.dp))
        }
        ArtBox(albumArt(e.albumId), 48.dp, 8.dp, lc, Icons.Rounded.MusicNote)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(e.title, color = lc.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${e.artist} · ${fmtDuration(e.durationMs)}", color = lc.textSecondary, fontSize = 12.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

// ── Поиск ────────────────────────────────────────────────────────────────────
@Composable
private fun SearchResultsView(
    results: LocalLibraryStore.SearchResults?,
    lc: LiquidColors,
    context: android.content.Context,
    onOpenArtist: (String) -> Unit,
    onOpenAlbum: (Long, String) -> Unit
) {
    val r = results
    if (r == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Поиск…", color = lc.textTertiary, fontSize = 14.sp)
        }
        return
    }
    if (r.artists.isEmpty() && r.albums.isEmpty() && r.tracks.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Ничего не найдено", color = lc.textTertiary, fontSize = 14.sp)
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 120.dp)) {
        if (r.artists.isNotEmpty()) {
            item { SectionLabel("Артисты", lc) }
            items(r.artists, key = { "a_" + it.name }) { a -> ArtistRow(a, lc) { onOpenArtist(a.name) } }
        }
        if (r.albums.isNotEmpty()) {
            item { SectionLabel("Альбомы", lc) }
            items(r.albums, key = { "al_" + it.albumId }) { al -> AlbumRow(al, lc) { onOpenAlbum(al.albumId, al.name) } }
        }
        if (r.tracks.isNotEmpty()) {
            item { SectionLabel("Треки", lc) }
            itemsIndexed(r.tracks) { index, e -> TrackRow(e, lc) { LocalLibraryStore.play(context, r.tracks, index) } }
        }
    }
}

// ══════════════════════════════════════════════════════════════════════════════
//  Экран артиста: его альбомы + все треки
// ══════════════════════════════════════════════════════════════════════════════
@Composable
fun LocalArtistDetailScreen(artistName: String, onBack: () -> Unit, onOpenAlbum: (Long, String) -> Unit) {
    val lc = LiquidTheme.colors
    val context = LocalContext.current
    val dao = remember { AppDatabase.getInstance(context).localTracksDao() }
    val albumsFlow = remember(artistName) { dao.albumsOfArtist(artistName) }
    val tracksFlow = remember(artistName) { dao.tracksOfArtist(artistName) }
    val albums by albumsFlow.collectAsState(initial = emptyList())
    val tracks by tracksFlow.collectAsState(initial = emptyList())

    Box(Modifier.fillMaxSize().background(lc.settingsBackground)) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 120.dp)) {
            item {
                Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircleBack(lc, onBack)
                    Spacer(Modifier.width(14.dp))
                    Text(artistName, color = lc.textPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    PlayAllButton(lc) { LocalLibraryStore.play(context, tracks, 0) }
                }
                Spacer(Modifier.height(12.dp))
                if (albums.isNotEmpty()) {
                    SectionLabel("Альбомы", lc)
                    LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(albums, key = { it.albumId }) { al ->
                            Box(Modifier.width(140.dp)) { AlbumCard(al, lc) { onOpenAlbum(al.albumId, al.name) } }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    SectionLabel("Все треки", lc)
                }
            }
            itemsIndexed(tracks) { index, e -> TrackRow(e, lc, paddingH = 12.dp) { LocalLibraryStore.play(context, tracks, index) } }
        }
    }
}

// ══════════════════════════════════════════════════════════════════════════════
//  Экран альбома: треки по порядку
// ══════════════════════════════════════════════════════════════════════════════
@Composable
fun LocalAlbumDetailScreen(albumId: Long, albumName: String, onBack: () -> Unit) {
    val lc = LiquidTheme.colors
    val context = LocalContext.current
    val dao = remember { AppDatabase.getInstance(context).localTracksDao() }
    val tracksFlow = remember(albumId) { dao.tracksOfAlbum(albumId) }
    val tracks by tracksFlow.collectAsState(initial = emptyList())
    val artist = tracks.firstOrNull()?.artist ?: ""
    val year = tracks.firstOrNull()?.year ?: 0

    Box(Modifier.fillMaxSize().background(lc.settingsBackground)) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 120.dp)) {
            item {
                Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircleBack(lc, onBack)
                    Spacer(Modifier.weight(1f))
                    PlayAllButton(lc) { LocalLibraryStore.play(context, tracks, 0) }
                }
                Spacer(Modifier.height(12.dp))
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    ArtBox(albumArt(albumId), 180.dp, 16.dp, lc, Icons.Rounded.Album)
                }
                Spacer(Modifier.height(14.dp))
                Text(albumName, color = lc.textPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp))
                val sub = buildString {
                    append(artist)
                    if (year > 0) append(" · $year")
                    append(" · ${tracks.size} треков")
                }
                Text(sub, color = lc.textSecondary, fontSize = 13.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp))
            }
            itemsIndexed(tracks) { index, e ->
                AlbumTrackRow(e, index + 1, lc) { LocalLibraryStore.play(context, tracks, index) }
            }
        }
    }
}

// ══════════════════════════════════════════════════════════════════════════════
//  Переиспользуемые элементы
// ══════════════════════════════════════════════════════════════════════════════
@Composable
private fun ArtistRow(a: ArtistAgg, lc: LiquidColors, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .clickable(remember { MutableInteractionSource() }, null, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ArtBox(albumArt(a.anyAlbumId), 48.dp, 24.dp, lc, Icons.Rounded.Person)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(a.name, color = lc.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${a.albumCount} альб. · ${a.trackCount} трек.", color = lc.textSecondary, fontSize = 12.sp)
        }
    }
}

@Composable
private fun AlbumRow(al: AlbumAgg, lc: LiquidColors, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .clickable(remember { MutableInteractionSource() }, null, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ArtBox(albumArt(al.albumId), 48.dp, 8.dp, lc, Icons.Rounded.Album)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(al.name, color = lc.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(al.artist, color = lc.textSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun AlbumCard(al: AlbumAgg, lc: LiquidColors, onClick: () -> Unit) {
    Column(
        Modifier.clip(RoundedCornerShape(12.dp))
            .clickable(remember { MutableInteractionSource() }, null, onClick = onClick).padding(4.dp)
    ) {
        ArtBox(albumArt(al.albumId), null, 12.dp, lc, Icons.Rounded.Album, Modifier.fillMaxWidth().aspectRatio(1f))
        Spacer(Modifier.height(6.dp))
        Text(al.name, color = lc.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(al.artist, color = lc.textSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun TrackRow(e: LocalTrackEntity, lc: LiquidColors, paddingH: Dp = 8.dp, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .clickable(remember { MutableInteractionSource() }, null, onClick = onClick)
            .padding(horizontal = paddingH, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ArtBox(albumArt(e.albumId), 48.dp, 8.dp, lc, Icons.Rounded.MusicNote)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(e.title, color = lc.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${e.artist} · ${fmtDuration(e.durationMs)}", color = lc.textSecondary, fontSize = 12.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun AlbumTrackRow(e: LocalTrackEntity, num: Int, lc: LiquidColors, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .clickable(remember { MutableInteractionSource() }, null, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("$num", color = lc.textTertiary, fontSize = 13.sp, modifier = Modifier.width(28.dp))
        Column(Modifier.weight(1f)) {
            Text(e.title, color = lc.textPrimary, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(fmtDuration(e.durationMs), color = lc.textSecondary, fontSize = 12.sp)
    }
}

@Composable
private fun ArtBox(
    uri: Uri, size: Dp?, corner: Dp, lc: LiquidColors,
    fallback: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier
) {
    val base = if (size != null) modifier.size(size) else modifier
    Box(base.clip(RoundedCornerShape(corner)).background(lc.glassTint), contentAlignment = Alignment.Center) {
        Icon(fallback, null, tint = lc.iconMuted, modifier = Modifier.size(22.dp))
        AsyncImage(model = uri, contentDescription = null, modifier = Modifier.fillMaxSize(),
            contentScale = androidx.compose.ui.layout.ContentScale.Crop)
    }
}

@Composable
private fun SectionLabel(text: String, lc: LiquidColors) {
    Text(text, color = lc.textSecondary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 8.dp, top = 12.dp, bottom = 6.dp))
}

@Composable
private fun Segments(selected: Int, lc: LiquidColors, onSelect: (Int) -> Unit) {
    val items = listOf("Артисты", "Альбомы", "Треки")
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEachIndexed { i, t ->
            Box(
                Modifier.weight(1f).clip(CircleShape)
                    .background(if (selected == i) lc.accent else lc.cardSurface)
                    .clickable(remember { MutableInteractionSource() }, null) { onSelect(i) }
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(t, color = if (selected == i) Color.White else lc.textSecondary,
                    fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun SortChip(text: String, selected: Boolean, lc: LiquidColors, onClick: () -> Unit) {
    Box(
        Modifier.clip(CircleShape).background(if (selected) lc.accent else lc.cardSurface)
            .clickable(remember { MutableInteractionSource() }, null, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = if (selected) Color.White else lc.textSecondary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun SearchField(query: String, lc: LiquidColors, onChange: (String) -> Unit, onClear: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(44.dp).clip(CircleShape)
            .background(lc.searchFieldBg).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Rounded.Search, null, tint = lc.iconMuted, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        BasicTextField(
            value = query, onValueChange = onChange, singleLine = true,
            textStyle = TextStyle(color = lc.textPrimary, fontSize = 16.sp),
            cursorBrush = SolidColor(lc.accent), modifier = Modifier.weight(1f),
            decorationBox = { inner ->
                Box {
                    if (query.isEmpty()) Text("Поиск по трекам, артистам, альбомам", color = lc.textTertiary, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    inner()
                }
            }
        )
        if (query.isNotEmpty()) {
            Icon(Icons.Rounded.Close, null, tint = lc.iconMuted,
                modifier = Modifier.size(20.dp).clickable(remember { MutableInteractionSource() }, null, onClick = onClear))
        }
    }
}

@Composable
private fun PlayAllButton(lc: LiquidColors, onClick: () -> Unit) {
    Box(
        Modifier.size(40.dp).clip(CircleShape).background(lc.accent)
            .clickable(remember { MutableInteractionSource() }, null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(Icons.Rounded.PlayArrow, null, tint = Color.White, modifier = Modifier.size(24.dp))
    }
}

@Composable
private fun CircleBack(lc: LiquidColors, onClick: () -> Unit) {
    Box(
        Modifier.size(40.dp).background(if (lc.isDark) Color(0xFF1C1C1E) else Color(0xFFF2F2F7), CircleShape)
            .clip(CircleShape).clickable(remember { MutableInteractionSource() }, null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(Icons.AutoMirrored.Rounded.ArrowBack, null, tint = lc.iconDefault, modifier = Modifier.size(22.dp))
    }
}
