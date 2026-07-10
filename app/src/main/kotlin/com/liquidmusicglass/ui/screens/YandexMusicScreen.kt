package com.liquidmusicglass.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.liquidmusicglass.R
import com.liquidmusicglass.data.local.db.FavoriteTrackDatabase
import com.liquidmusicglass.data.yandex.YandexAuthRepository
import com.liquidmusicglass.data.yandex.YandexDownloadManager
import com.liquidmusicglass.data.yandex.YandexMusicClient
import com.liquidmusicglass.data.yandex.YandexMusicException
import com.liquidmusicglass.data.yandex.YandexUnauthorizedException
import com.liquidmusicglass.data.yandex.toEngineTrack
import com.liquidmusicglass.engine.PlayerController
import com.liquidmusicglass.ui.glass.liquidClickable
import com.liquidmusicglass.ui.theme.LiquidMotion
import com.liquidmusicglass.ui.theme.LiquidTheme
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

private val YandexYellow = Color(0xFFFFCC00)

/**
 * Полноэкранный экран Яндекс.Музыки (Библиотека → Yandex Music). Обычный
 * navigation destination — открывается с тем же slide-переходом, что и
 * остальные детали, а не sheet-оверлеем.
 *
 * Не connected → встроенный вход (WebView, автоперехват токена) или ручной
 * ввод OAuth-токена. Connected → вкладки Search (поиск каталога), Liked
 * (лайкнутая фонотека) и Playlists (свои плейлисты по токену); тап по треку —
 * стриминг, стрелка — скачивание в Downloads, «Download all» — фонотека или
 * плейлист целиком.
 */
@Composable
fun YandexMusicScreen(onBack: () -> Unit) {
    val lc = LiquidTheme.colors
    val focusManager = LocalFocusManager.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val connected by YandexAuthRepository.isConnected.collectAsState()
    val label by YandexAuthRepository.displayLabel.collectAsState()
    val hasPlus by YandexAuthRepository.hasPlus.collectAsState()
    val quality by YandexAuthRepository.quality.collectAsState()
    val dlProgress by YandexDownloadManager.progress.collectAsState()
    // Активная секция задаётся нижним баром ЯМ (его рисует AppRoot).
    val section by YandexSection.current.collectAsState()

    val inputBg = if (lc.isDark) Color.White.copy(alpha = 0.06f) else Color.Black.copy(alpha = 0.04f)

    // search state
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf<String?>(null) }
    var results by remember { mutableStateOf<List<YandexMusicClient.Track>>(emptyList()) }
    var searchArtists by remember { mutableStateOf<List<YandexMusicClient.ArtistBrief>>(emptyList()) }
    var searchAlbums by remember { mutableStateOf<List<YandexMusicClient.AlbumBrief>>(emptyList()) }
    var searchPlaylists by remember { mutableStateOf<List<YandexMusicClient.PlaylistBrief>>(emptyList()) }
    // Discovery (чарт + новинки) в пустом состоянии поиска.
    var discovery by remember { mutableStateOf<YandexMusicClient.Discovery?>(null) }
    // trackIds already in offline DB (ym_…)
    var offlineIds by remember { mutableStateOf<Set<String>>(emptySet()) }

    fun refreshOffline() {
        scope.launch(Dispatchers.IO) {
            val all = FavoriteTrackDatabase.getInstance(context).getDownloadedTracks()
            offlineIds = all.map { it.trackId }.filter { it.startsWith(YandexDownloadManager.ID_PREFIX) }.toSet()
        }
    }

    LaunchedEffect(connected) {
        if (connected) {
            refreshOffline()
            // Плюс может истечь/появиться после подключения — освежаем статус
            YandexAuthRepository.refreshAccountInfo()
        }
    }

    fun runSearch() {
        val q = query.trim()
        if (q.isEmpty() || searching) return
        focusManager.clearFocus()
        searching = true
        searchError = null
        scope.launch {
            try {
                val res = withContext(Dispatchers.IO) {
                    val client = YandexAuthRepository.clientOrNull()
                        ?: throw YandexMusicException("Not connected")
                    client.searchAll(q)
                }
                results = res.tracks
                searchArtists = res.artists
                searchAlbums = res.albums
                searchPlaylists = res.playlists
                val empty = res.tracks.isEmpty() && res.artists.isEmpty() &&
                    res.albums.isEmpty() && res.playlists.isEmpty()
                if (empty) searchError = "Nothing found"
            } catch (_: YandexUnauthorizedException) {
                searchError = "Token expired — reconnect"
                YandexAuthRepository.disconnect()
                results = emptyList(); searchArtists = emptyList()
                searchAlbums = emptyList(); searchPlaylists = emptyList()
            } catch (_: Exception) {
                // Без e.message: сеть/API могут отдавать шум; токен туда не должен попадать
                searchError = "Search failed"
                results = emptyList(); searchArtists = emptyList()
                searchAlbums = emptyList(); searchPlaylists = emptyList()
            } finally {
                searching = false
            }
        }
    }

    // ── Liked (фонотека ЯМ) ──
    var liked by remember { mutableStateOf<List<YandexMusicClient.Track>>(emptyList()) }
    var likedLoading by remember { mutableStateOf(false) }
    var likedError by remember { mutableStateOf<String?>(null) }
    var bulkActive by remember { mutableStateOf(false) }
    var bulkDone by remember { mutableStateOf(0) }
    var bulkTotal by remember { mutableStateOf(0) }
    var bulkStop by remember { mutableStateOf(false) }

    fun loadLiked(force: Boolean = false) {
        if (likedLoading || (!force && liked.isNotEmpty())) return
        likedLoading = true
        likedError = null
        scope.launch {
            try {
                val list = withContext(Dispatchers.IO) {
                    val client = YandexAuthRepository.clientOrNull()
                        ?: throw YandexMusicException("Not connected")
                    val uid = YandexAuthRepository.uidOrNull()
                        ?: throw YandexMusicException("No account uid")
                    client.fetchLikedTracks(uid)
                }
                liked = list
                if (list.isEmpty()) likedError = "No liked tracks in this account"
            } catch (_: YandexUnauthorizedException) {
                likedError = "Token expired — reconnect"
                YandexAuthRepository.disconnect()
            } catch (_: Exception) {
                likedError = "Failed to load liked tracks"
            } finally {
                likedLoading = false
            }
        }
    }

    // ── Playlists (свои плейлисты ЯМ) ──
    var playlists by remember { mutableStateOf<List<YandexMusicClient.Playlist>>(emptyList()) }
    var playlistsLoading by remember { mutableStateOf(false) }
    var playlistsError by remember { mutableStateOf<String?>(null) }
    var openPlaylist by remember { mutableStateOf<YandexMusicClient.Playlist?>(null) }
    var playlistTracks by remember { mutableStateOf<List<YandexMusicClient.Track>>(emptyList()) }
    var playlistLoading by remember { mutableStateOf(false) }

    fun loadPlaylists(force: Boolean = false) {
        if (playlistsLoading || (!force && playlists.isNotEmpty())) return
        playlistsLoading = true
        playlistsError = null
        scope.launch {
            try {
                val list = withContext(Dispatchers.IO) {
                    val client = YandexAuthRepository.clientOrNull()
                        ?: throw YandexMusicException("Not connected")
                    val uid = YandexAuthRepository.uidOrNull()
                        ?: throw YandexMusicException("No account uid")
                    client.fetchUserPlaylists(uid)
                }
                playlists = list
                if (list.isEmpty()) playlistsError = "No playlists in this account"
            } catch (_: YandexUnauthorizedException) {
                playlistsError = "Token expired — reconnect"
                YandexAuthRepository.disconnect()
            } catch (_: Exception) {
                playlistsError = "Failed to load playlists"
            } finally {
                playlistsLoading = false
            }
        }
    }

    fun openPlaylistTracks(pl: YandexMusicClient.Playlist) {
        openPlaylist = pl
        playlistTracks = emptyList()
        playlistLoading = true
        playlistsError = null
        scope.launch {
            try {
                val list = withContext(Dispatchers.IO) {
                    val client = YandexAuthRepository.clientOrNull()
                        ?: throw YandexMusicException("Not connected")
                    val uid = YandexAuthRepository.uidOrNull()
                        ?: throw YandexMusicException("No account uid")
                    client.fetchPlaylistTracks(uid, pl.kind)
                }
                playlistTracks = list
            } catch (_: Exception) {
                playlistsError = "Failed to load playlist"
            } finally {
                playlistLoading = false
            }
        }
    }

    LaunchedEffect(section, connected) {
        if (!connected) return@LaunchedEffect
        when (section) {
            YandexSection.LIKED -> loadLiked()
            YandexSection.PLAYLISTS -> loadPlaylists()
            YandexSection.WAVE -> loadStations()
            YandexSection.SEARCH -> loadDiscovery()
            else -> Unit
        }
    }

    // Системный «назад» из открытого плейлиста возвращает к списку
    BackHandler(enabled = openPlaylist != null) {
        openPlaylist = null
        playlistTracks = emptyList()
    }

    /**
     * «Скачать всё»: строго по одному треку за раз (не залп из сотен
     * параллельных загрузок) — щадим сеть/CDN, прогресс виден по-трековыми
     * индикаторами. Повторный тап — стоп после текущего трека.
     */
    fun bulkDownload(tracks: List<YandexMusicClient.Track>) {
        if (bulkActive) {
            bulkStop = true
            return
        }
        scope.launch {
            bulkActive = true
            bulkStop = false
            val pending = tracks.filter {
                it.available &&
                    YandexDownloadManager.storageId(it.bareTrackId) !in offlineIds
            }
            bulkTotal = pending.size
            bulkDone = 0
            for (track in pending) {
                if (bulkStop) break
                val sid = YandexDownloadManager.storageId(track.bareTrackId)
                if (sid in offlineIds || YandexDownloadManager.isDownloading(sid)) {
                    bulkDone++
                    continue
                }
                val outcome = suspendCancellableCoroutine<YandexDownloadManager.Outcome> { cont ->
                    YandexDownloadManager.download(context, track) { out ->
                        if (cont.isActive) cont.resume(out)
                    }
                }
                if (outcome == YandexDownloadManager.Outcome.DONE) {
                    offlineIds = offlineIds + sid
                }
                bulkDone++
            }
            bulkActive = false
            refreshOffline()
        }
    }

    // ── Волна ЯМ (ротор) ──
    val yWaveActive by com.liquidmusicglass.data.yandex.YandexWaveEngine.isActive.collectAsState()
    var waveStarting by remember { mutableStateOf(false) }
    var waveError by remember { mutableStateOf<String?>(null) }

    fun startStation(station: String, onFail: (String) -> Unit) {
        if (waveStarting) return
        waveStarting = true
        waveError = null
        scope.launch {
            try {
                val tracks = withContext(Dispatchers.IO) {
                    com.liquidmusicglass.data.yandex.YandexWaveEngine.start(station)
                }
                if (tracks.isEmpty()) {
                    onFail("Couldn't start the station")
                } else {
                    PlayerController.playFromList(
                        context = context,
                        tracks = tracks,
                        startIndex = 0,
                        autoRefillType = "YWAVE"
                    )
                    PlayerController.ensureWaveRefill()
                }
            } catch (_: YandexUnauthorizedException) {
                onFail("Token expired — reconnect")
                YandexAuthRepository.disconnect()
            } catch (_: Exception) {
                onFail("Couldn't start the station")
            } finally {
                waveStarting = false
            }
        }
    }

    fun startYWave() {
        startStation(com.liquidmusicglass.data.yandex.YandexWaveEngine.PERSONAL_STATION) { waveError = it }
    }

    fun startTrackRadio(track: YandexMusicClient.Track) {
        val station = com.liquidmusicglass.data.yandex.YandexWaveEngine.trackStation(track.bareTrackId)
        startStation(station) { /* тихо: радио по треку — вторичное действие */ }
    }

    fun loadDiscovery() {
        if (discovery != null) return
        scope.launch {
            val d = withContext(Dispatchers.IO) {
                runCatching { YandexAuthRepository.clientOrNull()?.fetchDiscovery() }.getOrNull()
            }
            if (d != null) discovery = d
        }
    }

    // ── Каталог станций ротора (жанр/настроение) ──
    var stations by remember { mutableStateOf<List<YandexMusicClient.Station>>(emptyList()) }
    fun loadStations() {
        if (stations.isNotEmpty()) return
        scope.launch {
            val list = withContext(Dispatchers.IO) {
                runCatching {
                    YandexAuthRepository.clientOrNull()?.rotorStations() ?: emptyList()
                }.getOrDefault(emptyList())
            }
            stations = list
        }
    }

    // ── Детали артиста/альбома/плейлиста (стек навигации поверх секции) ──
    // extra нужен только плейлисту (kind); artist/album используют только id.
    var detailStack by remember { mutableStateOf<List<Triple<String, Long, Long>>>(emptyList()) }
    var artistPage by remember { mutableStateOf<YandexMusicClient.ArtistPage?>(null) }
    var albumPage by remember { mutableStateOf<YandexMusicClient.AlbumPage?>(null) }
    var playlistDetail by remember { mutableStateOf<Pair<String, List<YandexMusicClient.Track>>?>(null) }
    var detailLoading by remember { mutableStateOf(false) }
    val currentDetail = detailStack.lastOrNull()

    fun openArtist(id: Long) { detailStack = detailStack + Triple("artist", id, 0L) }
    fun openAlbum(id: Long) { detailStack = detailStack + Triple("album", id, 0L) }
    fun openSearchPlaylist(pl: YandexMusicClient.PlaylistBrief) {
        playlistDetail = pl.title to emptyList()
        detailStack = detailStack + Triple("playlist", pl.ownerUid, pl.kind)
    }
    fun popDetail() { detailStack = detailStack.dropLast(1) }

    LaunchedEffect(currentDetail) {
        val d = currentDetail ?: run {
            artistPage = null; albumPage = null; playlistDetail = null; return@LaunchedEffect
        }
        detailLoading = true
        artistPage = null
        albumPage = null
        val client = YandexAuthRepository.clientOrNull()
        withContext(Dispatchers.IO) {
            runCatching {
                when (d.first) {
                    "artist" -> artistPage = client?.fetchArtist(d.second)
                    "album" -> albumPage = client?.fetchAlbum(d.second)
                    "playlist" -> {
                        val tracks = client?.fetchPlaylistTracks(d.second, d.third) ?: emptyList()
                        playlistDetail = (playlistDetail?.first ?: "Playlist") to tracks
                    }
                }
            }
        }
        detailLoading = false
    }

    BackHandler(enabled = currentDetail != null) { popDetail() }

    fun playYList(tracks: List<YandexMusicClient.Track>, index: Int, refillId: String) {
        PlayerController.playFromList(
            context = context,
            tracks = tracks.map { it.toEngineTrack() },
            startIndex = index,
            autoRefillType = "playlist",
            autoRefillId = refillId
        )
    }

    fun doDisconnect() {
        com.liquidmusicglass.data.yandex.YandexWaveEngine.stop()
        YandexAuthRepository.disconnect()
        results = emptyList()
        searchArtists = emptyList()
        searchAlbums = emptyList()
        searchPlaylists = emptyList()
        detailStack = emptyList()
        query = ""
        searchError = null
        liked = emptyList()
        likedError = null
        playlists = emptyList()
        playlistsError = null
        openPlaylist = null
        playlistTracks = emptyList()
        bulkStop = true
        YandexSection.set(YandexSection.SEARCH)
    }

    Box(Modifier.fillMaxSize().background(lc.settingsBackground)) {
        Column(Modifier.fillMaxSize().imePadding()) {
            Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
            Spacer(Modifier.height(12.dp))

            // Header: back + icon + title/status
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(inputBg)
                        .liquidClickable(pressedScale = LiquidMotion.PressIcon) {
                            focusManager.clearFocus()
                            onBack()
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = "Back",
                        tint = lc.textPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(Modifier.width(12.dp))
                Icon(
                    painter = painterResource(R.drawable.ic_service_yandex),
                    contentDescription = null,
                    tint = Color.Unspecified,
                    modifier = Modifier.size(34.dp)
                )
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Yandex Music",
                        color = lc.textPrimary,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        if (connected) "Connected · ${label ?: "account"}"
                        else "Sign in with your Yandex account",
                        color = if (connected) Color(0xFF34C759) else lc.textSecondary,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            if (!connected) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 120.dp)
                ) {
                    ConnectBody(
                        inputBg = inputBg,
                        lc = lc,
                        onConnected = { /* state via repo flow */ }
                    )
                }
            } else {
                // Скачивание полных треков гейтится подпиской Плюс самого
                // аккаунта — без неё честно предупреждаем вместо «Download failed».
                // Показываем только в секциях со скачиванием (не в Волне/Аккаунте).
                if (!hasPlus && section != YandexSection.WAVE && section != YandexSection.AUTH) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(YandexYellow.copy(alpha = 0.12f))
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                    ) {
                        Text(
                            "Downloads require an active Yandex Plus subscription on this account.",
                            color = lc.textSecondary,
                            fontSize = 12.sp
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                }

                when (section) {
                    YandexSection.WAVE -> {
                        YandexWaveSection(
                            active = yWaveActive,
                            starting = waveStarting,
                            error = waveError,
                            stations = stations,
                            lc = lc,
                            onStart = { startYWave() },
                            onStation = { startStation(it.id) { e -> waveError = e } }
                        )
                    }
                    YandexSection.SEARCH -> {
                    // ── Search ──
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(46.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(inputBg)
                                .padding(horizontal = 12.dp),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Rounded.Search,
                                    null,
                                    tint = lc.textTertiary,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                BasicTextField(
                                    value = query,
                                    onValueChange = { query = it; searchError = null },
                                    textStyle = TextStyle(color = lc.textPrimary, fontSize = 15.sp),
                                    cursorBrush = SolidColor(YandexYellow),
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                    keyboardActions = KeyboardActions(onSearch = { runSearch() }),
                                    modifier = Modifier.weight(1f),
                                    decorationBox = { inner ->
                                        if (query.isEmpty()) {
                                            Text(
                                                "Search tracks…",
                                                color = lc.textTertiary,
                                                fontSize = 15.sp
                                            )
                                        }
                                        inner()
                                    }
                                )
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .height(46.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(
                                    if (query.isNotBlank() && !searching) YandexYellow
                                    else YandexYellow.copy(alpha = 0.4f)
                                )
                                .liquidClickable(
                                    enabled = query.isNotBlank() && !searching,
                                    pressedScale = LiquidMotion.PressButton
                                ) { runSearch() }
                                .padding(horizontal = 16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            if (searching) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    color = Color.Black,
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Text("Search", color = Color.Black, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            }
                        }
                    }

                    if (searchError != null) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            searchError!!,
                            color = Color(0xFFFF453A),
                            fontSize = 12.sp,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                    }

                    Spacer(Modifier.height(10.dp))

                    // Results + Disconnect последним элементом; 120dp снизу —
                    // просвет под плавающий бар (та же конвенция, что у остальных
                    // экранов Библиотеки).
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 120.dp)
                    ) {
                        val noSearch = results.isEmpty() && searchArtists.isEmpty() &&
                            searchAlbums.isEmpty() && searchPlaylists.isEmpty() &&
                            !searching && searchError == null
                        // Пустой поиск → discovery (чарт + новинки).
                        if (noSearch) {
                            val disc = discovery
                            if (disc != null && disc.newReleases.isNotEmpty()) {
                                item { SectionLabel("New releases", lc) }
                                items(disc.newReleases.take(10), key = { "nr${it.id}" }) { al ->
                                    AlbumRow(al, inputBg, lc) { openAlbum(al.id) }
                                }
                            }
                            if (disc != null && disc.chart.isNotEmpty()) {
                                item { SectionLabel("Chart", lc) }
                                itemsIndexed(disc.chart, key = { _, t -> "ch${t.id}" }) { index, track ->
                                    val sid = YandexDownloadManager.storageId(track.bareTrackId)
                                    YandexTrackRow(
                                        track = track,
                                        progress = dlProgress[sid],
                                        isDownloaded = sid in offlineIds,
                                        inputBg = inputBg,
                                        onPlay = { playYList(disc.chart, index, "yandex_chart") },
                                        onRadio = { startTrackRadio(track) },
                                        onOpenAlbum = track.id.substringAfter(":", "").toLongOrNull()
                                            ?.let { alb -> { openAlbum(alb) } },
                                        onDownload = {
                                            YandexDownloadManager.download(context, track) { out ->
                                                if (out == YandexDownloadManager.Outcome.DONE) refreshOffline()
                                            }
                                        },
                                        onCancel = { YandexDownloadManager.cancel(sid) }
                                    )
                                }
                            }
                            if (disc == null) {
                                item {
                                    Text(
                                        "Type a query and tap Search.\nTap a track to play it; " +
                                            "downloads go to Library → Downloads.",
                                        color = lc.textTertiary,
                                        fontSize = 13.sp,
                                        modifier = Modifier.padding(vertical = 24.dp)
                                    )
                                }
                            }
                        }
                        // Артисты
                        if (searchArtists.isNotEmpty()) {
                            item { SectionLabel("Artists", lc) }
                            items(searchArtists.take(5), key = { "sa${it.id}" }) { a ->
                                SimilarArtistRow(a, inputBg, lc) { openArtist(a.id) }
                            }
                        }
                        // Альбомы
                        if (searchAlbums.isNotEmpty()) {
                            item { SectionLabel("Albums", lc) }
                            items(searchAlbums.take(5), key = { "sal${it.id}" }) { al ->
                                AlbumRow(al, inputBg, lc) { openAlbum(al.id) }
                            }
                        }
                        // Плейлисты
                        if (searchPlaylists.isNotEmpty()) {
                            item { SectionLabel("Playlists", lc) }
                            items(searchPlaylists.take(5), key = { "sp${it.ownerUid}:${it.kind}" }) { p ->
                                SearchPlaylistRow(p, inputBg, lc) { openSearchPlaylist(p) }
                            }
                        }
                        if (results.isNotEmpty()) {
                            item { SectionLabel("Tracks", lc) }
                        }
                        itemsIndexed(results, key = { _, track -> track.id }) { index, track ->
                            val sid = YandexDownloadManager.storageId(track.bareTrackId)
                            val prog = dlProgress[sid]
                            val saved = sid in offlineIds
                            YandexTrackRow(
                                track = track,
                                progress = prog,
                                isDownloaded = saved,
                                inputBg = inputBg,
                                onPlay = {
                                    // Стриминг: вся выдача — очередь, тапнутый — старт.
                                    // Свежая прямая ссылка резолвится плеером на лету;
                                    // скачанные ym_-треки играются из файла без сети.
                                    PlayerController.playFromList(
                                        context = context,
                                        tracks = results.map { it.toEngineTrack() },
                                        startIndex = index,
                                        autoRefillType = "playlist",
                                        autoRefillId = "yandex_search"
                                    )
                                },
                                onRadio = { startTrackRadio(track) },
                                onOpenAlbum = track.id.substringAfter(":", "").toLongOrNull()?.let { alb -> { openAlbum(alb) } },
                                onDownload = {
                                    YandexDownloadManager.download(context, track) { outcome ->
                                        when (outcome) {
                                            YandexDownloadManager.Outcome.DONE -> refreshOffline()
                                            YandexDownloadManager.Outcome.FAILED -> scope.launch {
                                                searchError = "Download failed: ${track.title}"
                                            }
                                            YandexDownloadManager.Outcome.CANCELLED -> Unit
                                        }
                                    }
                                },
                                onCancel = { YandexDownloadManager.cancel(sid) }
                            )
                        }
                    }
                    }
                    YandexSection.LIKED -> {
                    // ── Liked (фонотека) ──
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                    ) {
                        Text(
                            if (likedLoading && liked.isEmpty()) "Loading likes…"
                            else "Liked tracks · ${liked.size}",
                            color = lc.textSecondary,
                            fontSize = 13.sp,
                            modifier = Modifier.weight(1f)
                        )
                        val pendingCount = liked.count {
                            it.available &&
                                YandexDownloadManager.storageId(it.bareTrackId) !in offlineIds
                        }
                        BulkDownloadButton(
                            active = bulkActive,
                            done = bulkDone,
                            total = bulkTotal,
                            enabled = hasPlus && !likedLoading && (bulkActive || pendingCount > 0)
                        ) { bulkDownload(liked) }
                    }

                    if (likedError != null) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            likedError!!,
                            color = Color(0xFFFF453A),
                            fontSize = 12.sp,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                    }

                    Spacer(Modifier.height(10.dp))

                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 120.dp)
                    ) {
                        if (likedLoading && liked.isEmpty()) {
                            item {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 32.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(28.dp),
                                        color = YandexYellow,
                                        strokeWidth = 2.5.dp
                                    )
                                }
                            }
                        }
                        itemsIndexed(liked, key = { _, track -> track.id }) { index, track ->
                            val sid = YandexDownloadManager.storageId(track.bareTrackId)
                            val prog = dlProgress[sid]
                            val saved = sid in offlineIds
                            YandexTrackRow(
                                track = track,
                                progress = prog,
                                isDownloaded = saved,
                                inputBg = inputBg,
                                onPlay = {
                                    PlayerController.playFromList(
                                        context = context,
                                        tracks = liked.map { it.toEngineTrack() },
                                        startIndex = index,
                                        autoRefillType = "playlist",
                                        autoRefillId = "yandex_likes"
                                    )
                                },
                                onRadio = { startTrackRadio(track) },
                                onOpenAlbum = track.id.substringAfter(":", "").toLongOrNull()?.let { alb -> { openAlbum(alb) } },
                                onDownload = {
                                    YandexDownloadManager.download(context, track) { outcome ->
                                        when (outcome) {
                                            YandexDownloadManager.Outcome.DONE -> refreshOffline()
                                            YandexDownloadManager.Outcome.FAILED -> scope.launch {
                                                likedError = "Download failed: ${track.title}"
                                            }
                                            YandexDownloadManager.Outcome.CANCELLED -> Unit
                                        }
                                    }
                                },
                                onCancel = { YandexDownloadManager.cancel(sid) }
                            )
                        }
                    }
                    }
                    YandexSection.PLAYLISTS -> {
                    // ── Playlists (свои плейлисты ЯМ) ──
                    val pl = openPlaylist
                    if (pl == null) {
                        if (playlistsError != null) {
                            Text(
                                playlistsError!!,
                                color = Color(0xFFFF453A),
                                fontSize = 12.sp,
                                modifier = Modifier.padding(horizontal = 16.dp)
                            )
                            Spacer(Modifier.height(8.dp))
                        }
                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 120.dp)
                        ) {
                            if (playlistsLoading && playlists.isEmpty()) {
                                item {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 32.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(28.dp),
                                            color = YandexYellow,
                                            strokeWidth = 2.5.dp
                                        )
                                    }
                                }
                            }
                            items(playlists, key = { it.kind }) { p ->
                                PlaylistRow(playlist = p, inputBg = inputBg) {
                                    openPlaylistTracks(p)
                                }
                            }
                        }
                    } else {
                        // Открытый плейлист: назад + название + Download all
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(30.dp)
                                    .clip(CircleShape)
                                    .background(inputBg)
                                    .liquidClickable(pressedScale = LiquidMotion.PressIcon) {
                                        openPlaylist = null
                                        playlistTracks = emptyList()
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Rounded.ArrowBack,
                                    contentDescription = "Back to playlists",
                                    tint = lc.textPrimary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                            Spacer(Modifier.width(10.dp))
                            Text(
                                pl.title,
                                color = lc.textPrimary,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(Modifier.width(8.dp))
                            val pendingCount = playlistTracks.count {
                                it.available &&
                                    YandexDownloadManager.storageId(it.bareTrackId) !in offlineIds
                            }
                            BulkDownloadButton(
                                active = bulkActive,
                                done = bulkDone,
                                total = bulkTotal,
                                enabled = hasPlus && !playlistLoading && (bulkActive || pendingCount > 0)
                            ) { bulkDownload(playlistTracks) }
                        }

                        Spacer(Modifier.height(10.dp))

                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 120.dp)
                        ) {
                            if (playlistLoading) {
                                item {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 32.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(28.dp),
                                            color = YandexYellow,
                                            strokeWidth = 2.5.dp
                                        )
                                    }
                                }
                            }
                            // Ключ с индексом: один трек может встретиться в
                            // плейлисте дважды, голый id уронил бы LazyColumn.
                            itemsIndexed(
                                playlistTracks,
                                key = { index, track -> "$index:${track.id}" }
                            ) { index, track ->
                                val sid = YandexDownloadManager.storageId(track.bareTrackId)
                                val prog = dlProgress[sid]
                                val saved = sid in offlineIds
                                YandexTrackRow(
                                    track = track,
                                    progress = prog,
                                    isDownloaded = saved,
                                    inputBg = inputBg,
                                    onPlay = {
                                        PlayerController.playFromList(
                                            context = context,
                                            tracks = playlistTracks.map { it.toEngineTrack() },
                                            startIndex = index,
                                            autoRefillType = "playlist",
                                            autoRefillId = "yandex_playlist_${pl.kind}"
                                        )
                                    },
                                    onRadio = { startTrackRadio(track) },
                                onOpenAlbum = track.id.substringAfter(":", "").toLongOrNull()?.let { alb -> { openAlbum(alb) } },
                                    onDownload = {
                                        YandexDownloadManager.download(context, track) { outcome ->
                                            when (outcome) {
                                                YandexDownloadManager.Outcome.DONE -> refreshOffline()
                                                YandexDownloadManager.Outcome.FAILED -> scope.launch {
                                                    playlistsError = "Download failed: ${track.title}"
                                                }
                                                YandexDownloadManager.Outcome.CANCELLED -> Unit
                                            }
                                        }
                                    },
                                    onCancel = { YandexDownloadManager.cancel(sid) }
                                )
                            }
                        }
                    }
                    }
                    YandexSection.AUTH -> {
                        AccountSection(
                            label = label,
                            hasPlus = hasPlus,
                            quality = quality,
                            inputBg = inputBg,
                            lc = lc,
                            onQuality = { YandexAuthRepository.setQuality(it) },
                            onDisconnect = { doDisconnect() }
                        )
                    }
                }
            }
        }

        // Оверлей деталей артиста/альбома/плейлиста поверх секции (свой back-стек).
        if (currentDetail != null) {
            YandexDetailOverlay(
                kind = currentDetail.first,
                artist = artistPage,
                album = albumPage,
                playlist = playlistDetail,
                loading = detailLoading,
                dlProgress = dlProgress,
                offlineIds = offlineIds,
                inputBg = inputBg,
                lc = lc,
                onBack = { popDetail() },
                onOpenArtist = { openArtist(it) },
                onOpenAlbum = { openAlbum(it) },
                onPlay = { list, i -> playYList(list, i, "yandex_detail") },
                onRadio = { startTrackRadio(it) },
                onDownload = { t ->
                    YandexDownloadManager.download(context, t) { out ->
                        if (out == YandexDownloadManager.Outcome.DONE) refreshOffline()
                    }
                },
                onCancel = { sid -> YandexDownloadManager.cancel(sid) }
            )
        }
    }
}

/**
 * Секция «Волна» ЯМ: персональная станция ротора. Старт — первая пачка +
 * radioStarted; дальше очередь бесконечно дозаправляется пачками ротора, а
 * дослушивания/скипы обучают станцию (как в официальном клиенте).
 */
@Composable
private fun YandexWaveSection(
    active: Boolean,
    starting: Boolean,
    error: String?,
    stations: List<YandexMusicClient.Station>,
    lc: com.liquidmusicglass.ui.theme.LiquidColors,
    onStart: () -> Unit,
    onStation: (YandexMusicClient.Station) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Герой: «Моя волна»
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(YandexYellow.copy(alpha = 0.12f))
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = com.liquidmusicglass.ui.icons.LiquidGlyphs.Equalizer,
                    contentDescription = null,
                    tint = YandexYellow.copy(alpha = if (active) 1f else 0.8f),
                    modifier = Modifier.size(40.dp)
                )
                Spacer(Modifier.height(12.dp))
                Text("My Wave", color = lc.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text(
                    if (active) "Playing — learns from your skips and full listens"
                    else "Endless personal station from your account",
                    color = lc.textSecondary,
                    fontSize = 12.sp
                )
                if (error != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(error, color = Color(0xFFFF453A), fontSize = 12.sp)
                }
                Spacer(Modifier.height(16.dp))
                Box(
                    modifier = Modifier
                        .height(46.dp)
                        .clip(RoundedCornerShape(23.dp))
                        .background(if (!starting) YandexYellow else YandexYellow.copy(alpha = 0.4f))
                        .liquidClickable(enabled = !starting, pressedScale = LiquidMotion.PressButton) { onStart() }
                        .padding(horizontal = 28.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (starting) {
                        CircularProgressIndicator(Modifier.size(22.dp), color = Color.Black, strokeWidth = 2.dp)
                    } else {
                        Text(
                            if (active) "Restart wave" else "Start My Wave",
                            color = Color.Black,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 15.sp
                        )
                    }
                }
            }
        }

        if (stations.isNotEmpty()) {
            item {
                Text(
                    "Stations",
                    color = lc.textSecondary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 4.dp, top = 6.dp)
                )
            }
            items(stations, key = { it.id }) { st ->
                StationRow(station = st, lc = lc, onClick = { onStation(st) })
            }
        }
    }
}

@Composable
private fun StationRow(
    station: YandexMusicClient.Station,
    lc: com.liquidmusicglass.ui.theme.LiquidColors,
    onClick: () -> Unit,
) {
    val accent = remember(station.bgColor) { parseHexColor(station.bgColor) ?: YandexYellow }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(accent.copy(alpha = 0.16f))
            .liquidClickable(pressedScale = LiquidMotion.PressCard) { onClick() }
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(accent)
        )
        Spacer(Modifier.width(12.dp))
        Text(
            station.name,
            color = lc.textPrimary,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** "#rrggbb" → Compose Color, или null. */
private fun parseHexColor(hex: String?): Color? {
    val s = hex?.trim()?.removePrefix("#") ?: return null
    if (s.length != 6) return null
    return runCatching { Color(("ff$s").toLong(16)) }.getOrNull()
}

/** Секция «Аккаунт»: имя, статус Плюса, кнопка выхода. */
@Composable
private fun AccountSection(
    label: String?,
    hasPlus: Boolean,
    quality: com.liquidmusicglass.data.yandex.YandexQuality,
    inputBg: Color,
    lc: com.liquidmusicglass.ui.theme.LiquidColors,
    onQuality: (com.liquidmusicglass.data.yandex.YandexQuality) -> Unit,
    onDisconnect: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(inputBg)
                .padding(16.dp)
        ) {
            Text(
                label ?: "Connected",
                color = lc.textPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(4.dp))
            Text(
                if (hasPlus) "Yandex Plus · active" else "No Yandex Plus — streaming only",
                color = if (hasPlus) Color(0xFF34C759) else lc.textSecondary,
                fontSize = 13.sp
            )
        }

        Spacer(Modifier.height(16.dp))

        // Качество стрима и скачивания
        Text(
            "Quality",
            color = lc.textSecondary,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
        )
        Row {
            QualityChip("Normal", "192 kbps", quality == com.liquidmusicglass.data.yandex.YandexQuality.NORMAL, inputBg, lc, Modifier.weight(1f)) {
                onQuality(com.liquidmusicglass.data.yandex.YandexQuality.NORMAL)
            }
            Spacer(Modifier.width(8.dp))
            QualityChip("High", "320 kbps", quality == com.liquidmusicglass.data.yandex.YandexQuality.HIGH, inputBg, lc, Modifier.weight(1f)) {
                onQuality(com.liquidmusicglass.data.yandex.YandexQuality.HIGH)
            }
            Spacer(Modifier.width(8.dp))
            QualityChip("Lossless", "FLAC", quality == com.liquidmusicglass.data.yandex.YandexQuality.LOSSLESS, inputBg, lc, Modifier.weight(1f)) {
                onQuality(com.liquidmusicglass.data.yandex.YandexQuality.LOSSLESS)
            }
        }
        if (quality == com.liquidmusicglass.data.yandex.YandexQuality.LOSSLESS && !hasPlus) {
            Spacer(Modifier.height(8.dp))
            Text(
                "FLAC needs Yandex Plus — High (320) is used without it.",
                color = lc.textTertiary,
                fontSize = 11.sp,
                modifier = Modifier.padding(start = 4.dp)
            )
        }

        Spacer(Modifier.height(20.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(46.dp)
                .clip(RoundedCornerShape(23.dp))
                .background(Color(0xFFFF453A).copy(alpha = 0.12f))
                .liquidClickable(pressedScale = LiquidMotion.PressButton) { onDisconnect() },
            contentAlignment = Alignment.Center
        ) {
            Text("Disconnect", color = Color(0xFFFF453A), fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        }
    }
}

@Composable
private fun QualityChip(
    title: String,
    subtitle: String,
    selected: Boolean,
    inputBg: Color,
    lc: com.liquidmusicglass.ui.theme.LiquidColors,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) YandexYellow else inputBg)
            .liquidClickable(pressedScale = LiquidMotion.PressButton) { onClick() }
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            title,
            color = if (selected) Color.Black else lc.textPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            subtitle,
            color = if (selected) Color.Black.copy(alpha = 0.7f) else lc.textTertiary,
            fontSize = 11.sp
        )
    }
}

@Composable
private fun BulkDownloadButton(
    active: Boolean,
    done: Int,
    total: Int,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .height(34.dp)
            .clip(RoundedCornerShape(17.dp))
            .background(if (enabled) YandexYellow else YandexYellow.copy(alpha = 0.35f))
            .liquidClickable(enabled = enabled, pressedScale = LiquidMotion.PressButton) { onClick() }
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            if (active) "Stop · $done/$total" else "Download all",
            color = Color.Black,
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp
        )
    }
}

@Composable
private fun PlaylistRow(
    playlist: YandexMusicClient.Playlist,
    inputBg: Color,
    onOpen: () -> Unit,
) {
    val lc = LiquidTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(inputBg)
            .liquidClickable { onOpen() }
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color.Black.copy(alpha = 0.2f))
        ) {
            if (!playlist.coverUrl.isNullOrBlank()) {
                AsyncImage(
                    model = playlist.coverUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(
                    painter = painterResource(R.drawable.ic_service_yandex),
                    contentDescription = null,
                    tint = Color.Unspecified,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(24.dp)
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                playlist.title,
                color = lc.textPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                "${playlist.trackCount} tracks",
                color = lc.textSecondary,
                fontSize = 12.sp
            )
        }
        Icon(
            Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            contentDescription = null,
            tint = lc.textTertiary,
            modifier = Modifier.size(22.dp)
        )
    }
}

@Composable
private fun ConnectBody(
    inputBg: Color,
    lc: com.liquidmusicglass.ui.theme.LiquidColors,
    onConnected: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var token by remember { mutableStateOf("") }
    var showToken by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showWebLogin by remember { mutableStateOf(false) }

    // Единая точка подключения: токен из встроенного входа и из ручного
    // поля идут через один и тот же connect() с шифрованным хранением.
    fun doConnect(raw: String) {
        if (busy || raw.isBlank()) return
        busy = true
        error = null
        scope.launch {
            try {
                YandexAuthRepository.connect(raw)
                token = "" // не держим plaintext в state после успеха
                onConnected()
            } catch (_: YandexUnauthorizedException) {
                error = "Invalid or expired token"
            } catch (_: YandexMusicException) {
                // Не пробрасываем e.message — там не должно быть токена, но UI-текст фиксированный
                error = "Connection failed"
            } catch (_: Exception) {
                error = "Connection failed"
            } finally {
                busy = false
            }
        }
    }

    Column {
        // Основной путь: встроенный вход через официальную страницу Яндекса —
        // токен перехватывается автоматически, вручную добывать не нужно.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(46.dp)
                .clip(RoundedCornerShape(23.dp))
                .background(if (!busy) YandexYellow else YandexYellow.copy(alpha = 0.4f))
                .liquidClickable(enabled = !busy, pressedScale = LiquidMotion.PressButton) {
                    error = null
                    showWebLogin = true
                },
            contentAlignment = Alignment.Center
        ) {
            if (busy) {
                CircularProgressIndicator(Modifier.size(22.dp), color = Color.Black, strokeWidth = 2.dp)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painter = painterResource(R.drawable.ic_service_yandex),
                        contentDescription = null,
                        tint = Color.Unspecified,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Sign in with Yandex", color = Color.Black, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        Text(
            "Or paste an OAuth token manually " +
                "(guide: github.com/MarshalX/yandex-music-api/discussions/513):",
            color = lc.textTertiary,
            fontSize = 12.sp
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(46.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(inputBg)
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                BasicTextField(
                    value = token,
                    onValueChange = { token = it; error = null },
                    textStyle = TextStyle(color = lc.textPrimary, fontSize = 14.sp),
                    cursorBrush = SolidColor(YandexYellow),
                    singleLine = true,
                    visualTransformation = if (showToken) VisualTransformation.None
                    else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                    decorationBox = { inner ->
                        if (token.isEmpty()) {
                            Text("y0_AgA… OAuth token", color = lc.textTertiary, fontSize = 14.sp, maxLines = 1)
                        }
                        inner()
                    }
                )
            }
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(inputBg)
                    .liquidClickable(pressedScale = LiquidMotion.PressButton) {
                        clipboard.getText()?.text?.let { token = it.trim(); error = null }
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Rounded.ContentPaste, "Paste", tint = lc.textSecondary, modifier = Modifier.size(20.dp))
            }
        }
        Text(
            if (showToken) "Hide token" else "Show token",
            color = lc.textTertiary,
            fontSize = 11.sp,
            modifier = Modifier
                .padding(top = 8.dp)
                .liquidClickable { showToken = !showToken }
        )
        if (error != null) {
            Spacer(Modifier.height(8.dp))
            Text(error!!, color = Color(0xFFFF453A), fontSize = 12.sp)
        }
        Spacer(Modifier.height(14.dp))
        val can = token.isNotBlank() && !busy
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(46.dp)
                .clip(RoundedCornerShape(23.dp))
                .background(if (can) inputBg else inputBg.copy(alpha = 0.5f))
                .liquidClickable(enabled = can, pressedScale = LiquidMotion.PressButton) {
                    doConnect(token)
                },
            contentAlignment = Alignment.Center
        ) {
            Text(
                "Connect with token",
                color = if (can) lc.textPrimary else lc.textTertiary,
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp
            )
        }
    }

    if (showWebLogin) {
        YandexWebLoginDialog(
            onToken = { intercepted ->
                showWebLogin = false
                doConnect(intercepted)
            },
            onDismiss = { showWebLogin = false }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun YandexTrackRow(
    track: YandexMusicClient.Track,
    progress: Float?,
    isDownloaded: Boolean,
    inputBg: Color,
    onPlay: () -> Unit = {},
    onRadio: () -> Unit = {},
    onDownload: () -> Unit,
    onCancel: () -> Unit = {},
    onOpenAlbum: (() -> Unit)? = null,
) {
    val lc = LiquidTheme.colors
    val downloading = progress != null

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(inputBg)
            // Тап — играть; долгое нажатие — радио по этому треку (станция ротора).
            .combinedClickable(
                enabled = track.available,
                onClick = { onPlay() },
                onLongClick = { onRadio() }
            )
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color.Black.copy(alpha = 0.2f))
                .then(
                    // Тап по обложке → открыть альбом трека (если доступно).
                    if (onOpenAlbum != null) {
                        Modifier.liquidClickable(pressedScale = LiquidMotion.PressIcon) { onOpenAlbum() }
                    } else Modifier
                )
        ) {
            if (!track.coverUrl.isNullOrBlank()) {
                AsyncImage(
                    model = track.coverUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(
                    painter = painterResource(R.drawable.ic_service_yandex),
                    contentDescription = null,
                    tint = Color.Unspecified,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(24.dp)
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                track.title,
                color = lc.textPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                track.artistsLine,
                color = lc.textSecondary,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (downloading) {
                Spacer(Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { (progress ?: 0f).coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = YandexYellow,
                    trackColor = YandexYellow.copy(alpha = 0.2f)
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(
                    when {
                        isDownloaded -> Color(0xFF34C759).copy(alpha = 0.15f)
                        downloading -> YandexYellow.copy(alpha = 0.2f)
                        else -> YandexYellow.copy(alpha = 0.18f)
                    }
                )
                .liquidClickable(
                    enabled = !isDownloaded && track.available,
                    pressedScale = LiquidMotion.PressIcon
                ) { if (downloading) onCancel() else onDownload() },
            contentAlignment = Alignment.Center
        ) {
            when {
                isDownloaded -> Icon(
                    Icons.Rounded.CheckCircle,
                    contentDescription = "Downloaded",
                    tint = Color(0xFF34C759),
                    modifier = Modifier.size(22.dp)
                )
                downloading -> {
                    // Тап по спиннеру = отмена загрузки
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = YandexYellow,
                        strokeWidth = 2.dp
                    )
                    Icon(
                        Icons.Rounded.Close,
                        contentDescription = "Cancel download",
                        tint = YandexYellow,
                        modifier = Modifier.size(12.dp)
                    )
                }
                else -> Icon(
                    Icons.Rounded.Download,
                    contentDescription = "Download",
                    tint = YandexYellow,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════
//  Детали артиста/альбома (оверлей поверх секции)
// ═══════════════════════════════════════════════════════════

@Composable
private fun YandexDetailOverlay(
    kind: String,
    artist: YandexMusicClient.ArtistPage?,
    album: YandexMusicClient.AlbumPage?,
    playlist: Pair<String, List<YandexMusicClient.Track>>?,
    loading: Boolean,
    dlProgress: Map<String, Float>,
    offlineIds: Set<String>,
    inputBg: Color,
    lc: com.liquidmusicglass.ui.theme.LiquidColors,
    onBack: () -> Unit,
    onOpenArtist: (Long) -> Unit,
    onOpenAlbum: (Long) -> Unit,
    onPlay: (List<YandexMusicClient.Track>, Int) -> Unit,
    onRadio: (YandexMusicClient.Track) -> Unit,
    onDownload: (YandexMusicClient.Track) -> Unit,
    onCancel: (String) -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(lc.settingsBackground)
    ) {
        Column(Modifier.fillMaxSize()) {
            Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
            Spacer(Modifier.height(12.dp))

            // Хедер: назад + заголовок
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(inputBg)
                        .liquidClickable(pressedScale = LiquidMotion.PressIcon) { onBack() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = "Back",
                        tint = lc.textPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    when (kind) {
                        "artist" -> artist?.name ?: "Artist"
                        "album" -> album?.title ?: "Album"
                        else -> playlist?.first ?: "Playlist"
                    },
                    color = lc.textPrimary,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(Modifier.height(12.dp))

            if (loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = YandexYellow, strokeWidth = 2.5.dp)
                }
                return@Column
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 120.dp)
            ) {
                if (kind == "album" && album != null) {
                    item { DetailHeaderArt(album.coverUrl, album.artistName, album.year?.toString()) }
                    // Название артиста альбома → открыть артиста (если id известен из первого трека)
                    val artistId = album.tracks.firstOrNull()?.artists?.firstOrNull()?.id
                    if (artistId != null && !album.artistName.isNullOrBlank()) {
                        item {
                            LinkRow("Artist: ${album.artistName}", lc) { onOpenArtist(artistId) }
                        }
                    }
                    itemsIndexed(album.tracks, key = { i, t -> "$i:${t.id}" }) { index, track ->
                        DetailTrackRow(track, dlProgress, offlineIds, inputBg,
                            onPlay = { onPlay(album.tracks, index) },
                            onRadio = { onRadio(track) },
                            onDownload = { onDownload(track) },
                            onCancel = onCancel)
                    }
                } else if (kind == "artist" && artist != null) {
                    if (!artist.coverUrl.isNullOrBlank()) {
                        item { DetailHeaderArt(artist.coverUrl, null, null) }
                    }
                    if (artist.topTracks.isNotEmpty()) {
                        item { SectionLabel("Popular", lc) }
                        itemsIndexed(artist.topTracks, key = { i, t -> "t$i:${t.id}" }) { index, track ->
                            DetailTrackRow(track, dlProgress, offlineIds, inputBg,
                                onPlay = { onPlay(artist.topTracks, index) },
                                onRadio = { onRadio(track) },
                                onDownload = { onDownload(track) },
                                onCancel = onCancel)
                        }
                    }
                    if (artist.albums.isNotEmpty()) {
                        item { SectionLabel("Albums", lc) }
                        items(artist.albums, key = { "a${it.id}" }) { alb ->
                            AlbumRow(alb, inputBg, lc) { onOpenAlbum(alb.id) }
                        }
                    }
                    if (artist.similar.isNotEmpty()) {
                        item { SectionLabel("Similar artists", lc) }
                        items(artist.similar, key = { "s${it.id}" }) { sim ->
                            SimilarArtistRow(sim, inputBg, lc) { onOpenArtist(sim.id) }
                        }
                    }
                } else if (kind == "playlist" && playlist != null) {
                    val tracks = playlist.second
                    itemsIndexed(tracks, key = { i, t -> "p$i:${t.id}" }) { index, track ->
                        DetailTrackRow(track, dlProgress, offlineIds, inputBg,
                            onPlay = { onPlay(tracks, index) },
                            onRadio = { onRadio(track) },
                            onDownload = { onDownload(track) },
                            onCancel = onCancel)
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailHeaderArt(coverUrl: String?, subtitle: String?, year: String?) {
    val lc = LiquidTheme.colors
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(160.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color.Black.copy(alpha = 0.2f))
        ) {
            if (!coverUrl.isNullOrBlank()) {
                AsyncImage(
                    model = coverUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        if (!subtitle.isNullOrBlank() || !year.isNullOrBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                listOfNotNull(subtitle?.takeIf { it.isNotBlank() }, year?.takeIf { it.isNotBlank() })
                    .joinToString(" · "),
                color = lc.textSecondary,
                fontSize = 13.sp
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String, lc: com.liquidmusicglass.ui.theme.LiquidColors) {
    Text(
        text,
        color = lc.textSecondary,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 4.dp, top = 10.dp, bottom = 2.dp)
    )
}

@Composable
private fun LinkRow(text: String, lc: com.liquidmusicglass.ui.theme.LiquidColors, onClick: () -> Unit) {
    Text(
        text,
        color = YandexYellow,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .padding(start = 4.dp, top = 2.dp, bottom = 4.dp)
            .liquidClickable { onClick() }
    )
}

@Composable
private fun DetailTrackRow(
    track: YandexMusicClient.Track,
    dlProgress: Map<String, Float>,
    offlineIds: Set<String>,
    inputBg: Color,
    onPlay: () -> Unit,
    onRadio: () -> Unit,
    onDownload: () -> Unit,
    onCancel: (String) -> Unit,
) {
    val sid = YandexDownloadManager.storageId(track.bareTrackId)
    YandexTrackRow(
        track = track,
        progress = dlProgress[sid],
        isDownloaded = sid in offlineIds,
        inputBg = inputBg,
        onPlay = onPlay,
        onRadio = onRadio,
        onDownload = onDownload,
        onCancel = { onCancel(sid) }
    )
}

@Composable
private fun AlbumRow(
    album: YandexMusicClient.AlbumBrief,
    inputBg: Color,
    lc: com.liquidmusicglass.ui.theme.LiquidColors,
    onOpen: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(inputBg)
            .liquidClickable { onOpen() }
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(48.dp).clip(RoundedCornerShape(10.dp))
                .background(Color.Black.copy(alpha = 0.2f))
        ) {
            if (!album.coverUrl.isNullOrBlank()) {
                AsyncImage(album.coverUrl, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(album.title, color = lc.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(album.artistName?.takeIf { it.isNotBlank() }, album.year?.toString())
                    .joinToString(" · "),
                color = lc.textSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = lc.textTertiary,
            modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun SearchPlaylistRow(
    playlist: YandexMusicClient.PlaylistBrief,
    inputBg: Color,
    lc: com.liquidmusicglass.ui.theme.LiquidColors,
    onOpen: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(inputBg)
            .liquidClickable { onOpen() }
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(48.dp).clip(RoundedCornerShape(10.dp))
                .background(Color.Black.copy(alpha = 0.2f))
        ) {
            if (!playlist.coverUrl.isNullOrBlank()) {
                AsyncImage(playlist.coverUrl, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                Icon(
                    painter = painterResource(R.drawable.ic_service_yandex),
                    contentDescription = null,
                    tint = Color.Unspecified,
                    modifier = Modifier.align(Alignment.Center).size(24.dp)
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(playlist.title, color = lc.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${playlist.trackCount} tracks", color = lc.textSecondary, fontSize = 12.sp)
        }
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = lc.textTertiary,
            modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun SimilarArtistRow(
    artist: YandexMusicClient.ArtistBrief,
    inputBg: Color,
    lc: com.liquidmusicglass.ui.theme.LiquidColors,
    onOpen: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(inputBg)
            .liquidClickable { onOpen() }
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(44.dp).clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.2f))
        ) {
            if (!artist.coverUrl.isNullOrBlank()) {
                AsyncImage(artist.coverUrl, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(artist.name, color = lc.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = lc.textTertiary,
            modifier = Modifier.size(22.dp))
    }
}
