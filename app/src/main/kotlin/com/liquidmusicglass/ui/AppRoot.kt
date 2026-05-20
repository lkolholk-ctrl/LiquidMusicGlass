package com.liquidmusicglass.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.liquidmusicglass.engine.AppSettings
import com.liquidmusicglass.engine.AppUpdater
import com.liquidmusicglass.engine.PlayerController
import com.liquidmusicglass.ui.navigation.BottomBar
import com.liquidmusicglass.ui.player.FullPlayer
import com.liquidmusicglass.ui.player.MiniPlayer
import com.liquidmusicglass.ui.screens.HomeScreen
import com.liquidmusicglass.ui.screens.SearchScreen
import com.liquidmusicglass.ui.screens.LibraryScreen
import com.liquidmusicglass.ui.screens.AlbumDetailScreen
import com.liquidmusicglass.ui.screens.ArtistDetailScreen
import com.liquidmusicglass.ui.screens.EqualizerScreen
import com.liquidmusicglass.ui.screens.PlaylistDetailScreen
import com.liquidmusicglass.ui.screens.PlaylistsScreen
import com.liquidmusicglass.ui.screens.SettingsScreen
import com.liquidmusicglass.ui.screens.AuthScreen
import com.liquidmusicglass.ui.screens.ProfileScreen
import com.liquidmusicglass.ui.theme.LiquidTheme
import kotlinx.coroutines.launch

@Composable
fun AppRoot() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Check for updates on launch
    LaunchedEffect(Unit) {
        try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            val versionCode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                packageInfo.longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode
            }
            AppUpdater.checkForUpdate(versionCode)
        } catch (_: Exception) {}
    }

    var selectedIndex by remember { 
        mutableIntStateOf(
            try { AppSettings.lastScreenIndex.value } catch (_: Exception) { 0 }
        ) 
    }
    var settingsOpen by remember { mutableStateOf(false) }

    // Detail screen navigation
    var detailAlbumId by remember { mutableStateOf<String?>(null) }
    var detailArtistId by remember { mutableStateOf<String?>(null) }
    var equalizerOpen by remember { mutableStateOf(false) }
    var playlistsOpen by remember { mutableStateOf(false) }
    var playlistDetailId by remember { mutableStateOf<String?>(null) }
    var authOpen by remember { mutableStateOf(false) }
    var profileOpen by remember { mutableStateOf(false) }

    val currentTrack by PlayerController.currentTrack.collectAsState()
    val isPlaying by PlayerController.isPlaying.collectAsState()
    val currentPositionMs by PlayerController.currentPositionMs.collectAsState()
    val durationMs by PlayerController.durationMs.collectAsState()
    val volume by PlayerController.volume.collectAsState()
    val autoMixEnabled by PlayerController.autoMixEnabled.collectAsState()
    val isMixing by PlayerController.isMixing.collectAsState()

    val trackTitle = currentTrack?.title ?: "No track"
    val artistName = currentTrack?.artist ?: "—"

    val expandProgress = remember { Animatable(0f) }
    var screenHeightPx by remember { mutableStateOf(1f) }

    fun animateExpand() {
        scope.launch {
            expandProgress.animateTo(
                1f,
                spring(dampingRatio = 0.82f, stiffness = 300f)
            )
        }
    }

    fun animateCollapse() {
        scope.launch {
            expandProgress.animateTo(
                0f,
                spring(dampingRatio = 0.88f, stiffness = 400f)
            )
        }
    }

    val miniAlpha = (1f - expandProgress.value * 3f).coerceIn(0f, 1f)

    // ── Apple-style parallax: background scales down when player opens ──
    val bgScale = (1f - expandProgress.value * 0.08f).coerceIn(0.9f, 1f)
    val bgCorner = (expandProgress.value * 24f).coerceAtLeast(0f)
    val bgAlpha = (1f - expandProgress.value * 0.15f).coerceIn(0.8f, 1f)

    val rootBackdrop: LayerBackdrop = rememberLayerBackdrop()

    // Back handler: close detail screens first, then player, then app
    BackHandler(enabled = detailAlbumId != null || detailArtistId != null || equalizerOpen || playlistsOpen || playlistDetailId != null || settingsOpen || authOpen || profileOpen || expandProgress.value > 0.5f) {
        when {
            settingsOpen -> settingsOpen = false
            authOpen -> authOpen = false
            profileOpen -> profileOpen = false
            equalizerOpen -> equalizerOpen = false
            playlistsOpen -> playlistsOpen = false
            playlistDetailId != null -> playlistDetailId = null
            detailAlbumId != null -> detailAlbumId = null
            detailArtistId != null -> detailArtistId = null
            expandProgress.value > 0.5f -> animateCollapse()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black) // visible behind scaled content
            .onGloballyPositioned { screenHeightPx = it.size.height.toFloat() }
    ) {
        // ── Background content with parallax ──
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = bgScale
                    scaleY = bgScale
                    alpha = bgAlpha
                    clip = true
                    shape = RoundedCornerShape(bgCorner.dp)
                }
                .layerBackdrop(rootBackdrop)
        ) {
            // ── Main screens ──
            when (selectedIndex) {
                0 -> HomeScreen(
                    onNavigateToAlbum = { detailAlbumId = it },
                    onNavigateToArtist = { detailArtistId = it },
                    onNavigateToPlaylist = { playlistDetailId = it }
                )
                1 -> SearchScreen(
                    onNavigateToAlbum = { detailAlbumId = it },
                    onNavigateToArtist = { detailArtistId = it }
                )
                2 -> LibraryScreen(
                    onNavigateToAlbum = { detailAlbumId = it },
                    onNavigateToArtist = { detailArtistId = it }
                )
                3 -> ProfileScreen(
                    onOpenSettings = { settingsOpen = true },
                    onLogout = { selectedIndex = 0 },
                    onOpenAuth = { authOpen = true }
                )
                else -> HomeScreen(
                    onNavigateToAlbum = { detailAlbumId = it },
                    onNavigateToArtist = { detailArtistId = it },
                    onNavigateToPlaylist = { playlistDetailId = it }
                )
            }

            // ── Album Detail ──
            AnimatedVisibility(
                visible = detailAlbumId != null,
                enter = slideInHorizontally(
                    initialOffsetX = { it },
                    animationSpec = spring(dampingRatio = 0.9f, stiffness = 300f)
                ) + fadeIn(tween(200)),
                exit = slideOutHorizontally(
                    targetOffsetX = { it },
                    animationSpec = spring(dampingRatio = 0.9f, stiffness = 350f)
                ) + fadeOut(tween(150))
            ) {
                detailAlbumId?.let { albumId ->
                    AlbumDetailScreen(
                        albumId = albumId,
                        onBack = { detailAlbumId = null }
                    )
                }
            }

            // ── Artist Detail ──
            AnimatedVisibility(
                visible = detailArtistId != null,
                enter = slideInHorizontally(
                    initialOffsetX = { it },
                    animationSpec = spring(dampingRatio = 0.9f, stiffness = 300f)
                ) + fadeIn(tween(200)),
                exit = slideOutHorizontally(
                    targetOffsetX = { it },
                    animationSpec = spring(dampingRatio = 0.9f, stiffness = 350f)
                ) + fadeOut(tween(150))
            ) {
                detailArtistId?.let { id ->
                    ArtistDetailScreen(
                        artistId = id,
                        onBack = { detailArtistId = null },
                        onNavigateToAlbum = { albumId ->
                            detailArtistId = null
                            detailAlbumId = albumId
                        },
                        onNavigateToArtist = { nextArtistId ->
                            detailArtistId = nextArtistId
                        }
                    )
                }
            }

            // ── Equalizer ──
            AnimatedVisibility(
                visible = equalizerOpen,
                enter = slideInVertically(
                    initialOffsetY = { it },
                    animationSpec = spring(dampingRatio = 0.88f, stiffness = 300f)
                ) + fadeIn(tween(200)),
                exit = slideOutVertically(
                    targetOffsetY = { it },
                    animationSpec = spring(dampingRatio = 0.92f, stiffness = 400f)
                ) + fadeOut(tween(150))
            ) {
                EqualizerScreen(onBack = { equalizerOpen = false })
            }

            // ── Playlists ──
            AnimatedVisibility(
                visible = playlistsOpen,
                enter = slideInHorizontally(
                    initialOffsetX = { it },
                    animationSpec = spring(dampingRatio = 0.9f, stiffness = 300f)
                ) + fadeIn(tween(200)),
                exit = slideOutHorizontally(
                    targetOffsetX = { it },
                    animationSpec = spring(dampingRatio = 0.9f, stiffness = 350f)
                ) + fadeOut(tween(150))
            ) {
                PlaylistsScreen(
                    onBack = { playlistsOpen = false },
                    onOpenPlaylist = { id ->
                        playlistDetailId = id
                    }
                )
            }

            // ── Playlist Detail ──
            AnimatedVisibility(
                visible = playlistDetailId != null,
                enter = slideInHorizontally(
                    initialOffsetX = { it },
                    animationSpec = spring(dampingRatio = 0.9f, stiffness = 300f)
                ) + fadeIn(tween(200)),
                exit = slideOutHorizontally(
                    targetOffsetX = { it },
                    animationSpec = spring(dampingRatio = 0.9f, stiffness = 350f)
                ) + fadeOut(tween(150))
            ) {
                playlistDetailId?.let { id ->
                    PlaylistDetailScreen(
                        playlistId = id,
                        onBack = { playlistDetailId = null }
                    )
                }
            }
        }

    val barsVisible = detailAlbumId == null && detailArtistId == null && 
                        !equalizerOpen && !playlistsOpen && playlistDetailId == null &&
                        !settingsOpen && !authOpen && !profileOpen &&
                        expandProgress.value < 0.95f

    if (barsVisible) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .graphicsLayer { alpha = miniAlpha }
                    .pointerInput(Unit) {
                        detectVerticalDragGestures(
                            onVerticalDrag = { change, dragAmount ->
                                change.consume()
                                if (screenHeightPx > 0f) {
                                    val delta = -dragAmount / screenHeightPx
                                    scope.launch {
                                        expandProgress.snapTo(
                                            (expandProgress.value + delta).coerceIn(0f, 1f)
                                        )
                                    }
                                }
                            },
                            onDragEnd = {
                                if (expandProgress.value > 0.15f) animateExpand()
                                else animateCollapse()
                            },
                            onDragCancel = { animateCollapse() }
                        )
                    }
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    MiniPlayer(
                        trackTitle = trackTitle,
                        artistName = artistName,
                        isPlaying = isPlaying,
                        albumArtUri = currentTrack?.displayArtUri,
                        coverUrl = currentTrack?.coverUrl,
                        backdrop = rootBackdrop,
                        onExpand = { animateExpand() },
                        onPlayPause = { PlayerController.togglePlayPause(context) },
                        onSkipNext = { PlayerController.skipNext(context) }
                    )

                    BottomBar(
                        selectedIndex = selectedIndex,
                        onItemSelected = { selectedIndex = it; AppSettings.setLastScreen(it) },
                        backdrop = rootBackdrop
                    )
                }

                Spacer(
                    modifier = Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars)
                )
            }
        }

        FullPlayer(
            expandProgress = expandProgress.value,
            trackTitle = trackTitle,
            artistName = artistName,
            artists = currentTrack?.artists ?: emptyList(),
            isPlaying = isPlaying,
            albumArtUri = currentTrack?.displayArtUri,
            coverUrl = currentTrack?.coverUrl,
            audioFileUri = currentTrack?.uri,
            albumId = currentTrack?.albumId ?: -1L,
            currentPositionMs = currentPositionMs,
            durationMs = durationMs,
            volume = volume,
            isMixing = isMixing,
            onClose = { animateCollapse() },
            onDrag = { dragAmountPx ->
                if (screenHeightPx > 0f) {
                    val delta = dragAmountPx / screenHeightPx
                    scope.launch {
                        expandProgress.snapTo(
                            (expandProgress.value - delta).coerceIn(0f, 1f)
                        )
                    }
                }
            },
            onDragEnd = {
                if (expandProgress.value < 0.85f) animateCollapse()
                else animateExpand()
            },
            onPlayPause = { PlayerController.togglePlayPause(context) },
            onSkipNext = { PlayerController.skipNext(context) },
            onSkipPrevious = { PlayerController.skipPrevious(context) },
            onSeek = { PlayerController.seekTo(it) },
            onVolumeChange = { PlayerController.setVolume(it) },
            onOpenSettings = { settingsOpen = true },
            onNavigateToArtist = { artistId ->
                detailArtistId = artistId
            }
        )

        AnimatedVisibility(
            visible = settingsOpen,
            enter = slideInHorizontally(
                initialOffsetX = { it },
                animationSpec = tween(340, easing = FastOutSlowInEasing)
            ) + fadeIn(animationSpec = tween(250)),
            exit = slideOutHorizontally(
                targetOffsetX = { it },
                animationSpec = tween(300, easing = FastOutSlowInEasing)
            ) + fadeOut(animationSpec = tween(200))
        ) {
            SettingsScreen(
                autoMixEnabled = autoMixEnabled,
                onAutoMixChange = { PlayerController.setAutoMix(it) },
                onBack = { settingsOpen = false },
                onOpenEqualizer = { equalizerOpen = true; settingsOpen = false },
                backdrop = rootBackdrop
            )
        }

        // ── Auth Screen ──
        AnimatedVisibility(
            visible = authOpen,
            enter = slideInVertically(
                initialOffsetY = { it },
                animationSpec = spring(dampingRatio = 0.88f, stiffness = 300f)
            ) + fadeIn(tween(200)),
            exit = slideOutVertically(
                targetOffsetY = { it },
                animationSpec = spring(dampingRatio = 0.92f, stiffness = 400f)
            ) + fadeOut(tween(150))
        ) {
            AuthScreen(
                onAuthSuccess = {
                    authOpen = false
                    selectedIndex = 3
                    AppSettings.setLastScreen(3)
                },
                onBack = { authOpen = false }
            )
        }

        // ── Profile Screen ──
        AnimatedVisibility(
            visible = profileOpen,
            enter = slideInHorizontally(
                initialOffsetX = { it },
                animationSpec = spring(dampingRatio = 0.9f, stiffness = 300f)
            ) + fadeIn(tween(200)),
            exit = slideOutHorizontally(
                targetOffsetX = { it },
                animationSpec = spring(dampingRatio = 0.9f, stiffness = 350f)
            ) + fadeOut(tween(150))
        ) {
            ProfileScreen(
                onOpenSettings = { settingsOpen = true },
                onLogout = { profileOpen = false }
            )
        }

        // ── Update Dialog ──
        UpdateDialog(backdrop = rootBackdrop)
    }
}
