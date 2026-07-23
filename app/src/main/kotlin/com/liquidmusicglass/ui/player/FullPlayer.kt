package com.liquidmusicglass.ui.player

import android.content.Context
import android.media.AudioManager
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ThumbUp
import androidx.compose.material.icons.rounded.ThumbDown
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CropFree
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberModalBottomSheetState
import com.liquidmusicglass.api.icm.IcmMiniArtist
import com.liquidmusicglass.api.icm.IcmAuthRepository
import com.liquidmusicglass.engine.AppSettings
import com.liquidmusicglass.engine.AudioDownloadManager
import com.liquidmusicglass.data.local.db.FavoriteTrackDatabase
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.liquidmusicglass.engine.PlayerController
import com.liquidmusicglass.engine.UiLogger
import com.liquidmusicglass.api.icm.WaveSignalQueue
import com.liquidmusicglass.ui.glass.GlassKit
import com.liquidmusicglass.ui.glass.GlassDialog
import com.liquidmusicglass.ui.glass.GlassDialogButton
import com.liquidmusicglass.ui.glass.AlbumArtImage
import com.liquidmusicglass.ui.glass.pressScale
import com.liquidmusicglass.ui.glass.rememberAlbumColors
import com.liquidmusicglass.ui.liquid.LiquidSlider
import com.liquidmusicglass.ui.lyrics.LyricsScreen
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FullPlayer(
    expandProgress: Float,
    trackTitle: String,
    artistName: String,
    artists: List<IcmMiniArtist> = emptyList(),
    isPlaying: Boolean,
    albumArtUri: Uri?,
    coverUrl: String? = null,
    audioFileUri: Uri? = null,
    albumId: Long = -1L,
    currentPositionMs: Long,
    durationMs: Long,
    volume: Float,
    onClose: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
    onPlayPause: () -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrevious: () -> Unit,
    onSeek: (Long) -> Unit,
    onVolumeChange: (Float) -> Unit,
    onOpenSettings: () -> Unit,
    onNavigateToArtist: (String) -> Unit = {},
    onPublishLyrics: (com.liquidmusicglass.engine.Track) -> Unit = {},
    onEditTags: (com.liquidmusicglass.engine.Track) -> Unit = {}
) {
    if (expandProgress <= 0.005f) return

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val libraryRepo = remember { com.liquidmusicglass.data.local.db.LibraryRepository.getInstance(context) }
    val currentTrackObj by PlayerController.currentTrack.collectAsState()
    val trackId = currentTrackObj?.id ?: ""
    val isFavorite by libraryRepo.isFavoriteFlow(trackId).collectAsState(initial = false)
    val isPremium by IcmAuthRepository.isPremium.collectAsState()
    val db = remember { FavoriteTrackDatabase.getInstance(context) }
    val isDownloaded by db.isDownloadedFlow(trackId).collectAsState(initial = false)
    val downloadProgressMap by AudioDownloadManager.downloadProgress.collectAsState()
    val progress = downloadProgressMap[trackId]
    val isDownloading = progress != null

    var showPromoDialog by remember { mutableStateOf(false) }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }

    // ── Видеоклип: флаг + фуллскрин ──
    val isVideoClip by PlayerController.isVideoClip.collectAsState()
    var clipFullscreen by remember { mutableStateOf(false) }
    // Фуллскрин поворачивает экран в альбом; выход — возвращает авто-ориентацию.
    LaunchedEffect(clipFullscreen) {
        val act = context as? android.app.Activity ?: return@LaunchedEffect
        act.requestedOrientation = if (clipFullscreen)
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }
    // Ушли с клипа (скип на музыку) — фуллскрин закрывается сам.
    LaunchedEffect(isVideoClip) { if (!isVideoClip) clipFullscreen = false }
    // Плеер закрыли/умер, будучи в фуллскрине — вернуть ориентацию.
    DisposableEffect(Unit) {
        onDispose {
            (context as? android.app.Activity)?.requestedOrientation =
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }
    BackHandler(enabled = clipFullscreen) { clipFullscreen = false }

    var showAirPlay by remember { mutableStateOf(false) }
    var showQueue by remember { mutableStateOf(false) }
    var showLyrics by remember { mutableStateOf(false) }
    var showArtistSheet by remember { mutableStateOf(false) }
    var showDebugPanel by remember { mutableStateOf(false) }
    var showTrackMenu by remember { mutableStateOf(false) }
    val artistSheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true
    )
    val trackMenuSheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true
    )
    // Видимость контролов плеера. Когда открыта лирика — скрываются (как в Apple Music).
    // Тап по области лирики временно показывает их снова.
    var controlsVisible by remember { mutableStateOf(true) }
    val playerBackdrop: LayerBackdrop = rememberLayerBackdrop()

    val shuffleEnabled by PlayerController.shuffleEnabled.collectAsState()
    val repeatMode by PlayerController.repeatMode.collectAsState()
    val isBuffering by PlayerController.isBuffering.collectAsState()
    // currentTrackObj is declared above for Room reactive favorite state

    // isFavorite is now reactive from Room DB via LibraryRepository Flow
    // (declared above at composition start)

    // ── Mood/Color from album art ──
    val albumColors = rememberAlbumColors(albumArtUri, coverUrl)

    // Широкое окно (телефон-альбом ИЛИ планшет): обложка слева, контролы/
    // лирика/очередь — справа (side-by-side). Компактный портрет не меняется.
    // Через единый rememberWindowInfo — так же адаптируются все экраны.
    val isLandscape = com.liquidmusicglass.ui.rememberWindowInfo().useSideBySide

    // ── Gesture: horizontal swipe for skip ──
    val swipeOffsetX = remember { Animatable(0f) }
    var swipeTriggered by remember { mutableStateOf(false) }

    val trackProgressState = remember { mutableFloatStateOf(0f) }
    var userDragFraction by remember { mutableStateOf<Float?>(null) }

    // Показываем позицию от плеера ТОЛЬКО когда пользователь не тянет ползунок
    trackProgressState.floatValue = if (userDragFraction != null) {
        userDragFraction!!
    } else if (durationMs > 0) {
        (currentPositionMs.toFloat() / durationMs).coerceIn(0f, 1f)
    } else 0f

    // Debounce seek: выполняем seek через 150мс после последнего изменения
    LaunchedEffect(userDragFraction) {
        val fraction = userDragFraction ?: return@LaunchedEffect
        kotlinx.coroutines.delay(150L)
        if (durationMs > 0) {
            onSeek((fraction * durationMs).toLong())
        }
        userDragFraction = null
    }

    // Авто-скрытие контролов поверх лирики/очереди: через 3 сек после показа прячем обратно
    LaunchedEffect(controlsVisible, showLyrics, showQueue) {
        if ((showLyrics || showQueue) && controlsVisible) {
            kotlinx.coroutines.delay(3000L)
            controlsVisible = false
        }
    }

    val controlsAlpha = ((expandProgress - 0.4f) / 0.6f).coerceIn(0f, 1f)
    val bgAlpha = (expandProgress * 1.5f).coerceIn(0f, 1f)
    val controlsMounted = expandProgress > 0.35f || showLyrics || showQueue ||
        showAirPlay || showDebugPanel || showArtistSheet || showTrackMenu

    // ── Morphing parameters ──
    // Album art: 44dp → fullscreen, corners 10dp → 0dp
    // Controls stagger: each element appears with slight delay
    val titleAlpha = ((expandProgress - 0.25f) / 0.5f).coerceIn(0f, 1f)
    val sliderAlpha = ((expandProgress - 0.35f) / 0.5f).coerceIn(0f, 1f)
    val buttonsAlpha = ((expandProgress - 0.45f) / 0.5f).coerceIn(0f, 1f)
    val bottomAlpha = ((expandProgress - 0.55f) / 0.4f).coerceIn(0f, 1f)
    // Controls slide up offset
    val controlsOffsetY = ((1f - expandProgress) * 80f)

    // ── Card morphing: rounded corners during transition ──
    val cardCorner = ((1f - expandProgress) * 28f).coerceIn(0f, 28f)
    val cardOffsetY = ((1f - expandProgress).coerceAtLeast(0f) * 60f)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                alpha = bgAlpha
                translationY = cardOffsetY
                clip = true
                shape = RoundedCornerShape(cardCorner.dp)
            }
            .background(Color.Black)
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onVerticalDrag = { change, dragAmount ->
                        if (dragAmount > 0) {
                            change.consume()
                            onDrag(dragAmount)
                        }
                    },
                    onDragEnd = { onDragEnd() },
                    onDragCancel = { onDragEnd() }
                )
            }
    ) {
        // ═══ All visual content captured by playerBackdrop for glass sheets ═══
        Box(modifier = Modifier.fillMaxSize().layerBackdrop(playerBackdrop)) {
            // ═══ Apple Music style animated gradient background ═══
            // Клип: фон чисто чёрный (как у Apple при видео) — без градиента.
            if (isVideoClip) {
                Box(modifier = Modifier.fillMaxSize().background(Color.Black))
            } else {
                AnimatedPlayerBackground(
                    albumArtUri = albumArtUri,
                    coverUrl = coverUrl,
                    audioFileUri = audioFileUri,
                    albumId = albumId,
                    albumColors = albumColors,
                    modifier = Modifier.fillMaxSize()
                )
            }

            // ═══ Album art (inside backdrop so glass sees it) ═══
            // При открытой лирике большая обложка скрывается — у лирики своя шапка.
            // В АЛЬБОМЕ обложку НЕ прячем: она остаётся слева (split-режим Apple
            // Music — обложка слева, лирика/очередь справа).
            val artAlpha by animateFloatAsState(
                targetValue = when {
                    // Портрет: лирика прячет большую обложку (у лирики своя шапка).
                    showLyrics && !isLandscape -> 0f
                    // Split (альбом/планшет): обложка и контролы в ОДНОЙ левой
                    // половине. Когда при открытой лирике/очереди всплыли контролы
                    // — обложку прячем, чтобы кнопки/прогресс читались чисто (не
                    // мешались поверх обложки). Контролы ушли — обложка вернулась.
                    isLandscape && (showLyrics || showQueue) && controlsVisible -> 0f
                    else -> 1f
                },
                animationSpec = tween(300),
                label = "artAlpha"
            )
            val artPaddingH = (24f * expandProgress.coerceIn(0f, 1f)).coerceIn(0f, 24f)
            val artCornerR = (16f * expandProgress.coerceIn(0f, 1f)).coerceIn(0f, 16f)
            val artShape = RoundedCornerShape(artCornerR.dp)

            // Обложка «дышит»: на паузе ужимается (как в Apple Music), на плей —
            // упруго распахивается. Буферизацию не считаем паузой — иначе обложка
            // дёргалась бы при каждом скипе.
            val artScale by animateFloatAsState(
                targetValue = if (isPlaying || isBuffering) 1f else 0.86f,
                animationSpec = spring(dampingRatio = 0.72f, stiffness = 220f),
                label = "artBreath"
            )

            Box(
                modifier = Modifier
                    .then(
                        if (isLandscape && isVideoClip)
                            // Клип в сплите: 16:9 ОТ ШИРИНЫ левой половины —
                            // иначе (от высоты) карточка вылезала бы на контролы.
                            Modifier
                                .align(Alignment.CenterStart)
                                .fillMaxWidth(0.46f)
                                .padding(start = 28.dp)
                                .aspectRatio(16f / 9f)
                        else if (isLandscape)
                            // Слева, по высоте, левая половина экрана.
                            Modifier
                                .align(Alignment.CenterStart)
                                .fillMaxHeight(0.82f)
                                .padding(start = 28.dp)
                                .aspectRatio(1f)
                        else
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = artPaddingH.dp)
                                // Клип: карточка 16:9 ниже (центр верхней зоны, как у
                                // Apple), а не прижата к верху — обложка как раньше.
                                .padding(top = ((if (isVideoClip) 190 else 80).dp * expandProgress))
                                .aspectRatio(if (isVideoClip) 16f / 9f else 1f)
                    )
                    .graphicsLayer {
                        translationX = swipeOffsetX.value
                        scaleX = artScale
                        scaleY = artScale
                        shadowElevation = (24f - 10f * (1f - artScale) / 0.14f) *
                            expandProgress.coerceIn(0f, 1f)
                        alpha = artAlpha
                        clip = true
                        shape = artShape
                    }
            ) {
                // Видеоклип (Apple Music): вместо обложки — Surface с видео
                // (mp4 играет основной ExoPlayer, Apple MusicKit Android
                // рекомендует именно Surface). Карточка сама 16:9 (контейнер
                // выше), видео заполняет её целиком; значок-скобки в левом
                // верхнем углу (как у Apple), тап разворачивает на весь экран.
                // Иначе — обычная обложка.
                if (isVideoClip) {
                    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
                        if (!clipFullscreen) {
                            androidx.compose.ui.viewinterop.AndroidView(
                                factory = { ctx ->
                                    android.view.SurfaceView(ctx).also {
                                        PlayerController.attachVideoSurface(it)
                                    }
                                },
                                onRelease = { PlayerController.attachVideoSurface(null) },
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                        // Значок разворота — левый верхний угол, без подложки
                        // (референс Apple); сам тап ловит жестовый слой (onTap).
                        Icon(
                            Icons.Rounded.CropFree, "Fullscreen",
                            tint = Color.White.copy(alpha = 0.9f),
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(12.dp)
                                .size(20.dp)
                        )
                    }
                } else {
                // Кроссфейд обложек: при смене трека (авто или скип) старая
                // растворяется, новая проявляется — вместо мгновенной подмены.
                androidx.compose.animation.Crossfade(
                    targetState = ArtCrossfadeKey(albumArtUri, coverUrl, audioFileUri, albumId),
                    animationSpec = tween(450),
                    label = "artCrossfade"
                ) { art ->
                    AlbumArtImage(
                        uri = art.uri,
                        coverUrl = art.coverUrl,
                        audioFileUri = art.audioFileUri,
                        albumId = art.albumId,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                }
            }
        }

        // ═══ Gesture layer: swipe skip + double-tap like ═══
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(expandProgress) {
                    if (expandProgress < 0.9f) return@pointerInput
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            val threshold = size.width * 0.25f
                            when {
                                swipeOffsetX.value < -threshold && !swipeTriggered -> {
                                    swipeTriggered = true
                                    onSkipNext()
                                }
                                swipeOffsetX.value > threshold && !swipeTriggered -> {
                                    swipeTriggered = true
                                    onSkipPrevious()
                                }
                            }
                            scope.launch {
                                swipeOffsetX.animateTo(0f, spring(dampingRatio = 0.6f, stiffness = 300f))
                                swipeTriggered = false
                            }
                        },
                        onDragCancel = {
                            scope.launch {
                                swipeOffsetX.animateTo(0f, spring(dampingRatio = 0.6f, stiffness = 300f))
                                swipeTriggered = false
                            }
                        },
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            scope.launch {
                                swipeOffsetX.snapTo(
                                    (swipeOffsetX.value + dragAmount).coerceIn(
                                        -size.width * 0.4f,
                                        size.width * 0.4f
                                    )
                                )
                            }
                        }
                    )
                }
                .pointerInput(expandProgress) {
                    if (expandProgress < 0.9f) return@pointerInput
                    detectTapGestures(
                        onDoubleTap = {
                            currentTrackObj?.let { track ->
                                scope.launch {
                                    libraryRepo.toggleFavorite(track)
                                }
                            }
                        },
                        // Клип: одиночный тап по видео/фону → фуллскрин. Жестовый
                        // слой топовый в hit-test, поэтому тап обрабатываем здесь
                        // (клики внутри арт-бокса сюда не доходят); кнопки
                        // контролов лежат ВЫШЕ этого слоя и не задеваются.
                        onTap = {
                            if (isVideoClip && !showLyrics && !showQueue) clipFullscreen = true
                        }
                    )
                }
        )

        // ═══ Lyrics ═══
        // Рисуется ДО контролов — чтобы контролы (по тапу) всплывали поверх лирики.
        AnimatedVisibility(
            visible = showLyrics,
            enter = fadeIn(tween(400, easing = FastOutSlowInEasing)),
            exit = fadeOut(tween(350, easing = FastOutSlowInEasing)),
            // Landscape: лирика в ПРАВОЙ половине (обложка остаётся слева).
            modifier = if (isLandscape)
                Modifier.align(Alignment.CenterEnd).fillMaxWidth(0.5f).fillMaxHeight()
            else Modifier
        ) {
            LyricsScreen(
                audioFileUri = audioFileUri,
                lrcText = null,
                currentPositionMs = currentPositionMs,
                trackTitle = trackTitle,
                trackArtist = artistName,
                trackDurationMs = durationMs,
                albumArtUri = albumArtUri,
                coverUrl = coverUrl,
                albumId = albumId,
                trackId = currentTrackObj?.id,
                albumColors = albumColors,
                onRequestControls = { controlsVisible = true },
                onClose = { showLyrics = false },
                splitMode = isLandscape
            )
        }

        // ═══ Queue ═══
        QueueSheet(
            visible = showQueue,
            onDismiss = { showQueue = false },
            albumArtUri = albumArtUri,
            coverUrl = coverUrl,
            audioFileUri = audioFileUri,
            albumId = albumId,
            albumColors = albumColors,
            currentTrack = currentTrackObj,
            onRequestControls = { controlsVisible = true },
            // Landscape: очередь в ПРАВОЙ половине (обложка/контролы — слева).
            modifier = if (isLandscape)
                Modifier.align(Alignment.CenterEnd).fillMaxWidth(0.5f).fillMaxHeight()
            else Modifier,
            splitMode = isLandscape
        )

        // ═══ Controls ═══
        // Видны всегда, когда лирика и очередь закрыты. Когда открыты — только
        // если controlsVisible (по тапу), и автоматически прячутся через 3 сек.
        AnimatedVisibility(
            visible = controlsMounted && ((!showLyrics && !showQueue) || controlsVisible),
            enter = fadeIn(tween(250)),
            exit = fadeOut(tween(250))
        ) {
        Box(modifier = Modifier.fillMaxSize()) {
        // Подложка под контролами — только когда открыта лирика или очередь.
        // Цвет — из палитры обложки (не чёрный), чтобы совпадал с фоном.
        if (showLyrics || showQueue) {
            // Цвет обложки — светлее (меньше чёрного), непрозрачный книзу.
            // dominant теперь сочный из центрального экстрактора (vivid) — тянем к
            // чёрному чуть меньше (0.3→0.24), чтобы под контролами был цвет, не пятно.
            val scrimColor = lerp(albumColors.dominant, Color.Black, 0.24f)
            Box(
                modifier = Modifier
                    // Landscape: подложка только под ЛЕВОЙ половиной (там контролы);
                    // правую (лирика/очередь) не затемняем.
                    .then(
                        if (isLandscape)
                            Modifier.fillMaxWidth(0.5f).align(Alignment.BottomStart)
                        else
                            Modifier.fillMaxWidth().align(Alignment.BottomCenter)
                    )
                    .height(460.dp)
                    .background(
                        Brush.verticalGradient(
                            0.00f to Color.Transparent,
                            0.32f to scrimColor.copy(alpha = 0.9f),
                            1.00f to scrimColor
                        )
                    )
            )
        }
        Column(
            modifier = Modifier
                .then(
                    // Landscape: контролы в ПРАВОЙ половине (обложка — слева).
                    // Но когда открыта лирика/очередь (они в правой половине) —
                    // контролы уходят ВЛЕВО (поверх обложки), чтобы не налезать
                    // на текст/список (Apple Music split).
                    if (isLandscape)
                        Modifier
                            .align(if (showLyrics || showQueue) Alignment.CenterStart else Alignment.CenterEnd)
                            .fillMaxHeight()
                            .fillMaxWidth(0.5f)
                            .padding(horizontal = 12.dp)
                    else
                        Modifier.fillMaxSize()
                )
                .windowInsetsPadding(WindowInsets.statusBars)
                .windowInsetsPadding(WindowInsets.navigationBars),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Drag Handle — long press opens debug panel
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer { alpha = controlsAlpha }
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = { onClose() },
                            onLongPress = { showDebugPanel = true }
                        )
                    }
                    .padding(top = 8.dp, bottom = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    Modifier
                        .width(36.dp)
                        .height(5.dp)
                        .background(
                            Color.White.copy(alpha = 0.45f),
                            RoundedCornerShape(100.dp)
                        )
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            // ── Main controls — staggered appearance ──
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 36.dp)
                    .graphicsLayer { translationY = controlsOffsetY }
            ) {
                // Track Info — скрыта в режиме лирики и очереди
                AnimatedVisibility(visible = !showLyrics && !showQueue) {
                Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .graphicsLayer { alpha = titleAlpha },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = trackTitle,
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 22.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(4.dp))
                        // Артист кликабелен: один — сразу на его страницу,
                        // несколько (фиты) — шторка выбора (она уже была
                        // построена, но её никто не открывал).
                        Text(
                            text = artistName,
                            color = Color.White.copy(alpha = 0.60f),
                            fontSize = 16.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) {
                                val withId = artists.filter { it.id != null }
                                when {
                                    withId.size > 1 -> showArtistSheet = true
                                    withId.size == 1 -> onNavigateToArtist(withId.first().id!!)
                                    else -> {
                                        // Метаданные без artistId (старый лайк, VK,
                                        // локалка) — резолвим по ИМЕНИ через поиск:
                                        // тап работает везде, где артист существует.
                                        val name = artistName
                                        if (name.isNotBlank() && name != "Unknown Artist" && name != "—") {
                                            scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                                val resolved = try {
                                                    com.liquidmusicglass.api.icm.IcmRepository
                                                        .searchAll(name, limit = 5)?.items
                                                        ?.firstOrNull { it.isArtist }
                                                        ?.let { it.artistId ?: it.id }
                                                } catch (_: Exception) { null }
                                                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                                    if (resolved != null) {
                                                        onNavigateToArtist(resolved)
                                                    } else {
                                                        Toast.makeText(
                                                            context,
                                                            "Artist page unavailable",
                                                            Toast.LENGTH_SHORT
                                                        ).show()
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    com.liquidmusicglass.ui.components.LikeBurstHeart(
                        isLiked = isFavorite,
                        modifier = Modifier.size(44.dp),
                        iconSize = 26.dp,
                        onToggle = {
                            currentTrackObj?.let { track ->
                                scope.launch {
                                    libraryRepo.toggleFavorite(track)
                                }
                            }
                        }
                    )
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .pressScale { showTrackMenu = true },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Rounded.MoreHoriz, null,
                            tint = Color.White.copy(alpha = 0.70f),
                            modifier = Modifier.size(26.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                }
                }

                // Progress (stagger: sliderAlpha)
                Box(
                    Modifier
                        .graphicsLayer { alpha = sliderAlpha }
                        .pointerInput(Unit) {
                        detectVerticalDragGestures(
                            onVerticalDrag = { change, _ -> change.consume() }
                        )
                    }
                ) {
                    LiquidSlider(
                        value = { trackProgressState.floatValue },
                        onValueChange = { fraction ->
                            userDragFraction = fraction
                        },
                        backdrop = playerBackdrop
                    )
                }

                // Time labels
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        formatTime(currentPositionMs),
                        color = Color.White.copy(alpha = 0.50f),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                    // Тап по правому лейблу: «-осталось» ⇄ «всего». Выбор помнится.
                    val showTotal by AppSettings.timeShowTotal.collectAsState()
                    val remaining = (durationMs - currentPositionMs).coerceAtLeast(0)
                    Text(
                        if (showTotal) formatTime(durationMs.coerceAtLeast(0))
                        else "-${formatTime(remaining)}",
                        color = Color.White.copy(alpha = 0.50f),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) { AppSettings.setTimeShowTotal(!showTotal) }
                            .padding(horizontal = 4.dp)
                    )
                }

                Spacer(modifier = Modifier.height(18.dp))

                // Transport (stagger: buttonsAlpha)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .graphicsLayer { alpha = buttonsAlpha },
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Shuffle (чуть крупнее — полевой фидбек)
                    Box(
                        Modifier
                            .size(48.dp)
                            .pressScale { PlayerController.toggleShuffle() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Rounded.Shuffle, null,
                            tint = if (shuffleEnabled) Color(0xFFFC3C44)
                            else Color.White.copy(alpha = 0.40f),
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    AnimatedTransportButton(
                        icon = Icons.Rounded.FastRewind,
                        iconSize = 56.dp,
                        onClick = onSkipPrevious
                    )
                    // Пока трек буферизуется после скипа — вокруг play/pause крутится
                    // кольцо: видно, что плеер грузит трек, а не завис.
                    Box(contentAlignment = Alignment.Center) {
                        AnimatedTransportButton(
                            icon = if (isPlaying) Icons.Rounded.Pause
                                   else Icons.Rounded.PlayArrow,
                            iconSize = 74.dp,
                            onClick = onPlayPause
                        )
                        if (isBuffering) {
                            CircularProgressIndicator(
                                color = Color.White.copy(alpha = 0.85f),
                                strokeWidth = 2.5.dp,
                                modifier = Modifier.size(80.dp)
                            )
                        }
                    }
                    AnimatedTransportButton(
                        icon = Icons.Rounded.FastForward,
                        iconSize = 56.dp,
                        onClick = onSkipNext
                    )

                    // Repeat (чуть крупнее)
                    Box(
                        Modifier
                            .size(48.dp)
                            .pressScale { PlayerController.cycleRepeatMode() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            if (repeatMode == 2) Icons.Rounded.RepeatOne
                            else Icons.Rounded.Repeat, null,
                            tint = if (repeatMode > 0) Color(0xFFFC3C44)
                            else Color.White.copy(alpha = 0.40f),
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                // Громкость и скорость убраны (полевой фидбек): громкость —
                // качелькой телефона, скорость аудио не нужна.
                Spacer(modifier = Modifier.height(18.dp))

                // Bottom icons (stagger: bottomAlpha)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 40.dp)
                        .graphicsLayer { alpha = bottomAlpha },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    BottomIcon(Icons.Rounded.ChatBubbleOutline) {
                        if (showLyrics) {
                            showLyrics = false
                            controlsVisible = true
                        } else {
                            showLyrics = true
                            controlsVisible = false
                            showQueue = false // Close queue if open
                        }
                    }
                    BottomIcon(Icons.Rounded.Cast) { showAirPlay = true }

                    BottomIcon(Icons.AutoMirrored.Rounded.QueueMusic) {
                        if (showQueue) {
                            showQueue = false
                            controlsVisible = true
                        } else {
                            showQueue = true
                            controlsVisible = false
                            showLyrics = false // Close lyrics if open
                        }
                    }
                }
            }
        }
        } // Box (controls + scrim)
        } // AnimatedVisibility(controls)

        // ═══ AirPlay ═══
        AirPlaySheet(
            visible = showAirPlay,
            backdrop = playerBackdrop,
            trackTitle = trackTitle,
            artistName = artistName,
            albumArtUri = albumArtUri,
            onDismiss = { showAirPlay = false }
        )

        // ═══ Debug Panel ═══
        AnimatedVisibility(
            visible = showDebugPanel,
            enter = fadeIn(tween(250)),
            exit = fadeOut(tween(200))
        ) {
            DebugPanel(
                onDismiss = { showDebugPanel = false }
            )
        }

        // ═══ Видеоклип: фуллскрин (поверх контролов, чёрный фон, 16:9) ═══
        // Активная ориентация — альбом (LaunchedEffect выше); тап по видео или
        // значок сворачивают обратно, back тоже (BackHandler выше).
        if (isVideoClip && clipFullscreen) {
            BoxWithConstraints(modifier = Modifier.fillMaxSize().background(Color.Black)) {
                // Экран шире 16:9 (типичный телефон-альбом) → видео упирается в
                // высоту (рамки по бокам); уже — в ширину (рамки сверху/снизу).
                val heightFirst = maxWidth / maxHeight > 16f / 9f
                androidx.compose.ui.viewinterop.AndroidView(
                    factory = { ctx ->
                        android.view.SurfaceView(ctx).also {
                            PlayerController.attachVideoSurface(it)
                        }
                    },
                    onRelease = { PlayerController.attachVideoSurface(null) },
                    modifier = Modifier
                        .align(Alignment.Center)
                        .then(if (heightFirst) Modifier.fillMaxHeight() else Modifier.fillMaxWidth())
                        .aspectRatio(16f / 9f, matchHeightConstraintsFirst = heightFirst)
                )
                // Тап по любому месту — выход из фуллскрина.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { clipFullscreen = false }
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 40.dp, end = 16.dp)
                        .size(38.dp)
                        .background(Color.Black.copy(alpha = 0.45f), CircleShape)
                        .pressScale { clipFullscreen = false },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Rounded.FullscreenExit, "Exit fullscreen",
                        tint = Color.White, modifier = Modifier.size(24.dp)
                    )
                }
            }
        }


        // ═══ Artist Selection BottomSheet (for multi-artist tracks) ═══
        if (showArtistSheet) {
            ModalBottomSheet(
                onDismissRequest = { showArtistSheet = false },
                sheetState = artistSheetState,
                containerColor = Color(0xFF1C1C1E),
                shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
                        .padding(bottom = 32.dp)
                ) {
                    // Sheet title
                    Text(
                        text = "Artists",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp,
                        modifier = Modifier.padding(vertical = 16.dp)
                    )
                    // Artist list
                    artists.forEachIndexed { index, artist ->
                        val artistId = artist.id
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    artistId?.let {
                                        scope.launch {
                                            artistSheetState.hide()
                                            showArtistSheet = false
                                            onNavigateToArtist(it)
                                        }
                                    }
                                }
                                .padding(vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = artist.displayName,
                                color = Color.White,
                                fontSize = 17.sp,
                                modifier = Modifier.weight(1f)
                            )
                            if (artistId != null) {
                                Icon(
                                    imageVector = Icons.Rounded.ChevronRight,
                                    contentDescription = null,
                                    tint = Color.White.copy(alpha = 0.40f),
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                        if (index < artists.lastIndex) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(0.5.dp)
                                    .background(Color.White.copy(alpha = 0.10f))
                            )
                        }
                    }
                }
            }
        }

        // ═══ Track options menu (Волна по треку / Настройки) ═══
        if (showTrackMenu) {
            ModalBottomSheet(
                onDismissRequest = { showTrackMenu = false },
                sheetState = trackMenuSheetState,
                containerColor = Color(0xFF1C1C1E),
                shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
                        .padding(bottom = 32.dp)
                ) {
                    // Стриминговые пункты (волна / скачать / 👍👎) — только для
                    // онлайн-треков. Для локальных/оффлайн они не имеют смысла:
                    // волна и фидбек — фичи ICM, скачивать скачанное не надо.
                    val isLocalTrack = currentTrackObj?.let {
                        it.source == "local" || it.uri.scheme == "content" || it.uri.scheme == "file"
                    } ?: false
                    if (!isLocalTrack) {
                    // Волна по треку (станция)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                val seed = currentTrackObj
                                scope.launch {
                                    trackMenuSheetState.hide()
                                    showTrackMenu = false
                                    seed?.let { PlayerController.startTrackWave(context, it) }
                                }
                            }
                            .padding(vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.GraphicEq,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Text(
                            text = "Wave from this track",
                            color = Color.White,
                            fontSize = 17.sp,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(0.5.dp)
                            .background(Color.White.copy(alpha = 0.10f))
                    )
                    // Скачать (переехало из ряда кнопок — полевой фидбек):
                    // клип → mp4 в Загрузки, трек → обычная загрузка.
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                scope.launch {
                                    trackMenuSheetState.hide()
                                    showTrackMenu = false
                                }
                                if (!isPremium) {
                                    showPromoDialog = true
                                } else if (isDownloaded) {
                                    showDeleteConfirmDialog = true
                                } else if (!isDownloading) {
                                    currentTrackObj?.let { track ->
                                        if (isVideoClip) AudioDownloadManager.downloadClip(context, track)
                                        else AudioDownloadManager.downloadTrack(context, track)
                                    }
                                }
                            }
                            .padding(vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        when {
                            isDownloading -> CircularProgressIndicator(
                                progress = { progress ?: 0f },
                                modifier = Modifier.size(24.dp),
                                color = Color(0xFFFC3C44),
                                strokeWidth = 2.5.dp
                            )
                            isDownloaded -> Icon(
                                Icons.Rounded.CheckCircle, null,
                                tint = Color(0xFF4CAF50), modifier = Modifier.size(24.dp)
                            )
                            else -> Icon(
                                Icons.Rounded.Download, null,
                                tint = Color.White, modifier = Modifier.size(24.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        Text(
                            text = when {
                                isDownloading -> "Downloading… ${((progress ?: 0f) * 100).toInt()}%"
                                isDownloaded -> "Delete download"
                                else -> "Download"
                            },
                            color = Color.White,
                            fontSize = 17.sp,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(0.5.dp)
                            .background(Color.White.copy(alpha = 0.10f))
                    )
                    // 👍/👎 — фидбек волны (переехали сюда из ряда кнопок).
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                scope.launch {
                                    trackMenuSheetState.hide()
                                    showTrackMenu = false
                                }
                                currentTrackObj?.let { track ->
                                    scope.launch {
                                        WaveSignalQueue.sendFeedback("more_track", track.id)
                                        track.artists.firstOrNull()?.id?.let {
                                            WaveSignalQueue.sendFeedback("more_artist", it)
                                        }
                                    }
                                    Toast.makeText(context, "Got it — more like this", Toast.LENGTH_SHORT).show()
                                }
                            }
                            .padding(vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Rounded.ThumbUp, null,
                            tint = Color.White, modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Text(
                            text = "More like this",
                            color = Color.White,
                            fontSize = 17.sp,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(0.5.dp)
                            .background(Color.White.copy(alpha = 0.10f))
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                scope.launch {
                                    trackMenuSheetState.hide()
                                    showTrackMenu = false
                                }
                                currentTrackObj?.let { track ->
                                    scope.launch {
                                        WaveSignalQueue.sendFeedback("less_track", track.id)
                                        track.artists.firstOrNull()?.id?.let {
                                            WaveSignalQueue.sendFeedback("less_artist", it)
                                        }
                                    }
                                    Toast.makeText(context, "Got it — less like this", Toast.LENGTH_SHORT).show()
                                }
                            }
                            .padding(vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Rounded.ThumbDown, null,
                            tint = Color.White, modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Text(
                            text = "Less like this",
                            color = Color.White,
                            fontSize = 17.sp,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(0.5.dp)
                            .background(Color.White.copy(alpha = 0.10f))
                    )
                    } // if (!isLocalTrack) — конец стриминговых пунктов
                    // Опубликовать текст в LRCLIB — ТОЛЬКО для локального трека
                    val publishTrack = currentTrackObj
                    val isLocalForLrc = isLocalTrack
                    if (isLocalForLrc && publishTrack != null) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    scope.launch {
                                        trackMenuSheetState.hide()
                                        showTrackMenu = false
                                        onPublishLyrics(publishTrack)
                                    }
                                }
                                .padding(vertical = 16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Edit,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            Text(
                                text = "Publish lyrics to LRCLIB",
                                color = Color.White,
                                fontSize = 17.sp,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(0.5.dp)
                                .background(Color.White.copy(alpha = 0.10f))
                        )
                        // Редактировать теги — ТОЛЬКО для локального трека
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    scope.launch {
                                        trackMenuSheetState.hide()
                                        showTrackMenu = false
                                        onEditTags(publishTrack)
                                    }
                                }
                                .padding(vertical = 16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Edit,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            Text(
                                text = "Edit tags",
                                color = Color.White,
                                fontSize = 17.sp,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(0.5.dp)
                                .background(Color.White.copy(alpha = 0.10f))
                        )
                    }
                    // Настройки
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                scope.launch {
                                    trackMenuSheetState.hide()
                                    showTrackMenu = false
                                    onOpenSettings()
                                }
                            }
                            .padding(vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Settings,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Text(
                            text = "Settings",
                            color = Color.White,
                            fontSize = 17.sp,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }

        // --- Premium Promo Dialog ---
        GlassDialog(
            visible = showPromoDialog,
            onDismiss = { showPromoDialog = false },
            icon = Icons.Rounded.Download,
            iconTint = Color(0xFFFC3C44),
            title = "Premium Required",
            message = "Offline listening is strictly an exclusive feature for Premium subscribers under aggregator rules. Upgrade to save tracks and play offline.",
            primaryButton = GlassDialogButton(
                text = "Upgrade",
                onClick = {
                    showPromoDialog = false
                    val intent = android.content.Intent(
                        android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse("https://t.me/byicmbot")
                    )
                    context.startActivity(intent)
                }
            ),
            secondaryButton = GlassDialogButton(
                text = "Cancel",
                onClick = { showPromoDialog = false },
                backgroundColor = Color.White.copy(alpha = 0.08f),
                textColor = Color.White.copy(alpha = 0.7f)
            )
        )

        // --- Delete Confirmation Dialog ---
        GlassDialog(
            visible = showDeleteConfirmDialog,
            onDismiss = { showDeleteConfirmDialog = false },
            icon = Icons.Rounded.Close,
            iconTint = Color(0xFFFF5252),
            title = "Delete Download?",
            message = "Are you sure you want to delete this track from your device? You will need an internet connection to stream it again.",
            primaryButton = GlassDialogButton(
                text = "Delete",
                onClick = {
                    showDeleteConfirmDialog = false
                    AudioDownloadManager.deleteDownloadedTrack(context, trackId)
                },
                backgroundColor = Color(0xFFFF5252)
            ),
            secondaryButton = GlassDialogButton(
                text = "Cancel",
                onClick = { showDeleteConfirmDialog = false },
                backgroundColor = Color.White.copy(alpha = 0.08f),
                textColor = Color.White.copy(alpha = 0.7f)
            )
        )
    }
}

// ══════════════════════════════════════════════════════════════

@Composable
private fun AnimatedTransportButton(
    icon: ImageVector,
    iconSize: Dp,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.80f else 1f,
        animationSpec = spring(dampingRatio = 0.5f, stiffness = 400f),
        label = "transport"
    )

    Box(
        modifier = Modifier
            .size(80.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clickable(
                interactionSource = interactionSource,
                indication = null
            ) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        // Смена иконки (play ↔ pause) — не мгновенный свап, а морф: новая
        // иконка «выпрыгивает» с пружиной + fade, старая ужимается и гаснет.
        // Для prev/next иконка не меняется → AnimatedContent простаивает.
        AnimatedContent(
            targetState = icon,
            transitionSpec = {
                (scaleIn(
                    initialScale = 0.55f,
                    animationSpec = spring(dampingRatio = 0.45f, stiffness = 550f)
                ) + fadeIn(tween(130)))
                    .togetherWith(scaleOut(targetScale = 0.55f, animationSpec = tween(110)) + fadeOut(tween(110)))
            },
            label = "transportIcon"
        ) { targetIcon ->
            Icon(
                imageVector = targetIcon,
                contentDescription = null,
                modifier = Modifier.size(iconSize),
                tint = Color.White
            )
        }
    }
}

@Composable
private fun BottomIcon(icon: ImageVector, onClick: () -> Unit = {}) {
    Box(
        Modifier
            .size(52.dp)
            .pressScale { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, null, tint = Color.White.copy(alpha = 0.50f), modifier = Modifier.size(26.dp))
    }
}

private fun formatTime(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "${s / 60}:%02d".format(s % 60)
}

@Composable
private fun DebugPanel(onDismiss: () -> Unit) {
    val logs = remember { UiLogger.logs }
    val listState = rememberLazyListState()
    val logCount by remember { derivedStateOf { logs.size } }

    // Auto-scroll to bottom on new entries
    LaunchedEffect(logCount) {
        if (logs.isNotEmpty()) {
            listState.animateScrollToItem(logs.lastIndex)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.92f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onDismiss() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Debug Log",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
                Row {
                    // Clear button
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) { UiLogger.clear() },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Clear",
                            color = Color(0xFFFC3C44),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    // Close button
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) { onDismiss() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Rounded.Close, null,
                            tint = Color.White.copy(alpha = 0.70f),
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Log entries
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                state = listState
            ) {
                items(logs) { entry ->
                    val tint = when {
                        entry.contains("FAILED") || entry.contains("exception") -> Color(0xFFFF5252)
                        entry.contains("OK") || entry.contains("Cache hit") -> Color(0xFF4CAF50)
                        entry.contains("[YT]") -> Color(0xFFFF9800)
                        else -> Color.White.copy(alpha = 0.75f)
                    }
                    Text(
                        text = entry,
                        color = tint,
                        fontSize = 11.sp,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 1.dp)
                    )
                }
            }

            // Status bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                val isPlaying by PlayerController.isPlaying.collectAsState()
                val currentTrack by PlayerController.currentTrack.collectAsState()
                val queue by PlayerController.queueFlow.collectAsState()

                Text(
                    text = "playing=$isPlaying  queue=${queue.size}  track=${currentTrack?.id?.take(20) ?: "none"}",
                    color = Color.White.copy(alpha = 0.40f),
                    fontSize = 10.sp,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                )
                Text(
                    text = "${logCount} entries",
                    color = Color.White.copy(alpha = 0.40f),
                    fontSize = 10.sp,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                )
            }
        }
    }
}

/** Ключ кроссфейда обложки: все параметры арта одного трека одним значением. */
private data class ArtCrossfadeKey(
    val uri: Uri?,
    val coverUrl: String?,
    val audioFileUri: Uri?,
    val albumId: Long
)
