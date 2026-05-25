package com.liquidmusicglass.ui.screens

import android.content.Context
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.*
import com.liquidmusicglass.api.icm.IcmAuthRepository
import com.liquidmusicglass.api.icm.IcmRepository
import com.liquidmusicglass.data.local.db.FavoriteTrackDatabase
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.liquidmusicglass.ui.glass.GlassKit
import com.liquidmusicglass.engine.AudioDownloadManager
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.liquidmusicglass.data.local.db.FavoriteTrackEntity
import com.liquidmusicglass.data.local.db.LibraryRepository
import com.liquidmusicglass.engine.PlayerController
import com.liquidmusicglass.engine.Track
import com.liquidmusicglass.ui.viewmodel.LibraryViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val AppleRed = Color(0xFFFC3C44)

private enum class LibraryView { MAIN, FAVORITES, DOWNLOADS, IMPORTED }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onNavigateToAlbum: (String) -> Unit = {},
    onNavigateToArtist: (String) -> Unit = {},
    onOpenPlaylist: (String) -> Unit = {},
    backdrop: LayerBackdrop? = null
) {
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
            .background(Color.Black)
    ) {
        when (currentView) {
            LibraryView.MAIN -> {
                Column(modifier = Modifier.fillMaxSize()) {
                    // Header
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 48.dp, bottom = 24.dp, start = 20.dp, end = 20.dp)
                    ) {
                        Text(
                            text = "Playlists",
                            fontSize = 34.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }

                    // Options list
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // 1. Favorites Card
                        MenuCard(
                            title = "Favorites",
                            subtitle = "${favorites.size} tracks",
                            icon = Icons.Default.Favorite,
                            tint = AppleRed,
                            onClick = { currentView = LibraryView.FAVORITES }
                        )

                        // 2. Downloads Card
                        MenuCard(
                            title = "Downloads",
                            subtitle = "${downloadedTracks.size} tracks",
                            icon = Icons.Default.Download,
                            tint = Color(0xFF29B6F6),
                            onClick = { currentView = LibraryView.DOWNLOADS }
                        )

                        // 3. Imported Card
                        MenuCard(
                            title = "Imported",
                            subtitle = if (isLoggedIn) "${importedPlaylists.size} playlists" else "Sign in to sync",
                            icon = Icons.AutoMirrored.Rounded.PlaylistPlay,
                            tint = Color(0xFFAB47BC),
                            onClick = {
                                loadImportedPlaylists()
                                currentView = LibraryView.IMPORTED
                            }
                        )
                    }
                }
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
                                Icon(Icons.Filled.Refresh, null, tint = Color.Gray, modifier = Modifier.size(20.dp))
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
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(bottom = 120.dp)
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
                Column(modifier = Modifier.fillMaxSize()) {
                    SubHeader("Downloads", onBack = { currentView = LibraryView.MAIN })

                    if (!isPremium && downloadedTracks.isEmpty()) {
                        PremiumDownloadsPromo(backdrop = backdrop)
                    } else if (downloadedTracks.isEmpty()) {
                        EmptyState("No downloaded tracks yet", Icons.Default.Download)
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(bottom = 120.dp)
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
                                                    coverUrl = entity.imageUrl
                                                )
                                            }
                                            val startIndex = tracks.indexOfFirst { it.id == trackEntity.trackId }
                                            if (startIndex >= 0) {
                                                PlayerController.playFromList(
                                                    context = context,
                                                    tracks = tracks,
                                                    startIndex = startIndex,
                                                    autoRefillType = "library",
                                                    autoRefillId = "downloads",
                                                    autoRefillName = "Downloads"
                                                )
                                            }
                                        }
                                    },
                                    onDelete = {
                                        AudioDownloadManager.deleteDownloadedTrack(context, trackEntity.trackId)
                                    }
                                )
                            }
                        }
                    }
                }
            }

            LibraryView.IMPORTED -> {
                Column(modifier = Modifier.fillMaxSize()) {
                    SubHeader("Imported Playlists", onBack = { currentView = LibraryView.MAIN }) {
                        if (isLoggedIn) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { loadImportedPlaylists() }) {
                                    Icon(Icons.Filled.Refresh, null, tint = Color.Gray, modifier = Modifier.size(20.dp))
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
                                Icon(Icons.AutoMirrored.Rounded.PlaylistPlay, null, tint = Color.Gray, modifier = Modifier.size(64.dp))
                                Spacer(Modifier.height(16.dp))
                                Text("Sync Playlists", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    "Sign in to your ICM account in the Profile tab to import, view and sync your Yandex Music and Apple Music playlists.",
                                    color = Color.Gray,
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

                        if (importedPlaylists.isEmpty()) {
                            EmptyState("No imported playlists yet.\nTap + to import one!", Icons.AutoMirrored.Rounded.PlaylistPlay)
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 120.dp)
                            ) {
                                // ── Yandex Music Section ──
                                item {
                                    SectionHeader("Yandex Music", yandexPlaylists.size)
                                }
                                if (yandexPlaylists.isEmpty()) {
                                    item {
                                        PlaceholderCard("No Yandex playlists imported yet.")
                                    }
                                } else {
                                    items(yandexPlaylists, key = { it.id ?: 0L }) { playlist ->
                                        ImportedPlaylistRow(
                                            playlist = playlist,
                                            onClick = { onOpenPlaylist((playlist.id ?: 0L).toString()) },
                                            onDelete = {
                                                scope.launch {
                                                    IcmRepository.deleteUserPlaylist(playlist.id ?: 0L)
                                                    loadImportedPlaylists()
                                                }
                                            }
                                        )
                                        Spacer(Modifier.height(8.dp))
                                    }
                                }

                                item { Spacer(Modifier.height(24.dp)) }

                                // ── Apple Music Section ──
                                item {
                                    SectionHeader("Apple Music", applePlaylists.size)
                                }
                                if (applePlaylists.isEmpty()) {
                                    item {
                                        PlaceholderCard("No Apple playlists imported yet.")
                                    }
                                } else {
                                    items(applePlaylists, key = { it.id ?: 0L }) { playlist ->
                                        ImportedPlaylistRow(
                                            playlist = playlist,
                                            onClick = { onOpenPlaylist((playlist.id ?: 0L).toString()) },
                                            onDelete = {
                                                scope.launch {
                                                    IcmRepository.deleteUserPlaylist(playlist.id ?: 0L)
                                                    loadImportedPlaylists()
                                                }
                                            }
                                        )
                                        Spacer(Modifier.height(8.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Import Dialog Overlay
        if (showImportDialog) {
            ImportPlaylistDialog(
                onDismiss = {
                    showImportDialog = false
                    loadImportedPlaylists()
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
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp)
            .background(Color(0xFF1C1C1E), shape)
            .clip(shape)
            .clickable(remember { MutableInteractionSource() }, null, onClick = onClick)
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
            Text(title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text(subtitle, color = Color.Gray, fontSize = 13.sp)
        }

        Icon(Icons.Rounded.ChevronRight, null, tint = Color.DarkGray, modifier = Modifier.size(24.dp))
    }
}

@Composable
private fun SubHeader(
    title: String,
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {}
) {
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
                .background(Color(0xFF1C1C1E), CircleShape)
                .clip(CircleShape)
                .clickable(remember { MutableInteractionSource() }, null, onClick = onBack),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, null, tint = Color.White, modifier = Modifier.size(20.dp))
        }

        Spacer(Modifier.width(16.dp))

        Text(
            text = title,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            modifier = Modifier.weight(1f)
        )

        Row(content = actions)
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun SectionHeader(title: String, count: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .background(Color(0xFF1C1C1E), RoundedCornerShape(10.dp))
                .padding(horizontal = 8.dp, vertical = 2.dp)
        ) {
            Text("$count", color = Color.Gray, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun PlaceholderCard(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF0F0F10), RoundedCornerShape(12.dp))
            .padding(vertical = 20.dp, horizontal = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = Color.DarkGray, fontSize = 14.sp)
    }
}

@Composable
private fun ImportedPlaylistRow(
    playlist: com.liquidmusicglass.api.icm.IcmUserPlaylist,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    val shape = RoundedCornerShape(12.dp)
    var showDeleteConfirm by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF1C1C1E), shape)
            .clip(shape)
            .clickable(remember { MutableInteractionSource() }, null, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Cover
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.DarkGray)
        ) {
            if (!playlist.cover.isNullOrBlank()) {
                AsyncImage(
                    model = playlist.cover.replace("1000x1000", "200x200"),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Icon(
                    Icons.AutoMirrored.Rounded.PlaylistPlay,
                    null,
                    tint = AppleRed,
                    modifier = Modifier
                        .size(24.dp)
                        .align(Alignment.Center)
                )
            }
        }

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                playlist.name.orEmpty(),
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                "${playlist.trackCount} tracks",
                color = Color.Gray,
                fontSize = 13.sp
            )
        }

        IconButton(onClick = { showDeleteConfirm = true }) {
            Icon(Icons.Filled.Delete, null, tint = Color.DarkGray, modifier = Modifier.size(20.dp))
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete Playlist", color = Color.White, fontWeight = FontWeight.Bold) },
            text = { Text("Are you sure you want to delete '${playlist.name}' from your ICM library?", color = Color.Gray) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirm = false
                        onDelete()
                    }
                ) {
                    Text("Delete", color = AppleRed, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("Cancel", color = Color.Gray)
                }
            },
            containerColor = Color(0xFF1C1C1E)
        )
    }
}

// ═════════════════════════════════════════════════════════════════
//  Import Dialog
// ═════════════════════════════════════════════════════════════════

@Composable
private fun ImportPlaylistDialog(
    onDismiss: () -> Unit
) {
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

    // Auto-detect source based on URL domain
    LaunchedEffect(url) {
        val lower = url.lowercase()
        if (lower.contains("yandex.ru") || lower.contains("music.yandex")) {
            source = "yandex"
        } else if (lower.contains("apple.com") || lower.contains("music.apple")) {
            source = "apple"
        }
    }

    // Polling logic for async Yandex import
    LaunchedEffect(importJobId) {
        importJobId?.let { jobId ->
            while (!importCompleted && importError == null) {
                delay(importPollAfter * 1000L)
                val statusResponse = IcmRepository.getImportJobStatus(jobId)
                if (statusResponse != null) {
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
                        }
                        "failed" -> {
                            importError = statusResponse.message ?: "Matching process failed on ICM servers."
                            isImporting = false
                        }
                    }
                } else {
                    // Check specific error codes from repository
                    val errCode = IcmRepository.getLastErrorCode()
                    importError = when (errCode) {
                        "job_not_found_or_expired" -> "Import session expired. Please try importing again."
                        "job_belongs_to_another_partner" -> "Import session mismatch. Please try again."
                        else -> "Failed to poll matching status."
                    }
                    isImporting = false
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.85f))
            .clickable(remember { MutableInteractionSource() }, null) {
                if (!isImporting) onDismiss()
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .background(Color(0xFF1C1C1E), RoundedCornerShape(24.dp))
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
                    Text("Import Playlist", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    if (!isImporting) {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Rounded.Close, null, tint = Color.Gray)
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
                            Text("Matching & Syncing...", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
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
                                    trackColor = Color.DarkGray
                                )
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    "Tracks: ${matchedVal + failedVal} / $totalVal (Matched: $matchedVal, Failed: $failedVal)",
                                    color = Color.Gray,
                                    fontSize = 13.sp
                                )
                            } else {
                                Text("Queueing job on server...", color = Color.Gray, fontSize = 13.sp)
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
                                            color = Color.Gray,
                                            fontSize = 11.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    if (importFailedTracks.size > 5) {
                                        Text(
                                            "... and ${importFailedTracks.size - 5} more",
                                            color = Color.Gray,
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
                                Text(details, color = Color.Gray, fontSize = 11.sp)
                            }
                            Spacer(Modifier.height(16.dp))
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(44.dp)
                                    .background(Color.DarkGray, RoundedCornerShape(50))
                                    .clip(RoundedCornerShape(50))
                                    .clickable { onDismiss() },
                                contentAlignment = Alignment.Center
                            ) {
                                Text("Close", color = Color.White)
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
                                .background(Color(0xFF2C2C2E), RoundedCornerShape(50))
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
                            textStyle = TextStyle(color = Color.White, fontSize = 15.sp),
                            cursorBrush = SolidColor(AppleRed),
                            singleLine = true,
                            decorationBox = { inner ->
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(Color(0xFF2C2C2E), RoundedCornerShape(12.dp))
                                        .padding(horizontal = 16.dp, vertical = 14.dp)
                                ) {
                                    if (url.isEmpty()) {
                                        Text("Paste playlist link here...", color = Color.DarkGray, fontSize = 15.sp)
                                    }
                                    inner()
                                }
                            }
                        )

                        // 3. Name override (optional)
                        BasicTextField(
                            value = playlistName,
                            onValueChange = { playlistName = it },
                            textStyle = TextStyle(color = Color.White, fontSize = 15.sp),
                            cursorBrush = SolidColor(AppleRed),
                            singleLine = true,
                            decorationBox = { inner ->
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(Color(0xFF2C2C2E), RoundedCornerShape(12.dp))
                                        .padding(horizontal = 16.dp, vertical = 14.dp)
                                ) {
                                    if (playlistName.isEmpty()) {
                                        Text("Optional custom name...", color = Color.DarkGray, fontSize = 15.sp)
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
                                    .background(Color(0xFF0F0F10), RoundedCornerShape(12.dp))
                                    .padding(12.dp)
                            ) {
                                Text("Preview: ${res.name}", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                Text("${res.tracks.size} tracks found", color = Color.Gray, fontSize = 12.sp)

                                Spacer(Modifier.height(8.dp))
                                Box(modifier = Modifier.heightIn(max = 120.dp)) {
                                    LazyColumn {
                                        items(res.tracks.take(5)) { tr ->
                                            Text(
                                                "${tr.title} - ${tr.artist}",
                                                color = Color.Gray,
                                                fontSize = 12.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        if (res.tracks.size > 5) {
                                            item {
                                                Text("... and ${res.tracks.size - 5} more", color = Color.DarkGray, fontSize = 11.sp)
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
                                    .background(Color(0xFF2C2C2E), RoundedCornerShape(50))
                                    .clip(RoundedCornerShape(50))
                                    .clickable {
                                        if (url.isNotBlank()) {
                                            scope.launch {
                                                isPreviewing = true
                                                previewError = null
                                                previewResult = null
                                                val res = IcmRepository.previewPlaylist(source, url.trim())
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
                                Text("Preview", color = Color.White, fontWeight = FontWeight.SemiBold)
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
                                                val res = IcmRepository.importPlaylist(
                                                    source = source,
                                                    url = url.trim(),
                                                    name = playlistName.trim().takeIf { it.isNotBlank() }
                                                )
                                                if (res != null) {
                                                    if (res.jobId != null || res.playlistId != null) {
                                                        if (source == "yandex") {
                                                            importJobId = res.jobId
                                                            res.pollAfter?.let { importPollAfter = it }
                                                            // isImporting stays true while polling
                                                        } else {
                                                            // Apple Music — synchronous import
                                                            importCompleted = true
                                                            isImporting = false
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
            color = if (active) Color.White else Color.Gray,
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
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF1C1C1E))
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
                color = Color.White,
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
                .background(Color.DarkGray)
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
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = track.artistName ?: "Unknown Artist",
                color = Color.Gray,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        IconButton(onClick = onToggleLike) {
            Icon(
                imageVector = if (isLiked) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                contentDescription = null,
                tint = if (isLiked) AppleRed else Color.Gray,
                modifier = Modifier.size(22.dp)
            )
        }

        if (track.durationMs > 0) {
            Text(
                text = formatDuration(track.durationMs),
                color = Color.Gray,
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
    var showConfirm by remember { mutableStateOf(false) }

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
                .background(Color.DarkGray)
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
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = track.artistName ?: "Unknown Artist",
                color = Color.Gray,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        IconButton(onClick = { showConfirm = true }) {
            Icon(
                imageVector = Icons.Filled.Delete,
                contentDescription = null,
                tint = Color.Gray,
                modifier = Modifier.size(22.dp)
            )
        }

        if (track.durationMs > 0) {
            Text(
                text = formatDuration(track.durationMs),
                color = Color.Gray,
                fontSize = 12.sp,
                modifier = Modifier.padding(start = 4.dp)
            )
        }
    }

    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text("Delete Offline Track", color = Color.White, fontWeight = FontWeight.Bold) },
            text = { Text("Remove '${track.title}' from your device storage?", color = Color.Gray) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showConfirm = false
                        onDelete()
                    }
                ) {
                    Text("Remove", color = AppleRed, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = false }) {
                    Text("Cancel", color = Color.Gray)
                }
            },
            containerColor = Color(0xFF1C1C1E)
        )
    }
}

@Composable
private fun EmptyState(message: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Color.DarkGray,
                modifier = Modifier.size(48.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = message,
                color = Color.Gray,
                fontSize = 16.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
    }
}

@Composable
private fun PremiumDownloadsPromo(backdrop: LayerBackdrop? = null) {
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
                .background(Color(0xFF1C1C1E))
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
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Offline downloads are strictly a Premium feature under Aggregator requirements. Listen to music without connection.",
                color = Color.Gray,
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
