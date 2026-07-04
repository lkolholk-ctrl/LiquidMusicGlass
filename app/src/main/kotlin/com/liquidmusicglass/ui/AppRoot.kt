package com.liquidmusicglass.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.drawscope.Stroke
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.liquidmusicglass.engine.AppSettings
import com.liquidmusicglass.engine.AppUpdater
import com.liquidmusicglass.engine.NotificationRouter
import com.liquidmusicglass.engine.PlayerController
import com.liquidmusicglass.ui.navigation.BottomBar
import com.liquidmusicglass.ui.player.FullPlayer
import com.liquidmusicglass.ui.screens.HomeScreen
import com.liquidmusicglass.ui.screens.WaveHomeScreen
import com.liquidmusicglass.ui.screens.SearchScreen
import com.liquidmusicglass.ui.screens.LibraryScreen
import com.liquidmusicglass.ui.screens.AlbumDetailScreen
import com.liquidmusicglass.ui.screens.ArtistDetailScreen
import com.liquidmusicglass.ui.screens.AudioFxScreen
import com.liquidmusicglass.ui.screens.LocalLibraryScreen
import com.liquidmusicglass.ui.screens.LocalArtistDetailScreen
import com.liquidmusicglass.ui.screens.LocalAlbumDetailScreen
import com.liquidmusicglass.ui.screens.NewScreen
import com.liquidmusicglass.ui.screens.PlaylistDetailScreen
import com.liquidmusicglass.ui.screens.SettingsScreen
import com.liquidmusicglass.ui.screens.AuthScreen
import com.liquidmusicglass.ui.screens.ProfileScreen
import com.liquidmusicglass.ui.screens.WaveOnboardingScreen
import com.liquidmusicglass.ui.theme.ForceDarkContent
import com.liquidmusicglass.ui.theme.LiquidTheme
import kotlinx.coroutines.launch

@Composable
fun AppRoot() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

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
    var lrcPublishTrack by remember { mutableStateOf<com.liquidmusicglass.engine.Track?>(null) }
    var tagEditTrack by remember { mutableStateOf<com.liquidmusicglass.engine.Track?>(null) }
    var playlistDetailId by remember { mutableStateOf<String?>(null) }
    var authOpen by remember { mutableStateOf(false) }
    var profileOpen by remember { mutableStateOf(false) }
    // Локальная медиатека (Артисты/Альбомы/Треки + поиск)
    var localLibraryOpen by remember { mutableStateOf(false) }
    var localArtistName by remember { mutableStateOf<String?>(null) }
    var localAlbum by remember { mutableStateOf<Pair<Long, String>?>(null) }

    val currentTrack by PlayerController.currentTrack.collectAsState()
    val isPlaying by PlayerController.isPlaying.collectAsState()
    val currentPositionMs by PlayerController.currentPositionMs.collectAsState()
    val durationMs by PlayerController.durationMs.collectAsState()
    val volume by PlayerController.volume.collectAsState()

    val trackTitle = currentTrack?.title ?: "No track"
    val artistName = currentTrack?.artist ?: "—"

    val expandProgress = remember { Animatable(0f) }
    var screenHeightPx by remember { mutableStateOf(1f) }

    val currentCamp by remember {
        com.liquidmusicglass.camp.FeatureAccessManager.getInstance(context)
            .currentCamp
    }.collectAsState()

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

    LaunchedEffect(Unit) {
        // Collect notification tap events and expand player
        NotificationRouter.openLargePlayer.collect {
            animateExpand()
        }
    }

    LaunchedEffect(Unit) {
        // Check for updates on launch
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

    val miniAlpha = (1f - expandProgress.value * 3f).coerceIn(0f, 1f)

    // ── Apple-style parallax: background scales down when player opens ──
    val bgScale = (1f - expandProgress.value * 0.08f).coerceIn(0.9f, 1f)
    val bgCorner = (expandProgress.value * 24f).coerceAtLeast(0f)
    val bgAlpha = (1f - expandProgress.value * 0.15f).coerceIn(0.8f, 1f)

    val rootBackdrop: LayerBackdrop = rememberLayerBackdrop()

    // Back handler: close detail screens first, then player, then app
    BackHandler(enabled = tagEditTrack != null || lrcPublishTrack != null || localAlbum != null || localArtistName != null || localLibraryOpen || detailAlbumId != null || detailArtistId != null || equalizerOpen || playlistDetailId != null || settingsOpen || authOpen || profileOpen || expandProgress.value > 0.5f) {
        when {
            tagEditTrack != null -> tagEditTrack = null
            lrcPublishTrack != null -> lrcPublishTrack = null
            localAlbum != null -> localAlbum = null
            localArtistName != null -> localArtistName = null
            localLibraryOpen -> localLibraryOpen = false
            settingsOpen -> settingsOpen = false
            authOpen -> authOpen = false
            profileOpen -> profileOpen = false
            equalizerOpen -> equalizerOpen = false
            playlistDetailId != null -> playlistDetailId = null
            detailAlbumId != null -> detailAlbumId = null
            detailArtistId != null -> detailArtistId = null
            expandProgress.value > 0.5f -> animateCollapse()
        }
    }

    val lc = LiquidTheme.colors
    val rootBg = if (lc.isDark) Color.Black else Color(0xFFF5F5F7)

    val isOnboardingCompleted by AppSettings.isOnboardingCompleted.collectAsState()

    if (!isOnboardingCompleted && currentCamp is com.liquidmusicglass.camp.MusicCamp.Icm) {
        WaveOnboardingScreen(
            onComplete = {
                AppSettings.setOnboardingCompleted(true)
            },
            onDismiss = {
                AppSettings.setOnboardingCompleted(true)
            }
        )
    } else {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(rootBg) // visible behind scaled content
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
                .background(lc.settingsBackground)
                .layerBackdrop(rootBackdrop)
        ) {
            val isHomeActive = selectedIndex == 0 &&
                    detailAlbumId == null &&
                    detailArtistId == null &&
                    playlistDetailId == null &&
                    !equalizerOpen &&
                    !settingsOpen &&
                    !authOpen &&
                    !profileOpen &&
                    expandProgress.value < 0.05f &&
                    // При потере фокуса окна (пикер файла/фото, «о приложении», шторка,
                    // сворачивание) замораживаем тяжёлый дым — чтобы наш рендер не душил
                    // аудио-колбэк JUCE именно в момент системного перехода.
                    EffectsLifecycle.hasWindowFocus

            // ── Main screens ──
            when (selectedIndex) {
                0 -> {
                    // Wave всегда тёмный — эффекты завязаны на тёмный фон.
                    ForceDarkContent {
                        WaveHomeScreen(
                            onNavigateToSearch = { selectedIndex = 1 },
                            onOpenPlayer = { animateExpand() },
                            onNavigateToAlbum = { detailAlbumId = it },
                            onNavigateToArtist = { detailArtistId = it },
                            onNavigateToPlaylist = { playlistDetailId = it },
                            onOpenAuth = { authOpen = true },
                            onOpenProfile = { profileOpen = true },
                            animationsActive = isHomeActive
                        )
                    }
                }
                1 -> SearchScreen(
                    onNavigateToAlbum = { detailAlbumId = it },
                    onNavigateToArtist = { detailArtistId = it }
                )
                2 -> LibraryScreen(
                    onNavigateToAlbum = { detailAlbumId = it },
                    onNavigateToArtist = { detailArtistId = it },
                    onOpenPlaylist = { playlistDetailId = it },
                    onOpenLocalLibrary = { localLibraryOpen = true }
                )
                3 -> SettingsScreen(
                    onBack = {},
                    onOpenEqualizer = { equalizerOpen = true },
                    onOpenProfile = { profileOpen = true },
                    showBack = false,
                    backdrop = rootBackdrop
                )
                4 -> NewScreen(
                    onNavigateToAlbum = { detailAlbumId = it },
                    onNavigateToArtist = { detailArtistId = it }
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
                AudioFxScreen(onBack = { equalizerOpen = false })
            }

            // ── Local library: Артисты / Альбомы / Треки + поиск ──
            AnimatedVisibility(
                visible = localLibraryOpen,
                enter = slideInVertically(initialOffsetY = { it }, animationSpec = spring(dampingRatio = 0.88f, stiffness = 300f)) + fadeIn(tween(200)),
                exit = slideOutVertically(targetOffsetY = { it }, animationSpec = spring(dampingRatio = 0.92f, stiffness = 400f)) + fadeOut(tween(150))
            ) {
                LocalLibraryScreen(
                    onBack = { localLibraryOpen = false },
                    onOpenArtist = { localArtistName = it },
                    onOpenAlbum = { id, name -> localAlbum = id to name }
                )
            }

            AnimatedVisibility(
                visible = localArtistName != null,
                enter = slideInHorizontally(initialOffsetX = { it }, animationSpec = spring(dampingRatio = 0.9f, stiffness = 300f)) + fadeIn(tween(200)),
                exit = slideOutHorizontally(targetOffsetX = { it }, animationSpec = spring(dampingRatio = 0.92f, stiffness = 400f)) + fadeOut(tween(150))
            ) {
                localArtistName?.let { name ->
                    LocalArtistDetailScreen(
                        artistName = name,
                        onBack = { localArtistName = null },
                        onOpenAlbum = { id, n -> localAlbum = id to n }
                    )
                }
            }

            AnimatedVisibility(
                visible = localAlbum != null,
                enter = slideInHorizontally(initialOffsetX = { it }, animationSpec = spring(dampingRatio = 0.9f, stiffness = 300f)) + fadeIn(tween(200)),
                exit = slideOutHorizontally(targetOffsetX = { it }, animationSpec = spring(dampingRatio = 0.92f, stiffness = 400f)) + fadeOut(tween(150))
            ) {
                localAlbum?.let { (id, name) ->
                    LocalAlbumDetailScreen(albumId = id, albumName = name, onBack = { localAlbum = null })
                }
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
                        !equalizerOpen && playlistDetailId == null &&
                        !settingsOpen && !authOpen && !profileOpen

    if (barsVisible) {
            val density = androidx.compose.ui.platform.LocalDensity.current
            val bottomBarTranslateY = expandProgress.value * density.run { 160.dp.toPx() }
            val bottomBarAlpha = if (expandProgress.value >= 0.99f) 0f else 1f

            // ── Автоскрытие бара на главной (Wave): 3с бездействия → бар плавно
            // уезжает вниз, фон обложки дотекает до края. Тап по нижней зоне —
            // бар возвращается (и таймер перезапускается). Без жестов. ──
            var waveBarShown by remember { mutableStateOf(true) }
            var waveBarPokes by remember { mutableStateOf(0) }
            LaunchedEffect(selectedIndex) { waveBarShown = true }  // смена вкладки — показать
            LaunchedEffect(selectedIndex, waveBarShown, waveBarPokes) {
                if (selectedIndex == 0 && waveBarShown) {
                    kotlinx.coroutines.delay(3000)
                    waveBarShown = false
                }
            }
            val waveHideFrac by animateFloatAsState(
                targetValue = if (selectedIndex == 0 && !waveBarShown) 1f else 0f,
                animationSpec = tween(350),
                label = "waveBarHide"
            )

            // На вкладке Wave бар подстраивается под дым: красится тёмной базой
            // палитры обложки (darkMuted — тот же цвет, к которому аура гасит дым
            // у нижней кромки), с плавным переливом при смене трека. На остальных
            // вкладках — обычный цвет темы.
            val onWaveTab = selectedIndex == 0
            val albumColorsForBar = com.liquidmusicglass.ui.glass.rememberAlbumColors(
                currentTrack?.displayArtUri, currentTrack?.coverUrl
            )
            val waveBarColor by animateColorAsState(
                targetValue = lerp(albumColorsForBar.darkMuted, Color.Black, 0.35f),
                animationSpec = tween(600),
                label = "waveBarColor"
            )
            val barBackground =
                if (onWaveTab) waveBarColor
                else if (LiquidTheme.colors.isDark) Color(0xFF0D0D0F) else Color(0xFFF2F2F4)

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .graphicsLayer {
                        translationY = bottomBarTranslateY +
                            waveHideFrac * 160.dp.toPx()   // автоскрытие на Wave
                        alpha = bottomBarAlpha
                    }
                    .background(barBackground),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Мини-плеер (стиль ЯМ, плоский — без стекла): только на вкладках,
                // где включают музыку. На Wave-главной не нужен — экран сам плеер.
                val miniTrack = currentTrack
                if (selectedIndex != 0 && miniTrack != null) {
                    val miniLibraryRepo = remember {
                        com.liquidmusicglass.data.local.db.LibraryRepository.getInstance(context)
                    }
                    val miniLiked by miniLibraryRepo.isFavoriteFlow(miniTrack.id)
                        .collectAsState(initial = false)
                    Spacer(Modifier.height(6.dp))
                    com.liquidmusicglass.ui.player.MiniPlayer(
                        trackTitle = trackTitle,
                        artistName = artistName,
                        isPlaying = isPlaying,
                        albumArtUri = miniTrack.displayArtUri,
                        coverUrl = miniTrack.coverUrl,
                        tint = albumColorsForBar.darkMuted,
                        isLiked = miniLiked,
                        onToggleLike = { scope.launch { miniLibraryRepo.toggleFavorite(miniTrack) } },
                        onExpand = { animateExpand() },
                        onPlayPause = { PlayerController.togglePlayPause(context) },
                        onSkipNext = { PlayerController.skipNext(context) },
                        onSkipPrevious = { PlayerController.skipPrevious(context) }
                    )
                    Spacer(Modifier.height(6.dp))
                }
                // Wave-контент всегда тёмный → на дымном фоне иконки бара тоже
                // должны быть «тёмной темы» (белые), даже если тема приложения светлая.
                val barContent: @Composable () -> Unit = {
                    BottomBar(
                        selectedIndex = selectedIndex,
                        onItemSelected = {
                            com.liquidmusicglass.debug.DebugLog.add("TAB -> $it")
                            waveBarPokes++                 // взаимодействие — перезапуск таймера
                            selectedIndex = it; AppSettings.setLastScreen(it)
                        }
                    )
                }
                if (onWaveTab) ForceDarkContent { barContent() } else barContent()

                Spacer(
                    modifier = Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars)
                )
            }

            // Невидимая тап-зона внизу: пока бар скрыт, тап возвращает его
            // (и НЕ проваливается в контент под ним). Только Wave + плеер свёрнут.
            if (onWaveTab && !waveBarShown && expandProgress.value < 0.1f) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(64.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { waveBarShown = true }
                )
            }
        }

        // Фулл-плеер всегда тёмный — эффекты/палитра рассчитаны на тёмный фон.
        ForceDarkContent {
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
                animateCollapse()   // плеер рисуется ПОВЕРХ деталей — сворачиваем
            },
            onPublishLyrics = { track -> lrcPublishTrack = track },
            onEditTags = { track -> tagEditTrack = track }
        )
        }

        // ── Публикация текста в LRCLIB (открывается из меню трека в плеере) ──
        AnimatedVisibility(
            visible = lrcPublishTrack != null,
            enter = slideInVertically(
                initialOffsetY = { it },
                animationSpec = spring(dampingRatio = 0.88f, stiffness = 300f)
            ) + fadeIn(tween(200)),
            exit = slideOutVertically(
                targetOffsetY = { it },
                animationSpec = spring(dampingRatio = 0.92f, stiffness = 400f)
            ) + fadeOut(tween(150))
        ) {
            lrcPublishTrack?.let { track ->
                com.liquidmusicglass.ui.screens.LrcPublishScreen(
                    track = track,
                    onBack = { lrcPublishTrack = null }
                )
            }
        }

        // ── Редактирование тегов (открывается из меню трека в плеере) ──
        AnimatedVisibility(
            visible = tagEditTrack != null,
            enter = slideInVertically(
                initialOffsetY = { it },
                animationSpec = spring(dampingRatio = 0.88f, stiffness = 300f)
            ) + fadeIn(tween(200)),
            exit = slideOutVertically(
                targetOffsetY = { it },
                animationSpec = spring(dampingRatio = 0.92f, stiffness = 400f)
            ) + fadeOut(tween(150))
        ) {
            tagEditTrack?.let { track ->
                com.liquidmusicglass.ui.screens.TagEditScreen(
                    track = track,
                    onBack = { tagEditTrack = null }
                )
            }
        }

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
                onBack = { settingsOpen = false },
                onOpenEqualizer = { equalizerOpen = true; settingsOpen = false },
                onOpenProfile = { profileOpen = true; settingsOpen = false },
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
                    // На Wave (профиль теперь иконка слева вверху, не таб).
                    selectedIndex = 0
                    AppSettings.setLastScreen(0)
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
                onOpenSettings = { profileOpen = false; selectedIndex = 3 },
                onLogout = { profileOpen = false },
                onOpenAuth = { authOpen = true }
            )
        }

        // ── Update Dialog ──
        UpdateDialog(backdrop = rootBackdrop)

        // On-screen логгер (диагностика аудио на устройстве друга — MMAP/Oboe).
        com.liquidmusicglass.ui.debug.DebugOverlay()
        }
    }
}
