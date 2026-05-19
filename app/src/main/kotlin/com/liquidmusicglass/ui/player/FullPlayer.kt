package com.liquidmusicglass.ui.player

import android.content.Context
import android.media.AudioManager
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.automirrored.rounded.VolumeDown
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
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
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberModalBottomSheetState
import com.liquidmusicglass.api.icm.IcmMiniArtist
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.Capsule
import com.liquidmusicglass.engine.PlayerController
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
    isMixing: Boolean,
    onClose: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
    onPlayPause: () -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrevious: () -> Unit,
    onSeek: (Long) -> Unit,
    onVolumeChange: (Float) -> Unit,
    onOpenSettings: () -> Unit,
    onNavigateToArtist: (String) -> Unit = {}
) {
    if (expandProgress <= 0.005f) return

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val libraryRepo = remember { com.liquidmusicglass.data.local.db.LibraryRepository.getInstance(context) }
    val currentTrackObj by PlayerController.currentTrack.collectAsState()
    val trackId = currentTrackObj?.id ?: ""
    val isFavorite by libraryRepo.isFavoriteFlow(trackId).collectAsState(initial = false)
    var showAirPlay by remember { mutableStateOf(false) }
    var showQueue by remember { mutableStateOf(false) }
    var showLyrics by remember { mutableStateOf(false) }
    var showArtistSheet by remember { mutableStateOf(false) }
    val artistSheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true
    )
    // Видимость контролов плеера. Когда открыта лирика — скрываются (как в Apple Music).
    // Тап по области лирики временно показывает их снова.
    var controlsVisible by remember { mutableStateOf(true) }
    val playerBackdrop: LayerBackdrop = rememberLayerBackdrop()

    val shuffleEnabled by PlayerController.shuffleEnabled.collectAsState()
    val repeatMode by PlayerController.repeatMode.collectAsState()
    // currentTrackObj is declared above for Room reactive favorite state

    // isFavorite is now reactive from Room DB via LibraryRepository Flow
    // (declared above at composition start)

    // ── Mood/Color from album art ──
    val albumColors = rememberAlbumColors(albumArtUri, coverUrl)

    // ── Gesture: horizontal swipe for skip ──
    val swipeOffsetX = remember { Animatable(0f) }
    var swipeTriggered by remember { mutableStateOf(false) }

    // Предзагрузка AGSL шейдеров — 1x1dp invisible, компилирует blur/lens/chromatic при первом compose
    val shadersWarmedUp = remember { mutableStateOf(false) }
    if (!shadersWarmedUp.value) {
        Box(
            Modifier
                .size(1.dp)
                .graphicsLayer { alpha = 0f }
                .drawBackdrop(
                    backdrop = playerBackdrop,
                    shape = { Capsule() },
                    effects = {
                        blur(1f)
                        lens(1f, 1f, chromaticAberration = true)
                    }
                )
        )
        LaunchedEffect(Unit) { shadersWarmedUp.value = true }
    }

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

    // Авто-скрытие контролов поверх лирики: через 3 сек после показа прячем обратно
    LaunchedEffect(controlsVisible, showLyrics) {
        if (showLyrics && controlsVisible) {
            kotlinx.coroutines.delay(3000L)
            controlsVisible = false
        }
    }

    val controlsAlpha = ((expandProgress - 0.4f) / 0.6f).coerceIn(0f, 1f)
    val bgAlpha = (expandProgress * 1.5f).coerceIn(0f, 1f)

    // ── Morphing parameters ──
    // Album art: 44dp → fullscreen, corners 10dp → 0dp
    // Controls stagger: each element appears with slight delay
    val titleAlpha = ((expandProgress - 0.25f) / 0.5f).coerceIn(0f, 1f)
    val sliderAlpha = ((expandProgress - 0.35f) / 0.5f).coerceIn(0f, 1f)
    val buttonsAlpha = ((expandProgress - 0.45f) / 0.5f).coerceIn(0f, 1f)
    val bottomAlpha = ((expandProgress - 0.55f) / 0.4f).coerceIn(0f, 1f)
    // Controls slide up offset
    val controlsOffsetY = ((1f - expandProgress) * 80f)

    // Volume sync — smoothed to avoid discrete jumps
    val rawSystemVolume by rememberSystemVolume()
    val systemVolume by animateFloatAsState(
        targetValue = rawSystemVolume,
        animationSpec = spring(dampingRatio = 0.9f, stiffness = 200f),
        label = "vol_smooth"
    )

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
            AnimatedPlayerBackground(
                albumArtUri = albumArtUri,
                coverUrl = coverUrl,
                audioFileUri = audioFileUri,
                albumId = albumId,
                albumColors = albumColors,
                modifier = Modifier.fillMaxSize()
            )

            // ═══ Album art (inside backdrop so glass sees it) ═══
            // При открытой лирике большая обложка скрывается — у лирики своя шапка
            val artAlpha by animateFloatAsState(
                targetValue = if (showLyrics) 0f else 1f,
                animationSpec = tween(300),
                label = "artAlpha"
            )
            val artPaddingH = (24f * expandProgress.coerceIn(0f, 1f)).coerceIn(0f, 24f)
            val artCornerR = (16f * expandProgress.coerceIn(0f, 1f)).coerceIn(0f, 16f)
            val artShape = RoundedCornerShape(artCornerR.dp)

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = artPaddingH.dp)
                    .padding(top = (80.dp * expandProgress))
                    .aspectRatio(1f)
                    .graphicsLayer {
                        translationX = swipeOffsetX.value
                        shadowElevation = 24f * expandProgress.coerceIn(0f, 1f)
                        alpha = artAlpha
                        clip = true
                        shape = artShape
                    }
            ) {
                AlbumArtImage(
                    uri = albumArtUri,
                    coverUrl = coverUrl,
                    audioFileUri = audioFileUri,
                    albumId = albumId,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
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
                        }
                    )
                }
        )

        // ═══ Lyrics ═══
        // Рисуется ДО контролов — чтобы контролы (по тапу) всплывали поверх лирики.
        AnimatedVisibility(
            visible = showLyrics,
            enter = fadeIn(tween(400, easing = FastOutSlowInEasing)),
            exit = fadeOut(tween(350, easing = FastOutSlowInEasing))
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
                onClose = { showLyrics = false }
            )
        }

        // ═══ Controls ═══
        // Видны всегда, когда лирика закрыта. Когда лирика открыта — только
        // если controlsVisible (по тапу), и автоматически прячутся через 3 сек.
        AnimatedVisibility(
            visible = !showLyrics || controlsVisible,
            enter = fadeIn(tween(250)),
            exit = fadeOut(tween(250))
        ) {
        Box(modifier = Modifier.fillMaxSize()) {
        // Подложка под контролами — только когда открыта лирика.
        // Цвет — из палитры обложки (не чёрный), чтобы совпадал с фоном.
        if (showLyrics) {
            // Цвет обложки — светлее (меньше чёрного), непрозрачный книзу
            val scrimColor = lerp(albumColors.dominant, Color.Black, 0.3f)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(460.dp)
                    .align(Alignment.BottomCenter)
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
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars)
                .windowInsetsPadding(WindowInsets.navigationBars),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Drag Handle
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer { alpha = controlsAlpha }
                    .clickable(
                        remember { MutableInteractionSource() }, null
                    ) { onClose() }
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
                // Track Info — скрыта в режиме лирики (название уже в шапке лирики)
                AnimatedVisibility(visible = !showLyrics) {
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
                        Spacer(Modifier.height(2.dp))
                        // Artist name — wrapped in Box for minimum 48.dp touch target
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null
                                ) {
                                    when {
                                        artists.size == 1 && artists[0].id != null -> {
                                            artists[0].id?.let { onNavigateToArtist(it) }
                                        }
                                        artists.size > 1 -> {
                                            showArtistSheet = true
                                        }
                                    }
                                }
                                .padding(vertical = 12.dp),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            Text(
                                text = artistName,
                                color = Color.White.copy(alpha = 0.60f),
                                fontSize = 16.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .pressScale {
                                currentTrackObj?.let { track ->
                                    scope.launch {
                                        libraryRepo.toggleFavorite(track)
                                    }
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            if (isFavorite) Icons.Rounded.Favorite
                            else Icons.Rounded.FavoriteBorder, null,
                            tint = if (isFavorite) Color(0xFFFC3C44)
                            else Color.White.copy(alpha = 0.70f),
                            modifier = Modifier.size(26.dp)
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .pressScale { onOpenSettings() },
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
                    val mAlpha by animateFloatAsState(
                        if (isMixing) 1f else 0f,
                        tween(if (isMixing) 600 else 400, easing = FastOutSlowInEasing),
                        label = "mix"
                    )
                    Text(
                        "Mixing",
                        color = Color.White.copy(alpha = 0.65f * mAlpha),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.graphicsLayer { alpha = mAlpha }
                    )
                    val remaining = (durationMs - currentPositionMs).coerceAtLeast(0)
                    Text(
                        "-${formatTime(remaining)}",
                        color = Color.White.copy(alpha = 0.50f),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
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
                    // Shuffle
                    Box(
                        Modifier
                            .size(44.dp)
                            .pressScale { PlayerController.toggleShuffle() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Rounded.Shuffle, null,
                            tint = if (shuffleEnabled) Color(0xFFFC3C44)
                            else Color.White.copy(alpha = 0.40f),
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    AnimatedTransportButton(
                        icon = Icons.Rounded.FastRewind,
                        iconSize = 50.dp,
                        onClick = onSkipPrevious
                    )
                    AnimatedTransportButton(
                        icon = if (isPlaying) Icons.Rounded.Pause
                               else Icons.Rounded.PlayArrow,
                        iconSize = 66.dp,
                        onClick = onPlayPause
                    )
                    AnimatedTransportButton(
                        icon = Icons.Rounded.FastForward,
                        iconSize = 50.dp,
                        onClick = onSkipNext
                    )

                    // Repeat
                    Box(
                        Modifier
                            .size(44.dp)
                            .pressScale { PlayerController.cycleRepeatMode() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            if (repeatMode == 2) Icons.Rounded.RepeatOne
                            else Icons.Rounded.Repeat, null,
                            tint = if (repeatMode > 0) Color(0xFFFC3C44)
                            else Color.White.copy(alpha = 0.40f),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                // Volume — скрыта в режиме лирики
                AnimatedVisibility(visible = !showLyrics) {
                Column {
                Spacer(modifier = Modifier.height(22.dp))

                // Volume — liquid glass slider
                Row(
                    Modifier
                        .fillMaxWidth()
                        .pointerInput(Unit) {
                            // Перехватываем вертикальные drag'и чтобы они не уходили
                            // в detectVerticalDragGestures FullPlayer'а
                            detectVerticalDragGestures(
                                onVerticalDrag = { change, _ -> change.consume() }
                            )
                        },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.AutoMirrored.Rounded.VolumeDown, null,
                        tint = Color.White.copy(alpha = 0.40f),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    val audioManager = remember {
                        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    }
                    val maxVolumeSteps = remember { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }
                    val lastVolumeStep = remember { intArrayOf(-1) }

                    LiquidSlider(
                        value = { systemVolume },
                        onValueChange = { v ->
                            val newStep = (v * maxVolumeSteps).toInt().coerceIn(0, maxVolumeSteps)
                            if (newStep != lastVolumeStep[0]) {
                                lastVolumeStep[0] = newStep
                                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newStep, 0)
                            }
                        },
                        backdrop = playerBackdrop,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(10.dp))
                    Icon(
                        Icons.AutoMirrored.Rounded.VolumeUp, null,
                        tint = Color.White.copy(alpha = 0.40f),
                        modifier = Modifier.size(16.dp)
                    )
                }
                }
                }

                Spacer(modifier = Modifier.height(18.dp))

                // Bottom icons (stagger: bottomAlpha)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 56.dp)
                        .graphicsLayer { alpha = bottomAlpha },
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    BottomIcon(Icons.Rounded.ChatBubbleOutline) {
                        if (showLyrics) {
                            // Лирика открыта и контролы видны (раз кнопку нажали) — закрываем лирику
                            showLyrics = false
                            controlsVisible = true
                        } else {
                            // Открываем лирику и прячем контролы
                            showLyrics = true
                            controlsVisible = false
                        }
                    }
                    BottomIcon(Icons.Rounded.Cast) { showAirPlay = true }
                    BottomIcon(Icons.AutoMirrored.Rounded.QueueMusic) { showQueue = true }
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

        // ═══ Queue ═══
        QueueSheet(
            visible = showQueue,
            onDismiss = { showQueue = false }
        )

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
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(iconSize),
            tint = Color.White
        )
    }
}

@Composable
private fun BottomIcon(icon: ImageVector, onClick: () -> Unit = {}) {
    Box(
        Modifier
            .size(48.dp)
            .pressScale { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, null, tint = Color.White.copy(alpha = 0.50f), modifier = Modifier.size(22.dp))
    }
}

private fun formatTime(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "${s / 60}:%02d".format(s % 60)
}
