package com.liquidmusicglass.ui.screens

import android.net.Uri
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.withInfiniteAnimationFrameMillis
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.ThumbDown
import androidx.compose.material.icons.rounded.Whatshot
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.liquidmusicglass.api.icm.IcmChart
import com.liquidmusicglass.api.icm.IcmHomeItem
import com.liquidmusicglass.api.icm.IcmRepository
import com.liquidmusicglass.api.icm.WaveSignalQueue
import com.liquidmusicglass.api.icm.toTrack
import com.liquidmusicglass.engine.AudioReactor
import com.liquidmusicglass.engine.LyricsParser
import com.liquidmusicglass.engine.PlaybackBackend
import com.liquidmusicglass.engine.PlayerController
import com.liquidmusicglass.engine.Track
import com.liquidmusicglass.ui.glass.AlbumArtImage
import com.liquidmusicglass.ui.glass.liquidClickable
import com.liquidmusicglass.ui.glass.rememberAlbumColors
import com.liquidmusicglass.ui.player.AuraBackground
import com.liquidmusicglass.ui.theme.AppFontFamily
import com.liquidmusicglass.ui.theme.LiquidMotion
import com.liquidmusicglass.ui.viewmodel.HomeViewModel
import java.util.Calendar
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "My Wave" — the main screen, our own take on the Yandex-Music style feed.
 *
 * The screen is now just the wave hero, vertically centered (idle: big title +
 * Play; playing: artist, cover, flat controls and a live wave progress line in
 * the title pill). Mood tiles and content sections (recently played, charts,
 * recommendations) live in the New tab.
 *
 * Glass is intentionally avoided (heavy blur lags on devices) — controls are flat
 * and the background is a single animated aura Canvas. Tapping the cover or the
 * title panel opens the full-screen player via [onOpenPlayer].
 */
@Composable
fun WaveHomeScreen(
    onNavigateToSearch: () -> Unit = {},
    onOpenPlayer: () -> Unit = {},
    onNavigateToAlbum: (String) -> Unit = {},
    onNavigateToArtist: (String) -> Unit = {},
    onNavigateToPlaylist: (String) -> Unit = {},
    onOpenAuth: () -> Unit = {},
    onOpenProfile: () -> Unit = {},
    animationsActive: Boolean = true,
) {
    val context = LocalContext.current
    val viewModel = remember { HomeViewModel() }
    DisposableEffect(viewModel) { onDispose { viewModel.cancelLoads() } }

    LaunchedEffect(Unit) { viewModel.loadHomeContent() }

    val currentTrack by PlayerController.currentTrack.collectAsState()
    val isPlaying by PlayerController.isPlaying.collectAsState()
    val isBuffering by PlayerController.isBuffering.collectAsState()
    val favoriteIds by PlayerController.favoriteIds.collectAsState()
    val isBuildingWave by viewModel.isBuildingWave.collectAsState()
    val needsOnboarding by viewModel.needsOnboarding.collectAsState()
    val needsLink by viewModel.needsLink.collectAsState()

    // Активная «именованная» волна (по муду/треку/артисту). У дефолтной «Моей волны»
    // имени нет → индикатор не показываем.
    val waveContext by PlayerController.waveRefillContext.collectAsState()
    val activeStationName = waveContext?.name?.takeIf { it.isNotBlank() }

    val albumColors = rememberAlbumColors(currentTrack?.displayArtUri, currentTrack?.coverUrl)

    val track = currentTrack
    val isFavorite = track?.id?.let { favoriteIds.contains(it) } == true

    val scope = rememberCoroutineScope()
    val backend by PlayerController.playbackBackend.collectAsState()
    val queueList by PlayerController.queueFlow.collectAsState()

    // Сглаженный бас 0..1 — пульс обложки и кромок мудкарточек. Питается только
    // от стриминга (BassAudioProcessor в цепочке ExoPlayer); у локального JUCE
    // уровней нет — эффект просто нулевой, как и «вдох» ауры-дыма. Кадровый цикл
    // работает ТОЛЬКО пока играет музыка (на паузе плавно гаснет и остановлен);
    // в энергосбережении пульс выключен целиком.
    val bassLevel = rememberSmoothedBass(
        animate = animationsActive && !com.liquidmusicglass.ui.PowerSaveMonitor.active,
        playing = isPlaying
    )

    // Акцент экрана — vibrant играющей обложки (fallback — зелёный волны).
    val accent by animateColorAsState(
        targetValue = if (track != null) albumColors.vibrant else WaveAccent,
        animationSpec = tween(700),
        label = "waveAccent"
    )

    // «Дальше в волне»: следующие до трёх треков очереди; индекс — абсолютный
    // (для PlayerController.playTrack). Очередь и позиция общие для обоих
    // бэкендов (JUCE-локал и ExoPlayer-стриминг) — контроллер сам маршрутизирует.
    val upNext = remember(queueList, track?.id) {
        val idx = queueList.indexOfFirst { it.id == track?.id }
        if (idx < 0) emptyList()
        else (idx + 1 until minOf(idx + 4, queueList.size)).map { it to queueList[it] }
    }

    // Фидбек волны — только ICM-стриминг с активным wave-контекстом
    // (для локальных файлов на JUCE он бессмыслен).
    val showWaveFeedback = track != null &&
        waveContext != null &&
        backend == PlaybackBackend.EXO_STREAMING

    Box(modifier = Modifier.fillMaxSize()) {

        // ── Living aura background (own AGSL shader, reacts to the music) ──
        AuraBackground(
            albumColors = albumColors,
            modifier = Modifier.fillMaxSize(),
            animate = animationsActive,
            smokeSaturation = 1.22f,
            smokeContrast = 1.16f
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            WaveTopBar(onSearch = onNavigateToSearch, onOpenProfile = onOpenProfile)

            // ── Индикатор активной волны (по муду/треку/артисту) + сброс на «Мою волну» ──
            if (activeStationName != null) {
                WaveStationIndicator(
                    name = activeStationName,
                    onClear = { viewModel.buildWaveQueue(context) }
                )
            }

            // ── Hero — по центру освободившегося экрана (мудкарточки уехали в New).
            // Box центрует, внутренний скролл — страховка для маленьких экранов. ──
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(bottom = 72.dp),   // высота нижнего бара + зазор
                contentAlignment = Alignment.Center
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                if (track == null) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 48.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // Приветствие по времени суток — экран «свой» ещё до музыки.
                        Text(
                            text = remember { greetingForNow() },
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            fontFamily = AppFontFamily,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = "My Wave",
                            color = Color.White,
                            fontSize = 46.sp,
                            fontWeight = FontWeight.Black,
                            fontFamily = AppFontFamily,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(40.dp))
                        BigPlayButton(
                            loading = isBuildingWave,
                            accent = accent,
                            onClick = { viewModel.buildWaveQueue(context) }
                        )
                    }
                } else {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Spacer(Modifier.height(24.dp))
                        // Имя артиста — КРУПНО (главный герой экрана). lineHeight >
                        // fontSize — при переносах (несколько артистов) строки не
                        // налезают друг на друга; до 3 строк, дальше многоточие.
                        Text(
                            text = track.artist,
                            color = Color.White,
                            fontSize = 60.sp,
                            lineHeight = 66.sp,
                            fontWeight = FontWeight.Black,
                            fontFamily = AppFontFamily,
                            textAlign = TextAlign.Center,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp)
                        )
                        Spacer(Modifier.height(6.dp))
                        // Текущая строка синхронного текста (пусто, если лирики нет).
                        CurrentLyricLine(track = track)
                        Spacer(Modifier.height(16.dp))

                        // Обложка: пульсирует от баса (только стриминг — у JUCE
                        // уровней нет) и свайпается влево/вправо для скипа. Скип —
                        // через PlayerController (асинхронно, оба бэкенда), main
                        // ничего не ждёт.
                        val density = LocalDensity.current
                        val dragThresholdPx = remember(density) { with(density) { 96.dp.toPx() } }
                        val maxDragPx = remember(density) { with(density) { 160.dp.toPx() } }
                        val coverOffset = remember { Animatable(0f) }

                        // Страховка от «застрявшей» наклонённой обложки: как только
                        // реально сменился трек — возвращаем обложку в центр, даже
                        // если возвратная анимация жеста была чем-то отменена.
                        LaunchedEffect(track.id) {
                            if (coverOffset.value != 0f) {
                                coverOffset.animateTo(0f, spring(dampingRatio = 0.85f, stiffness = 420f))
                            }
                        }

                        Box(
                            modifier = Modifier
                                .size(248.dp)
                                .graphicsLayer {
                                    translationX = coverOffset.value
                                    rotationZ = coverOffset.value / 42f
                                    val pulse = 1f + bassLevel.value * 0.025f
                                    scaleX = pulse
                                    scaleY = pulse
                                    alpha = 1f -
                                        (abs(coverOffset.value) / (dragThresholdPx * 4f)).coerceAtMost(0.35f)
                                }
                                .clip(RoundedCornerShape(28.dp))
                                .draggable(
                                    orientation = Orientation.Horizontal,
                                    state = rememberDraggableState { delta ->
                                        // Резинка: тянется с сопротивлением и не дальше maxDragPx.
                                        scope.launch {
                                            val next = (coverOffset.value + delta * 0.85f)
                                                .coerceIn(-maxDragPx, maxDragPx)
                                            coverOffset.snapTo(next)
                                        }
                                    },
                                    onDragStopped = { velocity ->
                                        val off = coverOffset.value
                                        when {
                                            off < -dragThresholdPx || velocity < -2800f ->
                                                PlayerController.skipNext(context)
                                            off > dragThresholdPx || velocity > 2800f ->
                                                PlayerController.skipPrevious(context)
                                        }
                                        // Возврат — в ТОМ ЖЕ scope, что и snapTo-дельты:
                                        // единая очередь main, отставший snapTo не отменит
                                        // возвратную анимацию (раньше обложка могла застрять
                                        // наклонённой на время буферизации).
                                        scope.launch {
                                            coverOffset.animateTo(
                                                0f,
                                                spring(dampingRatio = 0.78f, stiffness = 300f)
                                            )
                                        }
                                    }
                                )
                                .clickable { onOpenPlayer() },
                            contentAlignment = Alignment.Center
                        ) {
                            AlbumArtImage(
                                uri = track.displayArtUri,
                                coverUrl = track.coverUrl,
                                albumId = track.albumId,
                                contentDescription = track.title,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                            // Буферизация после скипа: затемнение + спиннер прямо на
                            // обложке — видно, что трек грузится, а не «всё зависло».
                            if (isBuffering) {
                                Box(
                                    Modifier
                                        .fillMaxSize()
                                        .background(Color.Black.copy(alpha = 0.35f))
                                )
                                CircularProgressIndicator(
                                    color = Color.White,
                                    strokeWidth = 3.dp,
                                    modifier = Modifier.size(40.dp)
                                )
                            }
                        }
                        Spacer(Modifier.height(28.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 24.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            FlatCircleButton(onClick = { PlayerController.togglePlayPause(context) }) {
                                Icon(
                                    imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                    contentDescription = if (isPlaying) "Pause" else "Play",
                                    tint = Color.White,
                                    modifier = Modifier.size(26.dp)
                                )
                            }
                            // Перемотка водой: горизонтальный свайп по пилюле —
                            // вода следует за пальцем, отпустил — seek. Тап без
                            // движения — открытие плеера, как раньше.
                            var scrubFrac by remember(track.id) {
                                androidx.compose.runtime.mutableStateOf<Float?>(null)
                            }
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(56.dp)
                                    .clip(RoundedCornerShape(28.dp))
                                    // Подложка пилюли красится акцентом обложки —
                                    // экран целиком уходит в палитру трека.
                                    .background(accent.copy(alpha = 0.16f))
                                    .clickable { onOpenPlayer() }
                                    .pointerInput(track.id, track.durationMs) {
                                        if (track.durationMs <= 0L) return@pointerInput
                                        detectHorizontalDragGestures(
                                            onDragStart = { off ->
                                                scrubFrac = (off.x / size.width)
                                                    .coerceIn(0f, 1f)
                                            },
                                            onDragEnd = {
                                                scrubFrac?.let { f ->
                                                    PlayerController.seekTo(
                                                        (f * track.durationMs).toLong()
                                                    )
                                                }
                                                scrubFrac = null
                                            },
                                            onDragCancel = { scrubFrac = null },
                                            onHorizontalDrag = { change, _ ->
                                                scrubFrac = (change.position.x / size.width)
                                                    .coerceIn(0f, 1f)
                                                change.consume()
                                            }
                                        )
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                // Прогресс — «жидкость»: заливает пилюлю целиком
                                // слева направо, волнистый край дышит пока играет.
                                WaveProgressFill(
                                    durationMs = track.durationMs,
                                    accent = accent,
                                    playing = isPlaying,
                                    animate = animationsActive &&
                                        !com.liquidmusicglass.ui.PowerSaveMonitor.active,
                                    overrideProgress = scrubFrac,
                                    modifier = Modifier.fillMaxSize()
                                )
                                Text(
                                    text = track.title,
                                    color = Color.White,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    fontFamily = AppFontFamily,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(horizontal = 16.dp)
                                )
                            }
                            FlatCircleButton(onClick = { PlayerController.toggleFavorite(track.id) }) {
                                Icon(
                                    imageVector = Icons.Rounded.FavoriteBorder,
                                    contentDescription = "Like",
                                    tint = if (isFavorite) Color(0xFFFF4D67) else Color.White,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }

                        // ── Быстрый фидбек волны (только ICM-стриминг с wave-контекстом) ──
                        if (showWaveFeedback) {
                            Spacer(Modifier.height(14.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                WaveFeedbackChip(
                                    icon = Icons.Rounded.Whatshot,
                                    label = "More like this",
                                    tint = accent
                                ) {
                                    scope.launch(Dispatchers.IO) {
                                        runCatching { WaveSignalQueue.sendFeedback("more_track", track.id) }
                                    }
                                }
                                WaveFeedbackChip(
                                    icon = Icons.Rounded.ThumbDown,
                                    label = "Less",
                                    tint = Color.White.copy(alpha = 0.75f)
                                ) {
                                    scope.launch(Dispatchers.IO) {
                                        runCatching { WaveSignalQueue.sendFeedback("less_track", track.id) }
                                    }
                                    PlayerController.skipNext(context)
                                }
                            }
                            // ── Жанровые чипы: правят волну целым жанром. Жанр
                            // резолвится ЛЕНИВО (по тапу): у wave-треков его нет в
                            // ответе — добираем из /track/meta только когда
                            // пользователь реально нажал (ноль лишних запросов). ──
                            Spacer(Modifier.height(8.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                fun sendGenreFeedback(type: String) {
                                    scope.launch(Dispatchers.IO) {
                                        val genre = track.genre
                                            ?: runCatching { IcmRepository.getTrackMeta(track.id)?.genre }.getOrNull()
                                        if (genre.isNullOrBlank()) {
                                            launch(Dispatchers.Main) {
                                                android.widget.Toast.makeText(
                                                    context, "Genre unknown for this track",
                                                    android.widget.Toast.LENGTH_SHORT
                                                ).show()
                                            }
                                        } else {
                                            runCatching { WaveSignalQueue.sendFeedback(type, genre) }
                                        }
                                    }
                                }
                                WaveFeedbackChip(
                                    icon = Icons.Rounded.Whatshot,
                                    label = "More genre",
                                    tint = accent.copy(alpha = 0.85f)
                                ) { sendGenreFeedback("more_genre") }
                                WaveFeedbackChip(
                                    icon = Icons.Rounded.ThumbDown,
                                    label = "Less genre",
                                    tint = Color.White.copy(alpha = 0.6f)
                                ) { sendGenreFeedback("less_genre") }
                            }
                        }

                        // ── «Дальше в волне»: следующие обложки, тап — перескок ──
                        if (upNext.isNotEmpty()) {
                            Spacer(Modifier.height(22.dp))
                            UpNextRow(
                                upNext = upNext,
                                onPlay = { index -> PlayerController.playTrack(context, index) }
                            )
                        }
                    }
                }
                }
            }
            // Мудкарточки и рекомендации живут в табе New — низ Wave свободен,
            // герой отцентрован по вертикали.
        }

        // ── Онбординг волны: показываем, когда персональная волна пуста и юзер
        // ещё не выбрал стартовых артистов (по доке: empty wave → wave/onboarding). ──
        if (needsOnboarding) {
            WaveOnboardingScreen(
                onComplete = {
                    viewModel.clearOnboardingFlag()
                    viewModel.buildWaveQueue(context)
                },
                onDismiss = { viewModel.clearOnboardingFlag() }
            )
        }

        // ── Гейт по TG-линку: без partner_user_id персонализации нет, поэтому вместо
        // общей выдачи предлагаем залогиниться через Telegram. ──
        if (needsLink) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { viewModel.clearLinkFlag() },
                title = { Text("Connect Telegram") },
                text = {
                    Text(
                        "Sign in with Telegram so My Wave can adapt to you. " +
                        "Without linking, the server returns generic recommendations."
                    )
                },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = {
                        viewModel.clearLinkFlag()
                        onOpenAuth()
                    }) { Text("Sign in") }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(onClick = {
                        viewModel.clearLinkFlag()
                    }) { Text("Later") }
                }
            )
        }

    }
}

internal fun IcmHomeItem.toWaveTrack(): Track = Track(
    id = id,
    title = title,
    artist = displayArtist,
    albumName = album ?: "",
    uri = Uri.parse("https://byicloud.online/track/$id"),
    durationMs = durationMs,
    albumId = collectionId?.hashCode()?.toLong() ?: -1L,
    coverUrl = cover,
    // artistId есть в модели — без него тап по артисту в плеере молчал
    // для треков, запущенных с главной/New.
    artists = artistId?.let {
        listOf(com.liquidmusicglass.api.icm.IcmMiniArtist(id = it, name = displayArtist))
    } ?: emptyList(),
    isExplicit = isExplicit,
    // без source резолвер стрима не знал, откуда тянуть (apple/vk) → трек не грузился
    source = source,
    genre = genre
)

@Composable
private fun WaveSectionHeader(title: String) {
    Text(
        text = title,
        color = Color.White,
        fontSize = 22.sp,
        fontWeight = FontWeight.Bold,
        fontFamily = AppFontFamily,
        modifier = Modifier
            .padding(horizontal = 20.dp)
            .padding(bottom = 12.dp)
    )
}

@Composable
private fun WaveTrackCard(
    title: String,
    subtitle: String,
    coverUrl: String?,
    uri: Uri? = null,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .width(140.dp)
            .liquidClickable { onClick() }
    ) {
        if (uri != null) {
            AlbumArtImage(
                uri = uri,
                coverUrl = coverUrl,
                modifier = Modifier
                    .size(140.dp)
                    .clip(RoundedCornerShape(12.dp))
            )
        } else {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(coverUrl)
                    .crossfade(true)
                    .build(),
                contentDescription = title,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(140.dp)
                    .clip(RoundedCornerShape(12.dp))
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = title,
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = AppFontFamily,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = subtitle,
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 12.sp,
            fontFamily = AppFontFamily,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun WaveChartCard(
    chart: IcmChart,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .width(160.dp)
            .liquidClickable { onClick() }
    ) {
        Box(
            modifier = Modifier
                .size(160.dp)
                .clip(RoundedCornerShape(12.dp))
        ) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(chart.cover)
                    .crossfade(true)
                    .build(),
                contentDescription = chart.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f)),
                            startY = 80f
                        )
                    )
            )
            Text(
                text = chart.name,
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = AppFontFamily,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(12.dp)
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = "${chart.tracks.size} tracks",
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 12.sp,
            fontFamily = AppFontFamily,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun FlatCircleButton(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .size(56.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.12f))
            .liquidClickable(pressedScale = LiquidMotion.PressButton) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

/** Чип активной именованной волны: «Wave by <name>» + крестик сброса на My Wave. */
@Composable
private fun WaveStationIndicator(name: String, onClear: () -> Unit) {
    Row(
        modifier = Modifier
            .padding(horizontal = 20.dp, vertical = 4.dp)
            .clip(androidx.compose.foundation.shape.CircleShape)
            .background(Color.White.copy(alpha = 0.12f))
            .padding(start = 14.dp, end = 4.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "Wave by $name",
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = AppFontFamily,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
        )
        Spacer(Modifier.width(4.dp))
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .liquidClickable(pressedScale = LiquidMotion.PressIcon) { onClear() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = "Reset to My Wave",
                tint = Color.White.copy(alpha = 0.85f),
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

@Composable
private fun WaveTopBar(onSearch: () -> Unit, onOpenProfile: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(horizontal = 16.dp)
    ) {
        // Слева — профиль: аватарка пользователя, если залогинен (серый
        // человечек — только для гостя).
        val avatarUrl by com.liquidmusicglass.api.icm.IcmAuthRepository
            .avatarUrl.collectAsState()
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .size(40.dp)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(Color.White.copy(alpha = 0.12f))
                .liquidClickable(pressedScale = LiquidMotion.PressIcon) { onOpenProfile() },
            contentAlignment = Alignment.Center
        ) {
            if (!avatarUrl.isNullOrBlank()) {
                AsyncImage(
                    model = avatarUrl,
                    contentDescription = "Profile",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(
                    imageVector = Icons.Rounded.AccountCircle,
                    contentDescription = "Profile",
                    tint = Color.White,
                    modifier = Modifier.size(26.dp)
                )
            }
        }

        Text(
            text = "My Wave",
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.Black,
            fontFamily = AppFontFamily,
            modifier = Modifier.align(Alignment.Center)
        )

        Icon(
            imageVector = Icons.Rounded.Search,
            contentDescription = "Search",
            tint = Color.White,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .size(26.dp)
                .liquidClickable(pressedScale = LiquidMotion.PressIcon) { onSearch() }
        )
    }
}

@Composable
private fun BigPlayButton(loading: Boolean, accent: Color = WaveAccent, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(132.dp)
            .liquidClickable(enabled = !loading, pressedScale = LiquidMotion.PressButton) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        if (loading) {
            CircularProgressIndicator(
                color = accent,
                strokeWidth = 3.dp,
                modifier = Modifier.size(48.dp)
            )
        } else {
            // Просто большой треугольник, без круга/подложки
            Icon(
                imageVector = Icons.Rounded.PlayArrow,
                contentDescription = "Listen",
                tint = accent,
                modifier = Modifier.size(124.dp)
            )
        }
    }
}

/** Приветствие по времени суток — для idle-состояния волны. */
private fun greetingForNow(): String =
    when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
        in 5..11 -> "Good morning"
        in 12..16 -> "Good afternoon"
        in 17..22 -> "Good evening"
        else -> "Late night waves"
    }

/**
 * Сглаженный бас 0..1 — каскад «огибающая с раздельной атакой/спадом + low-pass»,
 * как у ауры-дыма. Источник — [AudioReactor] (кормится ТОЛЬКО из ExoPlayer-цепочки,
 * т.е. стриминга; на локальном JUCE уровней нет — значение остаётся 0).
 *
 * ANR/энергия: кадровый цикл живёт только пока [playing]; на паузе значение
 * плавно гасится до нуля и цикл ОСТАНАВЛИВАЕТСЯ (никаких вечных покадровых корутин).
 */
@Composable
private fun rememberSmoothedBass(animate: Boolean, playing: Boolean): State<Float> =
    produceState(0f, animate, playing) {
        if (!animate || !playing) {
            var v = value
            while (v > 0.005f) {
                withInfiniteAnimationFrameMillis {
                    v += (0f - v) * 0.12f
                    value = v
                }
            }
            value = 0f
            return@produceState
        }
        var s1 = value
        var s2 = value
        var skip = false
        val halfRate = com.liquidmusicglass.ui.DeviceTier.lite
        while (true) {
            withInfiniteAnimationFrameMillis {
                val target = AudioReactor.low.coerceIn(0f, 1f)
                val rate = if (target > s1) 0.08f else 0.035f
                s1 += ((target - s1) * rate).coerceIn(-0.022f, 0.022f)
                s2 += (s1 - s2) * 0.15f
                // lite: публикуем через кадр — пульс/кромки перерисовываются на ~30 Гц.
                if (!halfRate || !skip) value = s2
                skip = !skip
            }
        }
    }

/**
 * Одна строка синхронного текста под именем артиста. Загрузка/парсинг — на
 * IO/кэше LyricsParser (тот же путь, что LyricsSheet), main не блокируется.
 * Высота фиксированная — hero не прыгает, когда строка появляется/исчезает.
 */
@Composable
private fun CurrentLyricLine(track: Track) {
    val context = LocalContext.current
    var lyrics by remember(track.id) { mutableStateOf(LyricsParser.getCachedLyrics(track.id)) }
    LaunchedEffect(track.id) {
        if (lyrics == null) {
            lyrics = withContext(Dispatchers.IO) {
                runCatching {
                    LyricsParser.loadLyrics(
                        context = context,
                        uri = track.uri,
                        title = track.title,
                        artist = track.artist,
                        durationMs = track.durationMs,
                        trackId = track.id
                    )
                }.getOrNull()
            }
        }
    }
    val positionMs by PlayerController.currentPositionMs.collectAsState()
    val line = remember(lyrics, positionMs) {
        val l = lyrics
        if (l == null || !l.isSynced || l.lines.isEmpty()) null
        else {
            val i = LyricsParser.findCurrentLine(l, positionMs)
            l.lines.getOrNull(i)?.text?.trim()?.takeIf { it.isNotEmpty() }
        }
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(20.dp)
            .padding(horizontal = 32.dp),
        contentAlignment = Alignment.Center
    ) {
        Crossfade(targetState = line.orEmpty(), label = "lyricLine") { text ->
            if (text.isNotEmpty()) {
                Text(
                    text = text,
                    color = Color.White.copy(alpha = 0.62f),
                    fontSize = 13.sp,
                    fontFamily = AppFontFamily,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

/** Чип быстрого фидбека волны («ещё такого» / «меньше такого»). */
@Composable
private fun WaveFeedbackChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: Color,
    onClick: () -> Unit
) {
    var pressedOnce by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(Color.White.copy(alpha = if (pressedOnce) 0.22f else 0.12f))
            .liquidClickable(pressedScale = LiquidMotion.PressButton) {
                pressedOnce = true
                onClick()
            }
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = tint,
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = AppFontFamily
        )
    }
}

/** «Дальше в волне»: до трёх следующих обложек, тап — перескок на трек. */
@Composable
private fun UpNextRow(upNext: List<Pair<Int, Track>>, onPlay: (Int) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = "Next",
            color = Color.White.copy(alpha = 0.55f),
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = AppFontFamily
        )
        upNext.forEach { (index, t) ->
            AlbumArtImage(
                uri = t.displayArtUri,
                coverUrl = t.coverUrl,
                albumId = t.albumId,
                contentDescription = t.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .liquidClickable(pressedScale = LiquidMotion.PressButton) { onPlay(index) }
            )
        }
    }
}

/**
 * Прогресс трека «жидкостью» в пилюле с названием: пройденная часть заливает
 * пилюлю НА ВСЮ ВЫСОТУ слева направо, правая кромка — живая волна (два слоя
 * с разными фазами + яркая грань). Фаза движется только пока музыка играет
 * (и не в энергосбережении) — видно, что трек идёт.
 *
 * Позиция собирается ЗДЕСЬ (не в родителе), чтобы тики позиции перерисовывали
 * только этот маленький Canvas, а не весь hero.
 */
@Composable
private fun WaveProgressFill(
    durationMs: Long,
    accent: Color,
    playing: Boolean,
    animate: Boolean,
    /** Не-null во время перемотки пальцем: вода следует за пальцем мгновенно. */
    overrideProgress: Float? = null,
    modifier: Modifier = Modifier
) {
    val positionMs by PlayerController.currentPositionMs.collectAsState()
    val rawProgress = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    // Позиция приходит тиками (~раз в секунду) — без сглаживания фронт ПРЫГАЕТ
    // на каждый тик («звук толкает пилюлю»). Линейная интерполяция чуть длиннее
    // тика — фронт скользит непрерывно, зависит только от времени трека.
    val animated by animateFloatAsState(
        targetValue = rawProgress,
        animationSpec = tween(1200, easing = LinearEasing),
        label = "waveProgress"
    )
    val progress = overrideProgress ?: animated

    // Фаза течения: ~2.6 рад/с — волна спокойно ПЛЫВЁТ сама, с постоянной
    // скоростью, от музыки не зависит. Кадровый цикл живёт ТОЛЬКО пока
    // playing && animate — на паузе вода замирает, корутина стоит.
    val phase = produceState(0f, playing, animate) {
        if (!playing || !animate) return@produceState
        var last = -1L
        while (true) {
            withInfiniteAnimationFrameMillis { t ->
                if (last >= 0) value = (value + (t - last) * 0.0026f) % (WAVE_TAU * 840f)
                last = t
            }
        }
    }

    val base = lerp(accent, Color.White, 0.30f)
    Canvas(modifier) {
        if (size.width <= 1f || progress <= 0.002f) return@Canvas
        // Лёгкое покачивание уровня (±1.5dp, медленное) — вода «дышит» сама.
        val split = size.width * progress + 1.5.dp.toPx() * sin(phase.value * 0.45f)
        val waveLen = 30.dp.toPx()      // вертикальный период волны кромки
        val step = 3.dp.toPx()
        val h = size.height

        // Заливка от левого края до волнистой вертикальной кромки на split.
        fun liquid(amp: Float, ph: Float) = Path().apply {
            moveTo(0f, 0f)
            lineTo(split + amp * sin(-ph), 0f)
            var y = 0f
            while (y < h) {
                y = min(y + step, h)
                lineTo(split + amp * sin(y / waveLen * WAVE_TAU - ph), y)
            }
            lineTo(0f, h)
            close()
        }

        // Два слоя воды в ПРОТИВОФАЗЕ (плывут навстречу) — живая вода с глубиной.
        drawPath(liquid(5.dp.toPx(), phase.value), base.copy(alpha = 0.34f))
        drawPath(liquid(8.dp.toPx(), -phase.value * 0.7f + 1.7f), base.copy(alpha = 0.16f))

        // Яркая грань передней волны — читаемый «уровень» прогресса.
        val edgeAmp = 5.dp.toPx()
        val edge = Path().apply {
            moveTo(split + edgeAmp * sin(-phase.value), 0f)
            var y = 0f
            while (y < h) {
                y = min(y + step, h)
                lineTo(split + edgeAmp * sin(y / waveLen * WAVE_TAU - phase.value), y)
            }
        }
        drawPath(
            edge,
            color = lerp(accent, Color.White, 0.6f).copy(alpha = 0.85f),
            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
        )
    }
}

private val WAVE_TAU = (2.0 * PI).toFloat()

// Бледно-зелёный акцент волны (заменил жёлтый — цвет Яндекса убран).
private val WaveAccent = Color(0xFF88C088)
