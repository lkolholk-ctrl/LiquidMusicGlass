package com.liquidmusicglass.ui.screens

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.liquidmusicglass.api.icm.IcmApiFileLogger
import com.liquidmusicglass.api.icm.IcmAuthRepository
import com.liquidmusicglass.api.icm.IcmRepository
import com.liquidmusicglass.data.local.db.FavoriteTrackDatabase
import com.liquidmusicglass.data.local.db.FavoriteTrackEntity
import com.liquidmusicglass.data.local.db.LibraryRepository
import com.liquidmusicglass.engine.AudioDownloadManager
import com.liquidmusicglass.engine.PlaybackContext
import com.liquidmusicglass.engine.PlayerController
import com.liquidmusicglass.engine.Track
import com.liquidmusicglass.ui.glass.GlassDialog
import com.liquidmusicglass.ui.glass.GlassDialogButton
import com.liquidmusicglass.ui.glass.GlassKit
import com.liquidmusicglass.ui.glass.liquidClickable
import com.liquidmusicglass.ui.theme.LiquidMotion
import com.liquidmusicglass.ui.theme.LiquidTheme
import com.liquidmusicglass.ui.viewmodel.LibraryViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers

private val AppleRed = Color(0xFFFC3C44)

private enum class LibraryView { MAIN, FAVORITES, DOWNLOADS, LOCAL_PLAYLISTS, IMPORTED, LOCAL_AUDIO }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onNavigateToAlbum: (String) -> Unit = {},
    onNavigateToArtist: (String) -> Unit = {},
    onOpenPlaylist: (String) -> Unit = {},
    onOpenLocalLibrary: () -> Unit = {},
    backdrop: LayerBackdrop? = null
) {
    val lc = LiquidTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val viewModel = remember { LibraryViewModel(context) }

    var currentView by remember { mutableStateOf(LibraryView.MAIN) }

    // Favorites state
    val favorites by viewModel.favorites.collectAsState()
    val favoriteIds by viewModel.favoriteIds.collectAsState()
    val isSyncing by viewModel.isSyncing.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()

    // Downloads state
    val isPremium by IcmAuthRepository.isPremium.collectAsState()
    val db = remember { FavoriteTrackDatabase.getInstance(context) }
    val downloadedTracks by db.downloadsFlow.collectAsState(initial = emptyList())

    // Imported state
    val isLoggedIn by IcmAuthRepository.isLoggedIn.collectAsState()
    var importedPlaylists by remember { mutableStateOf<List<com.liquidmusicglass.api.icm.IcmUserPlaylist>>(emptyList()) }
    var isPlaylistsLoading by remember { mutableStateOf(false) }
    var showImportDialog by remember { mutableStateOf(false) }

    // Load cloud playlists when entering the Imported view or when logged in
    fun loadImportedPlaylists() {
        if (isLoggedIn) {
            scope.launch {
                isPlaylistsLoading = true
                val response = IcmRepository.getUserPlaylists(limit = 100)
                if (response != null) {
                    importedPlaylists = response.items
                }
                isPlaylistsLoading = false
            }
        }
    }

    LaunchedEffect(isLoggedIn) {
        loadImportedPlaylists()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Transparent)
    ) {
        when (currentView) {
            LibraryView.MAIN -> {
                // ── Вариант A (редизайн): системные разделы одной карточкой 28dp
                // с живыми сабтайтлами, ниже — сетка плейлистов 2 колонки с
                // мозаикой обложек. My Playlists и Imported слиты в одну сетку
                // (импортные — с бейджем источника), Local Audio + Медиатека —
                // один раздел «On this device». ──
                val localPlaylists by com.liquidmusicglass.engine.PlaylistManager.playlists.collectAsState()

                // Размер загрузок на диске — фоном, чтобы не трогать main.
                var downloadsSize by remember { mutableStateOf<String?>(null) }
                LaunchedEffect(downloadedTracks) {
                    downloadsSize = kotlinx.coroutines.withContext(Dispatchers.IO) {
                        val bytes = downloadedTracks.sumOf { entity ->
                            runCatching { java.io.File(entity.localPath).length() }.getOrDefault(0L)
                        }
                        when {
                            bytes <= 0L -> null
                            bytes < (1L shl 20) -> "%.0f KB".format(bytes / 1024.0)
                            bytes < (1L shl 30) -> "%.0f MB".format(bytes / 1048576.0)
                            else -> "%.1f GB".format(bytes / 1073741824.0)
                        }
                    }
                }

                // Объединённая сетка плейлистов: свои + импортированные.
                val playlistCells = remember(localPlaylists, importedPlaylists) {
                    localPlaylists.map { p ->
                        PlaylistCellData(
                            key = "local_${p.id}",
                            id = p.id,
                            name = p.name,
                            trackCount = p.tracks.size,
                            covers = p.tracks.mapNotNull { it.coverUrl }.distinct().take(4),
                            badge = null,
                            isImported = false
                        )
                    } + importedPlaylists.map { p ->
                        PlaylistCellData(
                            key = "icm_${p.id}",
                            id = p.id ?: "",
                            name = p.name.orEmpty(),
                            trackCount = p.trackCount ?: 0,
                            covers = listOfNotNull(p.cover?.replace("1000x1000", "400x400")),
                            badge = when {
                                p.source?.contains("yandex", true) == true -> "Yandex"
                                p.source?.contains("apple", true) == true -> "Apple"
                                p.source?.contains("spotify", true) == true -> "Spotify"
                                else -> "Cloud"
                            },
                            isImported = true
                        )
                    }
                }
                var playlistToDelete by remember { mutableStateOf<PlaylistCellData?>(null) }

                androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                    columns = androidx.compose.foundation.lazy.grid.GridCells.Fixed(2),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 178.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                        Column {
                            Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
                            Text(
                                text = "Playlists",
                                fontSize = 34.sp,
                                fontWeight = FontWeight.Bold,
                                color = lc.textPrimary,
                                modifier = Modifier.padding(top = 12.dp, bottom = 8.dp)
                            )
                        }
                    }

                    // ── Системные разделы: одна карточка, строки с живым контентом ──
                    item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(28.dp))
                                .background(lc.cardSurface)
                        ) {
                            MenuCard(
                                title = "Favorites",
                                subtitle = "${favorites.size} tracks",
                                icon = Icons.Default.Favorite,
                                tint = AppleRed,
                                onClick = { currentView = LibraryView.FAVORITES },
                                trailing = {
                                    CoverStack(favorites.take(3).mapNotNull { it.imageUrl })
                                }
                            )
                            SystemRowDivider()
                            MenuCard(
                                title = "Downloads",
                                subtitle = downloadsSize
                                    ?.let { "${downloadedTracks.size} tracks · $it" }
                                    ?: "${downloadedTracks.size} tracks",
                                icon = Icons.Default.Download,
                                tint = Color(0xFF29B6F6),
                                onClick = { currentView = LibraryView.DOWNLOADS }
                            )
                            SystemRowDivider()
                            MenuCard(
                                title = "On this device",
                                subtitle = "Артисты · Альбомы · Треки · Поиск",
                                icon = Icons.Rounded.LibraryMusic,
                                tint = Color(0xFFFF9F0A),
                                onClick = onOpenLocalLibrary
                            )
                        }
                    }

                    // ── Секция плейлистов: заголовок + импорт ──
                    item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "My Playlists",
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                                color = lc.textPrimary
                            )
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .background(Color(0xFF30D158).copy(alpha = 0.14f), CircleShape)
                                    .clip(CircleShape)
                                    .liquidClickable(pressedScale = LiquidMotion.PressIcon) {
                                        showImportDialog = true
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Rounded.Add, null, tint = Color(0xFF30D158), modifier = Modifier.size(20.dp))
                            }
                        }
                    }

                    if (playlistCells.isEmpty()) {
                        item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Rounded.PlaylistPlay, null,
                                    tint = lc.iconMuted, modifier = Modifier.size(48.dp)
                                )
                                Spacer(Modifier.height(10.dp))
                                Text(
                                    if (isLoggedIn) "No playlists yet. Tap + to import one!"
                                    else "Sign in to sync playlists, or tap + to import",
                                    color = lc.textSecondary,
                                    fontSize = 14.sp
                                )
                            }
                        }
                    } else {
                        items(playlistCells.size, key = { playlistCells[it].key }) { i ->
                            PlaylistCell(
                                data = playlistCells[i],
                                onClick = { onOpenPlaylist(playlistCells[i].id) },
                                onLongPress = { playlistToDelete = playlistCells[i] }
                            )
                        }
                    }
                }

                // ── Долгий тап по плейлисту → удаление ──
                playlistToDelete?.let { cell ->
                    GlassDialog(
                        visible = true,
                        onDismiss = { playlistToDelete = null },
                        title = "Delete Playlist",
                        message = "Are you sure you want to delete '${cell.name}'?",
                        icon = Icons.Rounded.Close,
                        iconTint = Color(0xFFFF5252),
                        primaryButton = GlassDialogButton(
                            text = "Delete",
                            onClick = {
                                if (cell.isImported) {
                                    scope.launch {
                                        IcmRepository.deleteUserPlaylist(cell.id)
                                        loadImportedPlaylists()
                                    }
                                } else {
                                    com.liquidmusicglass.engine.PlaylistManager.delete(cell.id)
                                }
                                playlistToDelete = null
                            },
                            backgroundColor = Color(0xFFFF5252),
                            textColor = Color.White
                        ),
                        secondaryButton = GlassDialogButton(
                            text = "Cancel",
                            onClick = { playlistToDelete = null },
                            backgroundColor = lc.textPrimary.copy(alpha = 0.08f),
                            textColor = lc.textSecondary
                        )
                    )
                }
            }

            LibraryView.LOCAL_AUDIO -> {
                LocalAudioView(
                    context = context,
                    onBack = { currentView = LibraryView.MAIN }
                )
            }

            LibraryView.FAVORITES -> {
                Column(modifier = Modifier.fillMaxSize()) {
                    // Header
                    SubHeader("Favorites", onBack = { currentView = LibraryView.MAIN }) {
                        if (isSyncing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = AppleRed,
                                strokeWidth = 2.dp
                            )
                        } else {
                            IconButton(onClick = { viewModel.syncWithCloud() }) {
                                Icon(Icons.Filled.Refresh, null, tint = lc.iconMuted, modifier = Modifier.size(20.dp))
                            }
                        }
                    }

                    // Play/Shuffle
                    if (favorites.isNotEmpty()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            ActionButton("Play All", Icons.Default.PlayArrow, onClick = { viewModel.playAll(context) }, modifier = Modifier.weight(1f))
                            ActionButton("Shuffle", Icons.Default.Shuffle, onClick = { viewModel.shuffleAndPlay(context) }, modifier = Modifier.weight(1f))
                        }
                    }

                    // Content
                    if (favorites.isEmpty() && !isSyncing) {
                        EmptyState("No favorites yet", Icons.Default.Favorite)
                    } else {
                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(bottom = 178.dp)
                        ) {
                            items(favorites, key = { it.trackId }) { track ->
                                FavoriteTrackItem(
                                    track = track,
                                    isLiked = track.trackId in favoriteIds,
                                    onClick = { viewModel.playTrack(context, track.trackId) },
                                    onToggleLike = {
                                        scope.launch {
                                            val repo = LibraryRepository.getInstance(context)
                                            val t = Track(
                                                id = track.trackId,
                                                title = track.title,
                                                artist = track.artistName ?: "Unknown Artist",
                                                albumName = track.albumTitle ?: "",
                                                uri = Uri.parse("https://byicloud.online/track/${track.trackId}"),
                                                durationMs = track.durationMs,
                                                albumId = track.collectionId?.hashCode()?.toLong() ?: -1L,
                                                coverUrl = track.imageUrl
                                            )
                                            repo.toggleFavorite(t)
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }

            LibraryView.DOWNLOADS -> {
                var showClearAllDialog by remember { mutableStateOf(false) }
                var trackToDelete by remember { mutableStateOf<com.liquidmusicglass.data.local.db.DownloadedTrackEntity?>(null) }
                val isDialogActive = trackToDelete != null || showClearAllDialog

                // ── Screen content (blurred when dialog is active) ──
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .then(if (isDialogActive) Modifier.blur(16.dp) else Modifier)
                ) {
                    SubHeader(
                        title = "Downloads",
                        onBack = { currentView = LibraryView.MAIN },
                        actions = {
                            if (downloadedTracks.isNotEmpty()) {
                                IconButton(onClick = { showClearAllDialog = true }) {
                                    Icon(
                                        imageVector = Icons.Rounded.Close,
                                        contentDescription = "Clear all downloads",
                                        tint = AppleRed,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                            }
                        }
                    )

                    if (!isPremium && downloadedTracks.isEmpty()) {
                        PremiumDownloadsPromo(backdrop = backdrop)
                    } else if (downloadedTracks.isEmpty()) {
                        EmptyState("No downloaded tracks yet", Icons.Default.Download)
                    } else {
                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(bottom = 178.dp)
                        ) {
                            items(downloadedTracks, key = { it.trackId }) { trackEntity ->
                                DownloadedTrackItem(
                                    track = trackEntity,
                                    onClick = {
                                        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                            val tracks = downloadedTracks.map { entity ->
                                                Track(
                                                    id = entity.trackId,
                                                    title = entity.title,
                                                    artist = entity.artistName ?: "Unknown Artist",
                                                    albumName = entity.albumTitle ?: "",
                                                    uri = Uri.fromFile(java.io.File(entity.localPath)),
                                                    durationMs = entity.durationMs,
                                                    albumId = entity.albumTitle?.hashCode()?.toLong() ?: -1L,
                                                    coverUrl = entity.localCoverPath ?: entity.imageUrl
                                                )
                                            }
                                            val startIndex = tracks.indexOfFirst { it.id == trackEntity.trackId }
                                            if (startIndex >= 0) {
                                                PlayerController.playLocalOnJuce(
                                                    context = context,
                                                    tracks = tracks,
                                                    startIndex = startIndex,
                                                    playbackContext = PlaybackContext.Downloads
                                                )
                                            }
                                        }
                                    },
                                    onDelete = { trackToDelete = trackEntity }
                                )
                            }
                        }
                    }
                }

                // ── Dialogs OUTSIDE the blurred content ──
                if (showClearAllDialog) {
                    GlassDialog(
                        visible = showClearAllDialog,
                        onDismiss = { showClearAllDialog = false },
                        title = "Clear All Downloads",
                        message = "This will permanently delete all ${downloadedTracks.size} downloaded tracks from your device and the database. This action cannot be undone.",
                        icon = Icons.Default.Download,
                        iconTint = AppleRed,
                        primaryButton = GlassDialogButton(
                            text = "Clear All",
                            onClick = {
                                showClearAllDialog = false
                                scope.launch {
                                    AudioDownloadManager.clearAllDownloads(context)
                                }
                            },
                            backgroundColor = AppleRed,
                            textColor = Color.White
                        ),
                        secondaryButton = GlassDialogButton(
                            text = "Cancel",
                            onClick = { showClearAllDialog = false },
                            backgroundColor = lc.textPrimary.copy(alpha = 0.08f),
                            textColor = lc.textSecondary
                        )
                    )
                }

                trackToDelete?.let { track ->
                    GlassDialog(
                        visible = true,
                        onDismiss = { trackToDelete = null },
                        title = "Delete Offline Track",
                        message = "Remove '${track.title}' from your device storage?",
                        icon = Icons.Rounded.Close,
                        iconTint = Color(0xFFFF5252),
                        primaryButton = GlassDialogButton(
                            text = "Remove",
                            onClick = {
                                AudioDownloadManager.deleteDownloadedTrack(context, track.trackId)
                                trackToDelete = null
                            },
                            backgroundColor = Color(0xFFFF5252),
                            textColor = Color.White
                        ),
                        secondaryButton = GlassDialogButton(
                            text = "Cancel",
                            onClick = { trackToDelete = null },
                            backgroundColor = lc.textPrimary.copy(alpha = 0.08f),
                            textColor = lc.textSecondary
                        )
                    )
                }
            }

            LibraryView.LOCAL_PLAYLISTS -> {
                val localPlaylists by com.liquidmusicglass.engine.PlaylistManager.playlists.collectAsState()
                var playlistToDelete by remember { mutableStateOf<com.liquidmusicglass.engine.PlaylistManager.Playlist?>(null) }
                val isDialogActive = playlistToDelete != null

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .then(if (isDialogActive) Modifier.blur(16.dp) else Modifier)
                ) {
                    SubHeader("My Playlists", onBack = { currentView = LibraryView.MAIN }) {
                        IconButton(onClick = { showImportDialog = true }) {
                            Icon(Icons.Rounded.Add, null, tint = Color(0xFF30D158), modifier = Modifier.size(24.dp))
                        }
                    }

                    if (localPlaylists.isEmpty()) {
                        EmptyState("No playlists yet.\nImport one to get started!", Icons.AutoMirrored.Rounded.PlaylistPlay)
                    } else {
                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 120.dp)
                        ) {
                            items(localPlaylists, key = { it.id }) { playlist ->
                                LocalPlaylistRow(
                                    playlist = playlist,
                                    onClick = { onOpenPlaylist(playlist.id) },
                                    onDelete = { playlistToDelete = playlist }
                                )
                                Spacer(Modifier.height(8.dp))
                            }
                        }
                    }
                }

                // ── Screen-level modal dialog for local playlist deletion ──
                playlistToDelete?.let { playlist ->
                    GlassDialog(
                        visible = true,
                        onDismiss = { playlistToDelete = null },
                        title = "Delete Playlist",
                        message = "Are you sure you want to delete '${playlist.name}'?",
                        icon = Icons.Rounded.Close,
                        iconTint = Color(0xFFFF5252),
                        primaryButton = GlassDialogButton(
                            text = "Delete",
                            onClick = {
                                com.liquidmusicglass.engine.PlaylistManager.delete(playlist.id)
                                playlistToDelete = null
                            },
                            backgroundColor = Color(0xFFFF5252),
                            textColor = Color.White
                        ),
                        secondaryButton = GlassDialogButton(
                            text = "Cancel",
                            onClick = { playlistToDelete = null },
                            backgroundColor = lc.textPrimary.copy(alpha = 0.08f),
                            textColor = lc.textSecondary
                        )
                    )
                }
            }

            LibraryView.IMPORTED -> {
                var importedPlaylistToDelete by remember { mutableStateOf<com.liquidmusicglass.api.icm.IcmUserPlaylist?>(null) }
                val isDialogActive = importedPlaylistToDelete != null

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .then(if (isDialogActive) Modifier.blur(16.dp) else Modifier)
                ) {
                    SubHeader("Imported Playlists", onBack = { currentView = LibraryView.MAIN }) {
                        if (isLoggedIn) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { loadImportedPlaylists() }) {
                                    Icon(Icons.Filled.Refresh, null, tint = lc.iconMuted, modifier = Modifier.size(20.dp))
                                }
                                IconButton(onClick = { showImportDialog = true }) {
                                    Icon(Icons.Rounded.Add, null, tint = AppleRed, modifier = Modifier.size(24.dp))
                                }
                            }
                        }
                    }

                    if (!isLoggedIn) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.AutoMirrored.Rounded.PlaylistPlay, null, tint = lc.iconMuted, modifier = Modifier.size(64.dp))
                                Spacer(Modifier.height(16.dp))
                                Text("Sync Playlists", color = lc.textPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    "Sign in to your ICM account in the Profile tab to import, view and sync your Yandex Music and Apple Music playlists.",
                                    color = lc.textSecondary,
                                    fontSize = 14.sp,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                    lineHeight = 20.sp
                                )
                            }
                        }
                    } else if (isPlaylistsLoading && importedPlaylists.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = AppleRed)
                        }
                    } else {
                        val yandexPlaylists = importedPlaylists.filter { it.source?.lowercase()?.contains("yandex") == true }
                        val applePlaylists = importedPlaylists.filter { it.source?.lowercase()?.contains("apple") == true }
                        val otherPlaylists = importedPlaylists.filter { 
                            val s = it.source?.lowercase() 
                            s != null && !s.contains("yandex") && !s.contains("apple")
                        } + importedPlaylists.filter { it.source == null }

                        if (importedPlaylists.isEmpty()) {
                            EmptyState("No imported playlists yet.\nTap + to import one!", Icons.AutoMirrored.Rounded.PlaylistPlay)
                        } else {
                            LazyColumn(
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 120.dp)
                            ) {
                                // ── Yandex Music Section ──
                                if (yandexPlaylists.isNotEmpty()) {
                                    item {
                                        SectionHeader("Yandex Music", yandexPlaylists.size)
                                    }
                                    items(yandexPlaylists, key = { it.id ?: "" }) { playlist ->
                                        ImportedPlaylistRow(
                                            playlist = playlist,
                                            onClick = { onOpenPlaylist(playlist.id ?: "") },
                                            onDelete = { importedPlaylistToDelete = playlist }
                                        )
                                        Spacer(Modifier.height(8.dp))
                                    }
                                }

                                // ── Apple Music Section ──
                                if (applePlaylists.isNotEmpty()) {
                                    if (yandexPlaylists.isNotEmpty()) {
                                        item { Spacer(Modifier.height(24.dp)) }
                                    }
                                    item {
                                        SectionHeader("Apple Music", applePlaylists.size)
                                    }
                                    items(applePlaylists, key = { it.id ?: "" }) { playlist ->
                                        ImportedPlaylistRow(
                                            playlist = playlist,
                                            onClick = { onOpenPlaylist(playlist.id ?: "") },
                                            onDelete = { importedPlaylistToDelete = playlist }
                                        )
                                        Spacer(Modifier.height(8.dp))
                                    }
                                }

                                // ── Other Section ──
                                if (otherPlaylists.isNotEmpty()) {
                                    if (yandexPlaylists.isNotEmpty() || applePlaylists.isNotEmpty()) {
                                        item { Spacer(Modifier.height(24.dp)) }
                                    }
                                    item {
                                        SectionHeader("Other", otherPlaylists.size)
                                    }
                                    items(otherPlaylists, key = { it.id ?: "" }) { playlist ->
                                        ImportedPlaylistRow(
                                            playlist = playlist,
                                            onClick = { onOpenPlaylist(playlist.id ?: "") },
                                            onDelete = { importedPlaylistToDelete = playlist }
                                        )
                                        Spacer(Modifier.height(8.dp))
                                    }
                                }
                            }
                        }
                    }
                }

                // ── Screen-level modal dialog for imported playlist deletion ──
                importedPlaylistToDelete?.let { playlist ->
                    GlassDialog(
                        visible = true,
                        onDismiss = { importedPlaylistToDelete = null },
                        title = "Delete Playlist",
                        message = "Are you sure you want to delete '${playlist.name}' from your ICM library?",
                        icon = Icons.Rounded.Close,
                        iconTint = Color(0xFFFF5252),
                        primaryButton = GlassDialogButton(
                            text = "Delete",
                            onClick = {
                                scope.launch {
                                    IcmRepository.deleteUserPlaylist(playlist.id ?: "")
                                    loadImportedPlaylists()
                                }
                                importedPlaylistToDelete = null
                            },
                            backgroundColor = Color(0xFFFF5252),
                            textColor = Color.White
                        ),
                        secondaryButton = GlassDialogButton(
                            text = "Cancel",
                            onClick = { importedPlaylistToDelete = null },
                            backgroundColor = lc.textPrimary.copy(alpha = 0.08f),
                            textColor = lc.textSecondary
                        )
                    )
                }
            }
        }

        // ── Background Import System ──
        // Uses global singleton manager — survives tab switches.
        // The progress overlay is shown at the root level (AppRoot).

        // Import screen (#74): per-service cards with real brand icons, daily
        // remaining count, and in-app progress. Stays open during import so the
        // user watches "X of Y matched" right on the card.
        if (showImportDialog) {
            ImportServicesSheet(
                onDismiss = {
                    showImportDialog = false
                    // Сбросить осевший финальный статус, чтобы при следующем
                    // открытии карточка не показывала старый Success/Error.
                    val st = com.liquidmusicglass.data.playlistimport.PlaylistImportManager
                        .importState.value
                    if (st is com.liquidmusicglass.data.playlistimport.ImportState.Success ||
                        st is com.liquidmusicglass.data.playlistimport.ImportState.Error
                    ) {
                        com.liquidmusicglass.data.playlistimport.PlaylistImportManager.dismiss()
                    }
                },
                onImport = { url ->
                    com.liquidmusicglass.data.playlistimport.PlaylistImportManager
                        .importPlaylist(url, context)
                }
            )
        }

        // Error Snackbar
        if (errorMessage != null) {
            LaunchedEffect(errorMessage) {
                delay(3000)
                viewModel.clearError()
            }
        }
    }
}

// ═════════════════════════════════════════════════════════════════
//  Components
// ═════════════════════════════════════════════════════════════════

@Composable
private fun MenuCard(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color,
    onClick: () -> Unit,
    trailing: @Composable () -> Unit = {}
) {
    val lc = LiquidTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp)
            .liquidClickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .background(tint.copy(alpha = 0.12f), CircleShape)
                .clip(CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp))
        }

        Spacer(Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = lc.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text(subtitle, color = lc.textSecondary, fontSize = 13.sp)
        }

        trailing()

        Icon(Icons.Rounded.ChevronRight, null, tint = lc.textTertiary, modifier = Modifier.size(24.dp))
    }
}

/** Разделитель строк внутри системной карточки (с отступом под иконку). */
@Composable
private fun SystemRowDivider() {
    val lc = LiquidTheme.colors
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 74.dp, end = 16.dp)
            .height(0.8.dp)
            .background(lc.textPrimary.copy(alpha = 0.06f))
    )
}

/** Стопка обложек внахлёст (последние лайкнутые) — живой контент строки Favorites. */
@Composable
private fun CoverStack(covers: List<String>) {
    if (covers.isEmpty()) return
    val overlap = 18
    Box(modifier = Modifier.width(((covers.size - 1) * overlap + 28).dp)) {
        covers.forEachIndexed { i, url ->
            AsyncImage(
                model = url.replace("1000x1000", "200x200"),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .padding(start = (i * overlap).dp)
                    .size(28.dp)
                    .clip(RoundedCornerShape(7.dp))
            )
        }
    }
    Spacer(Modifier.width(8.dp))
}

/** Данные ячейки сетки плейлистов (свой или импортированный). */
private data class PlaylistCellData(
    val key: String,
    val id: String,
    val name: String,
    val trackCount: Int,
    val covers: List<String>,
    val badge: String?,
    val isImported: Boolean
)

/** Ячейка плейлиста: мозаика 2×2 из обложек (или одна/заглушка) + имя + счётчик. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun PlaylistCell(
    data: PlaylistCellData,
    onClick: () -> Unit,
    onLongPress: () -> Unit
) {
    val lc = LiquidTheme.colors
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
                onLongClick = onLongPress
            )
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(16.dp))
                .background(lc.cardSurface)
        ) {
            when {
                data.covers.size >= 4 -> {
                    Column(Modifier.fillMaxSize()) {
                        Row(Modifier.weight(1f)) {
                            MosaicTile(data.covers[0], Modifier.weight(1f))
                            MosaicTile(data.covers[1], Modifier.weight(1f))
                        }
                        Row(Modifier.weight(1f)) {
                            MosaicTile(data.covers[2], Modifier.weight(1f))
                            MosaicTile(data.covers[3], Modifier.weight(1f))
                        }
                    }
                }
                data.covers.isNotEmpty() -> {
                    AsyncImage(
                        model = data.covers.first(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                else -> {
                    Icon(
                        Icons.AutoMirrored.Rounded.PlaylistPlay,
                        null,
                        tint = lc.iconMuted,
                        modifier = Modifier.size(40.dp).align(Alignment.Center)
                    )
                }
            }
            data.badge?.let { badge ->
                SourceBadge(
                    source = badge,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                )
            }
        }
        Spacer(Modifier.height(7.dp))
        Text(
            data.name,
            color = lc.textPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            "${data.trackCount} tracks",
            color = lc.textSecondary,
            fontSize = 12.sp
        )
    }
}

@Composable
private fun MosaicTile(url: String, modifier: Modifier) {
    AsyncImage(
        model = url.replace("1000x1000", "300x300"),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier.fillMaxHeight()
    )
}

/**
 * Значок источника плейлиста в углу обложки: фирменный глиф сервиса, откуда
 * приехал плейлист. Значки рисуются на Canvas (без растровых ассетов) —
 * узнаваемо и легко без внешних файлов.
 *  - Spotify → зелёный круг с тремя «волнами»;
 *  - Apple   → красно-розовый градиентный квадрат с нотой;
 *  - Yandex  → красный круг с play-треугольником;
 *  - Cloud/прочее → тёмная пилюля с облачком (импорт без явного источника).
 */
@Composable
private fun SourceBadge(source: String, modifier: Modifier = Modifier) {
    val d = 22.dp
    Box(
        modifier = modifier
            .size(d)
            .clip(RoundedCornerShape(50))
            .background(Color.Black.copy(alpha = 0.28f))   // мягкая подложка на любой обложке
            .padding(2.dp),
        contentAlignment = Alignment.Center
    ) {
        when {
            source.equals("Spotify", true) -> Canvas(Modifier.fillMaxSize()) {
                val r = size.minDimension / 2f
                val c = center
                drawCircle(Color(0xFF1DB954), radius = r, center = c)
                // три «волны» — дуги, вложенные снизу вверх
                val white = Color.White
                val sw = size.minDimension * 0.075f
                listOf(0.62f to 0.30f, 0.46f to 0.18f, 0.30f to 0.08f).forEach { (span, yOff) ->
                    val rad = size.minDimension * span / 2f
                    drawArc(
                        color = white,
                        startAngle = 200f, sweepAngle = 140f, useCenter = false,
                        topLeft = Offset(c.x - rad, c.y - rad - size.minDimension * yOff),
                        size = androidx.compose.ui.geometry.Size(rad * 2, rad * 2),
                        style = Stroke(width = sw, cap = StrokeCap.Round)
                    )
                }
            }
            source.equals("Apple", true) -> Canvas(Modifier.fillMaxSize()) {
                val corner = size.minDimension * 0.28f
                drawRoundRect(
                    brush = Brush.verticalGradient(listOf(Color(0xFFFB5C74), Color(0xFFFA233B))),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(corner, corner)
                )
                // белая восьмая нота
                val w = size.width; val h = size.height
                val stemX = w * 0.62f
                drawLine(
                    Color.White, Offset(stemX, h * 0.28f), Offset(stemX, h * 0.66f),
                    strokeWidth = w * 0.07f, cap = StrokeCap.Round
                )
                drawLine(
                    Color.White, Offset(stemX, h * 0.30f), Offset(w * 0.74f, h * 0.24f),
                    strokeWidth = w * 0.07f, cap = StrokeCap.Round
                )
                drawCircle(Color.White, radius = w * 0.11f, center = Offset(w * 0.50f, h * 0.68f))
            }
            source.equals("Yandex", true) -> Canvas(Modifier.fillMaxSize()) {
                val r = size.minDimension / 2f
                val c = center
                drawCircle(Color(0xFFFC3F1D), radius = r, center = c)   // фирменный красный Яндекса
                // белый play-треугольник
                val tri = Path().apply {
                    val s = size.minDimension
                    moveTo(c.x - s * 0.11f, c.y - s * 0.17f)
                    lineTo(c.x - s * 0.11f, c.y + s * 0.17f)
                    lineTo(c.x + s * 0.20f, c.y)
                    close()
                }
                drawPath(tri, Color.White)
            }
            else -> Canvas(Modifier.fillMaxSize()) {
                val r = size.minDimension / 2f
                val c = center
                drawCircle(Color(0xFF3A3A3C), radius = r, center = c)
                // простое облачко
                val s = size.minDimension
                drawCircle(Color.White, radius = s * 0.12f, center = Offset(c.x - s * 0.12f, c.y + s * 0.02f))
                drawCircle(Color.White, radius = s * 0.16f, center = Offset(c.x + s * 0.02f, c.y - s * 0.04f))
                drawCircle(Color.White, radius = s * 0.12f, center = Offset(c.x + s * 0.16f, c.y + s * 0.02f))
                drawRoundRect(
                    Color.White,
                    topLeft = Offset(c.x - s * 0.24f, c.y + s * 0.02f),
                    size = androidx.compose.ui.geometry.Size(s * 0.48f, s * 0.14f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(s * 0.07f, s * 0.07f)
                )
            }
        }
    }
}

/**
 * Stage 7b — экран локальной музыки (MediaStore). Запрашивает READ_MEDIA_AUDIO
 * (13+) / READ_EXTERNAL_STORAGE, сканирует библиотеку, играет через Media3
 * (PlayerController.playFromList) — content:// URI проходит как локальный файл.
 */
@Composable
private fun LocalAudioView(
    context: Context,
    onBack: () -> Unit
) {
    val lc = LiquidTheme.colors
    val scope = rememberCoroutineScope()

    val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        android.Manifest.permission.READ_MEDIA_AUDIO
    } else {
        android.Manifest.permission.READ_EXTERNAL_STORAGE
    }
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        )
    }
    var tracks by remember { mutableStateOf<List<Track>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }

    fun scan() {
        scope.launch {
            loading = true
            tracks = com.liquidmusicglass.data.local.LocalAudioRepository.load(context)
            loading = false
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPermission = granted
        if (granted) scan()
    }

    LaunchedEffect(Unit) {
        if (hasPermission) scan() else permissionLauncher.launch(permission)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        SubHeader("Local Audio", onBack = onBack) {
            if (tracks.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = {
                        // Stage 8b — локальное аудио играем полностью через JUCE.
                        PlayerController.playLocalOnJuce(
                            context = context,
                            tracks = tracks.shuffled(),
                            startIndex = 0
                        )
                    }) {
                        Icon(Icons.Default.Shuffle, null, tint = Color(0xFFFF9F0A), modifier = Modifier.size(22.dp))
                    }
                }
            }
        }

        when {
            !hasPermission -> {
                Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Rounded.MusicNote, null, tint = lc.iconMuted, modifier = Modifier.size(64.dp))
                        Spacer(Modifier.height(16.dp))
                        Text("Allow access to your music", color = lc.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Grant permission to scan and play audio stored on this device.",
                            color = lc.textSecondary,
                            fontSize = 14.sp,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            lineHeight = 20.sp
                        )
                        Spacer(Modifier.height(16.dp))
                        ActionButton("Grant Permission", Icons.Default.PlayArrow, onClick = { permissionLauncher.launch(permission) })
                    }
                }
            }
            loading -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color(0xFFFF9F0A))
                }
            }
            tracks.isEmpty() -> {
                EmptyState("No local audio found", Icons.Rounded.MusicNote)
            }
            else -> {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 120.dp)
                ) {
                    items(tracks, key = { it.id }) { track ->
                        LocalTrackRow(
                            track = track,
                            onClick = {
                                val startIndex = tracks.indexOfFirst { it.id == track.id }
                                if (startIndex >= 0) {
                                    // Stage 8b — локальное аудио играем полностью через JUCE.
                                    PlayerController.playLocalOnJuce(
                                        context = context,
                                        tracks = tracks,
                                        startIndex = startIndex
                                    )
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LocalTrackRow(
    track: Track,
    onClick: () -> Unit
) {
    val lc = LiquidTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .liquidClickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(lc.glassTint),
            contentAlignment = Alignment.Center
        ) {
            // Иконка-заглушка снизу; обложка (если есть) рисуется поверх.
            Icon(Icons.Rounded.MusicNote, null, tint = lc.iconMuted, modifier = Modifier.size(22.dp))
            AsyncImage(
                model = track.albumArtUri,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(track.title, color = lc.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(track.artist, color = lc.textSecondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun SubHeader(
    title: String,
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {}
) {
    val lc = LiquidTheme.colors
    Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(lc.glassTint, CircleShape)
                .clip(CircleShape)
                .liquidClickable(pressedScale = LiquidMotion.PressIcon, onClick = onBack),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, null, tint = lc.textPrimary, modifier = Modifier.size(20.dp))
        }

        Spacer(Modifier.width(16.dp))

        Text(
            text = title,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = lc.textPrimary,
            modifier = Modifier.weight(1f)
        )

        Row(content = actions)
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun SectionHeader(title: String, count: Int) {
    val lc = LiquidTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, color = lc.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .background(lc.glassTint, RoundedCornerShape(10.dp))
                .padding(horizontal = 8.dp, vertical = 2.dp)
        ) {
            Text("$count", color = lc.textSecondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun PlaceholderCard(text: String) {
    val lc = LiquidTheme.colors
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Transparent, RoundedCornerShape(12.dp))
            .padding(vertical = 20.dp, horizontal = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = lc.textTertiary, fontSize = 14.sp)
    }
}

@Composable
private fun ImportedPlaylistRow(
    playlist: com.liquidmusicglass.api.icm.IcmUserPlaylist,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    val lc = LiquidTheme.colors
    val shape = RoundedCornerShape(28.dp)   // эталон радиуса — карточки настроек

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(lc.cardSurface)   // серая подложка как в настройках
            .liquidClickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Cover
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(8.dp))
        ) {
            if (!playlist.cover.isNullOrBlank()) {
                AsyncImage(
                    model = playlist.cover.replace("1000x1000", "200x200"),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(lc.glassTint),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.AutoMirrored.Rounded.PlaylistPlay,
                        null,
                        tint = lc.iconMuted,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
        }

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                playlist.name.orEmpty(),
                color = lc.textPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                "${playlist.trackCount} tracks",
                color = lc.textSecondary,
                fontSize = 13.sp
            )
        }

        IconButton(onClick = onDelete) {
            Icon(Icons.Rounded.Close, null, tint = lc.textTertiary, modifier = Modifier.size(22.dp))
        }
    }
}

/**
 * Row component for local playlists from PlaylistManager.
 */
@Composable
private fun LocalPlaylistRow(
    playlist: com.liquidmusicglass.engine.PlaylistManager.Playlist,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    val lc = LiquidTheme.colors
    val shape = RoundedCornerShape(28.dp)   // эталон радиуса — карточки настроек

    // Get cover from first track if available
    val firstTrackWithCover = playlist.tracks.firstOrNull { !it.coverUrl.isNullOrBlank() }
    val coverUrl = firstTrackWithCover?.coverUrl

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(lc.cardSurface)   // серая подложка как в настройках
            .liquidClickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Cover / Icon
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center
        ) {
            if (!coverUrl.isNullOrBlank()) {
                AsyncImage(
                    model = coverUrl.replace("1000x1000", "200x200"),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(lc.glassTint),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.AutoMirrored.Rounded.PlaylistPlay,
                        null,
                        tint = lc.iconMuted,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
        }

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                playlist.name,
                color = lc.textPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                "${playlist.tracks.size} tracks",
                color = lc.textSecondary,
                fontSize = 13.sp
            )
        }

        IconButton(onClick = onDelete) {
            Icon(Icons.Rounded.Close, null, tint = lc.textTertiary, modifier = Modifier.size(22.dp))
        }
    }
}

// ═════════════════════════════════════════════════════════════════
//  Import Dialog
// ═════════════════════════════════════════════════════════════════

@Composable
private fun ImportPlaylistDialog(
    onDismiss: () -> Unit,
    onImportSuccess: () -> Unit = {}
) {
    val lc = LiquidTheme.colors
    val scope = rememberCoroutineScope()
    var source by remember { mutableStateOf("yandex") } // "yandex" or "apple"
    var url by remember { mutableStateOf("") }
    var playlistName by remember { mutableStateOf("") }

    var isPreviewing by remember { mutableStateOf(false) }
    var previewResult by remember { mutableStateOf<com.liquidmusicglass.api.icm.IcmPlaylistPreviewResponse?>(null) }
    var previewError by remember { mutableStateOf<String?>(null) }

    var isImporting by remember { mutableStateOf(false) }
    var importJobId by remember { mutableStateOf<String?>(null) }
    var importPollAfter by remember { mutableStateOf(3) } // seconds, from API response
    var importProgress by remember { mutableStateOf<com.liquidmusicglass.api.icm.IcmImportJobProgress?>(null) }
    var importError by remember { mutableStateOf<String?>(null) }
    var importErrorDetails by remember { mutableStateOf<String?>(null) }
    var importCompleted by remember { mutableStateOf(false) }
    var importFailedTracks by remember { mutableStateOf<List<com.liquidmusicglass.api.icm.IcmFailedTrack>>(emptyList()) }

    // URL validation state
    var urlValidationError by remember { mutableStateOf<String?>(null) }

    // Auto-detect source based on URL domain + validate
    LaunchedEffect(url) {
        val lower = url.lowercase()
        if (lower.contains("yandex.ru") || lower.contains("music.yandex")) {
            source = "yandex"
            // Check for unsupported lk. playlist format
            urlValidationError = if (lower.contains("/playlists/lk.")) {
                "Yandex changed playlist links. 'lk.' links are not supported yet. Try sharing from Yandex Music app and use the old-style link (music.yandex.ru/users/.../playlists/...) if available."
            } else {
                null
            }
        } else if (lower.contains("apple.com") || lower.contains("music.apple")) {
            source = "apple"
            urlValidationError = null
        } else {
            urlValidationError = null
        }
    }

    // Polling logic for async Yandex import — with retry on transient errors
    LaunchedEffect(importJobId) {
        importJobId?.let { jobId ->
            var consecutiveErrors = 0
            val maxConsecutiveErrors = 8  // increased from 3 for transient 404s
            var pollDelaySec = importPollAfter.coerceAtLeast(3)
            while (!importCompleted && importError == null) {
                delay(pollDelaySec * 1000L)
                pollDelaySec = (pollDelaySec + 2).coerceAtMost(10) // exponential backoff up to 10s
                var statusResponse: com.liquidmusicglass.api.icm.IcmPlaylistImportJobResponse? = null
                var lastPollError: String? = null
                var lastPollHttpCode: Int? = null
                // Retry loop for single poll request (up to 3 attempts)
                repeat(3) { attempt ->
                    if (attempt > 0) delay(2000L) // wait 2s between retries
                    val result = IcmRepository.getImportJobStatus(jobId)
                    if (result != null) {
                        statusResponse = result
                        return@repeat
                    } else {
                        val errCode = IcmRepository.getLastErrorCode()
                        lastPollError = errCode
                        lastPollHttpCode = IcmRepository.getLastHttpCode()
                    }
                }
                if (statusResponse != null) {
                    consecutiveErrors = 0 // reset on success
                    pollDelaySec = importPollAfter.coerceAtLeast(3) // reset delay
                    when (statusResponse.status) {
                        "pending" -> {
                            importProgress = statusResponse.progress
                        }
                        "ready" -> {
                            importProgress = statusResponse.progress ?: com.liquidmusicglass.api.icm.IcmImportJobProgress(
                                total = statusResponse.total,
                                matched = statusResponse.matched,
                                failed = statusResponse.failed
                            )
                            importFailedTracks = statusResponse.failedTracks ?: emptyList()
                            importCompleted = true
                            isImporting = false
                            onImportSuccess() // refresh playlist list
                        }
                        "failed" -> {
                            importError = statusResponse.message ?: "Matching process failed on ICM servers."
                            isImporting = false
                        }
                        else -> {
                            // Unknown status — treat as error after max retries
                            consecutiveErrors++
                            if (consecutiveErrors >= maxConsecutiveErrors) {
                                importError = "Unknown job status: ${statusResponse.status}"
                                isImporting = false
                            }
                        }
                    }
                } else {
                    consecutiveErrors++
                    // Treat 404 playlist_not_found as transient for the first few attempts
                    // ICM may need more time to create the job
                    // По доке транзиентен для полла только job_not_found_or_expired
                    // (гонка создания джобы); playlist_not_found — терминальная
                    // ошибка ДРУГИХ эндпоинтов, ретраить её здесь нечего.
                    val isTransient404 = lastPollHttpCode == 404 &&
                        lastPollError == "job_not_found_or_expired"
                    if (consecutiveErrors >= maxConsecutiveErrors && !isTransient404) {
                        importError = when (lastPollError) {
                            "job_not_found_or_expired" -> "Import session expired. Please try importing again."
                            "job_belongs_to_another_partner" -> "Import session mismatch. Please try again."
                            "playlist_not_found" -> "Playlist not found on source platform. Check the URL and try again."
                            else -> "Failed to poll matching status after retries."
                        }
                        isImporting = false
                    } else if (consecutiveErrors >= maxConsecutiveErrors) {
                        // Even transient errors eventually time out
                        importError = "Import is taking too long. The playlist may have been created — check your library."
                        isImporting = false
                        onImportSuccess() // refresh anyway, maybe playlist was created
                    }
                    // else: keep polling
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(lc.glassTint.copy(alpha = 0.85f))
            .clickable(remember { MutableInteractionSource() }, null) {
                if (!isImporting) onDismiss()
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .background(lc.cardSurface, RoundedCornerShape(24.dp))
                .clip(RoundedCornerShape(24.dp))
                .clickable(remember { MutableInteractionSource() }, null) {} // prevent dismissing click inside
                .padding(24.dp)
        ) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Import Playlist", color = lc.textPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    if (!isImporting) {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Rounded.Close, null, tint = lc.iconMuted)
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))

                if (isImporting || importCompleted || importError != null) {
                    // Match Tracking / Result View
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        if (isImporting && !importCompleted && importError == null) {
                            CircularProgressIndicator(
                                color = AppleRed,
                                modifier = Modifier.size(48.dp)
                            )
                            Spacer(Modifier.height(16.dp))
                            Text("Matching & Syncing...", color = lc.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(8.dp))

                            val prog = importProgress
                            if (prog != null) {
                                val totalVal = prog.total ?: 0
                                val matchedVal = prog.matched ?: 0
                                val failedVal = prog.failed ?: 0
                                val percent = if (totalVal > 0) (matchedVal + failedVal).toFloat() / totalVal.toFloat() else 0f
                                LinearProgressIndicator(
                                    progress = { percent },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(6.dp)
                                        .clip(RoundedCornerShape(50)),
                                    color = AppleRed,
                                    trackColor = lc.textPrimary.copy(alpha = 0.15f)
                                )
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    "Tracks: ${matchedVal + failedVal} / $totalVal (Matched: $matchedVal, Failed: $failedVal)",
                                    color = lc.textSecondary,
                                    fontSize = 13.sp
                                )
                            } else {
                                Text("Queueing job on server...", color = lc.textSecondary, fontSize = 13.sp)
                            }
                        }

                        if (importCompleted) {
                            Spacer(Modifier.height(16.dp))
                            Text("Playlist successfully imported!", color = Color.Green, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                            
                            // Show failed tracks if any
                            if (importFailedTracks.isNotEmpty()) {
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    "${importFailedTracks.size} track(s) could not be matched",
                                    color = Color(0xFFFFA500),
                                    fontSize = 13.sp
                                )
                                Spacer(Modifier.height(4.dp))
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = 120.dp)
                                        .verticalScroll(rememberScrollState())
                                ) {
                                    importFailedTracks.take(5).forEach { failed ->
                                        Text(
                                            "• ${failed.yandexTitle ?: "Unknown"} — ${failed.reason ?: "not found"}",
                                            color = lc.textSecondary,
                                            fontSize = 11.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    if (importFailedTracks.size > 5) {
                                        Text(
                                            "... and ${importFailedTracks.size - 5} more",
                                            color = lc.textTertiary,
                                            fontSize = 11.sp
                                        )
                                    }
                                }
                            }
                            
                            Spacer(Modifier.height(16.dp))
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(44.dp)
                                    .background(AppleRed, RoundedCornerShape(50))
                                    .clip(RoundedCornerShape(50))
                                    .clickable { onDismiss() },
                                contentAlignment = Alignment.Center
                            ) {
                                Text("Done", color = Color.White, fontWeight = FontWeight.Bold)
                            }
                        }

                        importError?.let { err ->
                            Spacer(Modifier.height(16.dp))
                            Text(err, color = Color.Red, fontSize = 14.sp)
                            importErrorDetails?.let { details ->
                                Spacer(Modifier.height(4.dp))
                                Text(details, color = lc.textSecondary, fontSize = 11.sp)
                            }
                            Spacer(Modifier.height(8.dp))
                            // Copy logs button
                            val clipboard = LocalClipboardManager.current
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(36.dp)
                                    .background(lc.glassTint, RoundedCornerShape(50))
                                    .clip(RoundedCornerShape(50))
                                    .clickable {
                                        val logs = com.liquidmusicglass.api.icm.IcmApiFileLogger.getRecentLogs(100)
                                        clipboard.setText(androidx.compose.ui.text.AnnotatedString(logs))
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Text("Copy ICM logs", color = Color(0xFF0A84FF), fontSize = 13.sp)
                            }
                            Spacer(Modifier.height(16.dp))
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(44.dp)
                                    .background(lc.textPrimary.copy(alpha = 0.15f), RoundedCornerShape(50))
                                    .clip(RoundedCornerShape(50))
                                    .clickable { onDismiss() },
                                contentAlignment = Alignment.Center
                            ) {
                                Text("Close", color = lc.textPrimary)
                            }
                        }
                    }
                } else {
                    // Regular Form View
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        // 1. Platform selector
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(lc.glassTint, RoundedCornerShape(50))
                                .padding(4.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            TabChoice("Yandex Music", active = source == "yandex", modifier = Modifier.weight(1f)) { source = "yandex" }
                            TabChoice("Apple Music", active = source == "apple", modifier = Modifier.weight(1f)) { source = "apple" }
                        }

                        // 2. Link Input
                        BasicTextField(
                            value = url,
                            onValueChange = { url = it },
                            textStyle = TextStyle(color = lc.textPrimary, fontSize = 15.sp),
                            cursorBrush = SolidColor(AppleRed),
                            singleLine = true,
                            decorationBox = { inner ->
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(lc.glassTint, RoundedCornerShape(12.dp))
                                        .padding(horizontal = 16.dp, vertical = 14.dp)
                                ) {
                                    if (url.isEmpty()) {
                                        Text("Paste playlist link here...", color = lc.textTertiary, fontSize = 15.sp)
                                    }
                                    inner()
                                }
                            }
                        )

                        // URL validation warning
                        urlValidationError?.let { err ->
                            Text(
                                text = err,
                                color = Color(0xFFFFA500),
                                fontSize = 12.sp,
                                lineHeight = 16.sp,
                                modifier = Modifier.padding(horizontal = 4.dp)
                            )
                        }

                        // 3. Name override (optional)
                        BasicTextField(
                            value = playlistName,
                            onValueChange = { playlistName = it },
                            textStyle = TextStyle(color = lc.textPrimary, fontSize = 15.sp),
                            cursorBrush = SolidColor(AppleRed),
                            singleLine = true,
                            decorationBox = { inner ->
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(lc.glassTint, RoundedCornerShape(12.dp))
                                        .padding(horizontal = 16.dp, vertical = 14.dp)
                                ) {
                                    if (playlistName.isEmpty()) {
                                        Text("Optional custom name...", color = lc.textTertiary, fontSize = 15.sp)
                                    }
                                    inner()
                                }
                            }
                        )

                        // 4. Preview section
                        if (isPreviewing) {
                            Box(modifier = Modifier.fillMaxWidth().height(100.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(color = AppleRed, modifier = Modifier.size(24.dp))
                            }
                        }

                        previewError?.let { err ->
                            Text(err, color = Color.Red, fontSize = 13.sp)
                        }

                        previewResult?.let { res ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color.Transparent, RoundedCornerShape(12.dp))
                                    .padding(12.dp)
                            ) {
                                Text("Preview: ${res.name}", color = lc.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                Text("${res.tracks.size} tracks found", color = lc.textSecondary, fontSize = 12.sp)

                                Spacer(Modifier.height(8.dp))
                                Box(modifier = Modifier.heightIn(max = 120.dp)) {
                                    LazyColumn {
                                        items(res.tracks.take(5)) { tr ->
                                            Text(
                                                "${tr.title} - ${tr.artist}",
                                                color = lc.textSecondary,
                                                fontSize = 12.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        if (res.tracks.size > 5) {
                                            item {
                                                Text("... and ${res.tracks.size - 5} more", color = lc.textTertiary, fontSize = 11.sp)
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // 5. Actions row
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            // Preview button
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(44.dp)
                                    .background(lc.glassTint, RoundedCornerShape(50))
                                    .clip(RoundedCornerShape(50))
                                    .clickable {
                                        if (url.isNotBlank()) {
                                            scope.launch {
                                                isPreviewing = true
                                                previewError = null
                                                previewResult = null
                                                // Strip query params from URL before sending
                                                val cleanUrl = url.trim().substringBefore("?")
                                                val res = IcmRepository.previewPlaylist(source, cleanUrl)
                                                if (res != null) {
                                                    previewResult = res
                                                } else {
                                                    previewError = "Failed to load playlist preview."
                                                }
                                                isPreviewing = false
                                            }
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Text("Preview", color = lc.textPrimary, fontWeight = FontWeight.SemiBold)
                            }

                            // Import button
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(44.dp)
                                    .background(AppleRed, RoundedCornerShape(50))
                                    .clip(RoundedCornerShape(50))
                                    .clickable {
                                        if (url.isNotBlank()) {
                                            scope.launch {
                                                isImporting = true
                                                importError = null
                                                importErrorDetails = null
                                                importJobId = null
                                                importPollAfter = 3 // reset to default
                                                importCompleted = false
                                                importFailedTracks = emptyList()
                                                // Strip query params from URL before sending
                                                val cleanUrl = url.trim().substringBefore("?")
                                                val res = IcmRepository.importPlaylist(
                                                    source = source,
                                                    url = cleanUrl,
                                                    name = playlistName.trim().takeIf { it.isNotBlank() }
                                                )
                                                    if (res != null) {
                                                        if (res.jobId != null || res.playlistId != null) {
                                                            if (source == "yandex" && res.jobId != null) {
                                                                importJobId = res.jobId
                                                                res.pollAfter?.let { importPollAfter = it }
                                                                // isImporting stays true while polling
                                                            } else {
                                                                // Apple — синхронный импорт; сюда же попадает
                                                                // Yandex с playlist_id БЕЗ job_id (страховка:
                                                                // иначе спиннер крутился бы вечно — поллить нечего).
                                                                // Apple Music — synchronous import
                                                                importCompleted = true
                                                                isImporting = false
                                                                onImportSuccess() // refresh playlist list
                                                                // Auto-dismiss after short delay so user sees success
                                                                scope.launch {
                                                                    delay(1500)
                                                                    onDismiss()
                                                                }
                                                            }
                                                        } else {
                                                            importError = "Server denied import."
                                                            importErrorDetails = "Response: playlistId=null, jobId=null"
                                                            isImporting = false
                                                        }
                                                    } else {
                                                    val lastErr = IcmRepository.lastError.value
                                                    val httpCode = IcmRepository.getLastHttpCode()
                                                    val errCode = IcmRepository.getLastErrorCode()
                                                    importError = when (errCode) {
                                                        "scope_not_allowed" -> "Playlist import is not available for your API key. Contact ICM support to enable it."
                                                        "subscription_required" -> "ICM subscription required for playlist import."
                                                        "user_not_linked" -> "Link your ICM account in Profile tab first."
                                                        "invalid_url" -> "Invalid playlist URL. Please check the link and try again."
                                                        "invalid_source" -> "Unsupported playlist source. Use Yandex or Apple Music links."
                                                        "too_many_tracks" -> "Playlist is too large (max 500 tracks). Try a smaller playlist."
                                                        "playlist_not_found" -> "Playlist not found. It may be private or deleted."
                                                        "source_api_error" -> "Source service (Yandex/Apple) is temporarily unavailable. Try again later."
                                                        else -> lastErr ?: "Failed to initiate playlist import."
                                                    }
                                                    importErrorDetails = "HTTP ${httpCode ?: "?"} | Error: ${errCode ?: "unknown"}"
                                                    isImporting = false
                                                }
                                            }
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Text("Import", color = Color.White, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TabChoice(
    text: String,
    active: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val lc = LiquidTheme.colors
    Box(
        modifier = modifier
            .height(36.dp)
            .background(if (active) AppleRed else Color.Transparent, RoundedCornerShape(50))
            .clip(RoundedCornerShape(50))
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            color = if (active) Color.White else lc.textSecondary,
            fontSize = 13.sp,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal
        )
    }
}

// ═════════════════════════════════════════════════════════════════
//  Sub-states Views
// ═════════════════════════════════════════════════════════════════

@Composable
private fun ActionButton(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val lc = LiquidTheme.colors
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(lc.glassTint)
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = AppleRed,
                modifier = Modifier.size(20.dp)
            )
            Text(
                text = text,
                color = lc.textPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun FavoriteTrackItem(
    track: FavoriteTrackEntity,
    isLiked: Boolean,
    onClick: () -> Unit,
    onToggleLike: () -> Unit
) {
    val lc = LiquidTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(lc.glassTint)
        ) {
            if (!track.imageUrl.isNullOrBlank()) {
                AsyncImage(
                    model = track.imageUrl.replace("1000x1000", "300x300"),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = track.title,
                color = lc.textPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = track.artistName ?: "Unknown Artist",
                color = lc.textSecondary,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        IconButton(onClick = onToggleLike) {
            Icon(
                imageVector = if (isLiked) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                contentDescription = null,
                tint = if (isLiked) AppleRed else lc.textTertiary,
                modifier = Modifier.size(22.dp)
            )
        }

        if (track.durationMs > 0) {
            Text(
                text = formatDuration(track.durationMs),
                color = lc.textSecondary,
                fontSize = 12.sp,
                modifier = Modifier.padding(start = 4.dp)
            )
        }
    }
}

@Composable
private fun DownloadedTrackItem(
    track: com.liquidmusicglass.data.local.db.DownloadedTrackEntity,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    val lc = LiquidTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(lc.glassTint)
        ) {
            // Prefer local cover path, fallback to remote imageUrl
            val coverToLoad = track.localCoverPath ?: track.imageUrl
            if (!coverToLoad.isNullOrBlank()) {
                AsyncImage(
                    model = coverToLoad,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = track.title,
                color = lc.textPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = track.artistName ?: "Unknown Artist",
                color = lc.textSecondary,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        IconButton(onClick = onDelete) {
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = null,
                tint = lc.textTertiary,
                modifier = Modifier.size(22.dp)
            )
        }

        if (track.durationMs > 0) {
            Text(
                text = formatDuration(track.durationMs),
                color = lc.textSecondary,
                fontSize = 12.sp,
                modifier = Modifier.padding(start = 4.dp)
            )
        }
    }
}

@Composable
private fun EmptyState(message: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    val lc = LiquidTheme.colors
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = lc.textTertiary,
                modifier = Modifier.size(48.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = message,
                color = lc.textSecondary,
                fontSize = 16.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
    }
}

@Composable
private fun PremiumDownloadsPromo(backdrop: LayerBackdrop? = null) {
    val lc = LiquidTheme.colors
    val context = LocalContext.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(lc.glassTint)
                .padding(32.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Download,
                contentDescription = null,
                tint = AppleRed,
                modifier = Modifier.size(64.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Premium Exclusive",
                color = lc.textPrimary,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Offline downloads are strictly a Premium feature under Aggregator requirements. Listen to music without connection.",
                color = lc.textSecondary,
                fontSize = 14.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                lineHeight = 20.sp
            )
            Spacer(modifier = Modifier.height(28.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(AppleRed)
                    .clickable {
                        val intent = android.content.Intent(
                            android.content.Intent.ACTION_VIEW,
                            Uri.parse("https://t.me/byicmbot")
                        )
                        context.startActivity(intent)
                    }
                    .padding(vertical = 14.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Upgrade via Telegram",
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

private fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}

// ═════════════════════════════════════════════════════════════════
//  Cascade Ripple Animation — Playback Transition
// ═════════════════════════════════════════════════════════════════

/**
 * Wraps a track row with the Cascade Ripple playback animation.
 * When [isPlaying] becomes true, triggers:
 * 1. A soft color-tinted ripple from the center of the row
 * 2. A temporary micro-blur on the row container edges
 * 3. A smooth 4dp downward shift when items below are playing
 *
 * @param isPlaying Whether this track is currently the active playback target
 * @param itemIndex The index of this item in the list (for cascade shift calculation)
 * @param playingIndex The index of the currently playing item (-1 if none)
 * @param content The row content composable
 */
@Composable
private fun CascadeTrackRow(
    isPlaying: Boolean,
    itemIndex: Int,
    playingIndex: Int,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    // ── Micro-shift for items below the playing track ──
    val shouldShift = playingIndex >= 0 && itemIndex > playingIndex
    val shiftY by animateDpAsState(
        targetValue = if (shouldShift) 4.dp else 0.dp,
        animationSpec = tween(durationMillis = 400, easing = LinearOutSlowInEasing),
        label = "cascade_shift"
    )

    // ── Subtle blur when this item is the playing target ──
    val blurDp by animateDpAsState(
        targetValue = if (isPlaying) 2.dp else 0.dp,
        animationSpec = tween(durationMillis = 400, easing = LinearOutSlowInEasing),
        label = "cascade_blur"
    )

    // ── Background glow alpha when playing ──
    val glowAlpha by animateFloatAsState(
        targetValue = if (isPlaying) 0.08f else 0f,
        animationSpec = tween(durationMillis = 400, easing = LinearOutSlowInEasing),
        label = "cascade_glow"
    )

    Box(
        modifier = modifier
            .offset(y = shiftY)
            .then(if (blurDp.value > 0f) Modifier.blur(blurDp) else Modifier)
            .background(
                color = if (glowAlpha > 0f) AppleRed.copy(alpha = glowAlpha) else Color.Transparent,
                shape = RoundedCornerShape(12.dp)
            )
    ) {
        content()
    }
}
