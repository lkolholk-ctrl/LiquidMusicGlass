package com.liquidmusicglass.ui.lyrics

import android.net.Uri
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.liquidmusicglass.engine.LyricsFxController
import com.liquidmusicglass.engine.LyricsParser
import com.liquidmusicglass.engine.LyricsSyncStore
import com.liquidmusicglass.engine.PlayerController
import com.liquidmusicglass.ui.glass.AlbumArtImage
import com.liquidmusicglass.ui.glass.AlbumColors
import com.liquidmusicglass.ui.glass.rememberAlbumColors
import com.liquidmusicglass.ui.theme.AppFontFamily
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.PlatformTextStyle
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min


/** Насколько высоко поднимать активную строку: доля высоты экрана от верха (0.25–0.35 — верхняя треть). */
private const val ACTIVE_LINE_TOP_BIAS = 0.28f

/** Высота зоны заголовка со скрим-градиентом (плотная часть закрывает название+артиста). */
private val HEADER_SCRIM_HEIGHT = 170.dp

/** Пауза автоследования после ручного скролла: пользователь читает текст —
 *  не дёргаем список обратно, возвращаемся к активной строке через этот таймаут. */
private const val USER_SCROLL_PAUSE_MS = 4000L

/**
 * Полноэкранный караоке-экран лирики (Apple Music style).
 *
 * Фичи:
 * - Strict left alignment — все строки строго по левому краю, никаких staggered offsets
 * - Text containment — текст никогда не вылезает за края экрана
 * - Fluid gliding scroll — плавный spring-скролл без рывков
 * - Character-level fluid color bleed — посимвольное плавное закрашивание
 * - HSV-boosted фон с blur + scrim
 */
@Composable
fun LyricsScreen(
    audioFileUri: Uri?,
    lrcText: String?,
    currentPositionMs: Long,
    trackTitle: String = "",
    trackArtist: String = "",
    trackDurationMs: Long = 0L,
    albumArtUri: Uri? = null,
    coverUrl: String? = null,
    albumId: Long = -1L,
    trackId: String? = null,
    albumColors: AlbumColors? = null,
    onRequestControls: () -> Unit = {},
    onClose: () -> Unit = {},
    // Split-режим (альбомная ориентация, правая половина): СВОЙ фон не рисуем —
    // общий фон плеера уже под нами (иначе жёсткий вертикальный шов-«полоса»).
    splitMode: Boolean = false
) {
    val context = LocalContext.current
    val resolvedColors = albumColors ?: rememberAlbumColors(albumArtUri, coverUrl)

    val resolvedTrackId = remember(trackId, audioFileUri) {
        trackId ?: run {
            val path = audioFileUri?.toString() ?: ""
            when {
                path.startsWith("https://byicloud.online/track/") ->
                    path.removePrefix("https://byicloud.online/track/").takeWhile { it != '?' }
                else -> audioFileUri?.lastPathSegment ?: ""
            }
        }
    }

    // ── Lyrics loading ──
    val cachedLyrics = remember(resolvedTrackId) {
        LyricsParser.getCachedLyrics(resolvedTrackId)
    }

    var lyrics by remember { mutableStateOf(cachedLyrics ?: LyricsParser.Lyrics.EMPTY) }
    var isLoading by remember { mutableStateOf(cachedLyrics == null && lrcText.isNullOrBlank()) }

    LaunchedEffect(audioFileUri, lrcText, trackTitle, trackArtist, resolvedTrackId) {
        if (!lrcText.isNullOrBlank()) {
            lyrics = withContext(Dispatchers.Default) {
                LyricsParser.parseLyrics(lrcText)
            }
            isLoading = false
            return@LaunchedEffect
        }

        LyricsParser.getCachedLyrics(resolvedTrackId)?.let {
            lyrics = it
            isLoading = false
            return@LaunchedEffect
        }

        isLoading = true
        lyrics = withContext(Dispatchers.IO) {
            LyricsParser.loadLyrics(
                context = context,
                uri = audioFileUri,
                title = trackTitle,
                artist = trackArtist,
                durationMs = trackDurationMs,
                trackId = resolvedTrackId
            )
        }
        isLoading = false
    }

    // ── Time processor for line-level sync ──
    val timeProcessor = remember(lyrics) {
        if (lyrics.lines.isNotEmpty()) LyricsTimeProcessor(lyrics) else null
    }

    // Reset processor when track changes
    LaunchedEffect(resolvedTrackId) {
        timeProcessor?.reset()
    }

    val isInterlude by timeProcessor?.isInterlude?.collectAsState() ?: remember { mutableStateOf(false) }
    val interludeProgress by timeProcessor?.interludeProgress?.collectAsState()
        ?: remember { mutableFloatStateOf(0f) }

    // ── Ручная подстройка синхры (± мс, память на трек) ──
    // Лечит кривые таймкоды источника: + = лирика раньше, − = позже.
    var syncOffsetMs by remember(resolvedTrackId) {
        mutableLongStateOf(LyricsSyncStore.get(context, resolvedTrackId))
    }
    var syncUiOpen by remember { mutableStateOf(false) }
    // Долгий тап по строке лирики → карточка «поделиться» (как у Apple).
    var shareLine by remember { mutableStateOf<String?>(null) }
    fun adjustSync(deltaMs: Long) {
        syncOffsetMs = (syncOffsetMs + deltaMs).coerceIn(-10_000L, 10_000L)
        LyricsSyncStore.set(context, resolvedTrackId, syncOffsetMs)
        // Сброс процессора: монотонный курсор не пускает позицию назад,
        // без сброса сдвиг «−» применился бы только на следующей строке.
        timeProcessor?.reset()
    }

    // ── Smooth 60/120 FPS position ticker ──
    val isPlaying by PlayerController.isPlaying.collectAsState()
    var smoothPositionMs by remember { mutableLongStateOf(0L) }

    // Sync with coarse position only when paused — во время игры позицией
    // владеет покадровый тикер (getSmoothPositionMs), иначе грубые апдейты
    // дёргали бы плавный sweep.
    LaunchedEffect(currentPositionMs, syncOffsetMs) {
        if (!isPlaying) {
            smoothPositionMs = currentPositionMs
            timeProcessor?.updatePosition(currentPositionMs + syncOffsetMs)
        }
    }

    // High-frequency frame-synced ticker for butter-smooth animation.
    // Цикл живёт всё время (не пересоздаётся на смене isPlaying) — состояние
    // воспроизведения проверяется ВНУТРИ кадра, чтобы не терять кадры на
    // рестарте корутины при паузе/возобновлении.
    //
    // ВАЖНО: тикер запускается с ключом Unit и держит первую лямбду, поэтому
    // напрямую он бы замкнул timeProcessor на момент старта (часто ещё null,
    // пока лирика грузится). rememberUpdatedState даёт всегда СВЕЖУЮ ссылку на
    // процессор — иначе закрас не полз бы с первого открытия.
    val currentProcessor by rememberUpdatedState(timeProcessor)

    LaunchedEffect(Unit) {
        while (isActive) {
            withFrameMillis { _ ->
                if (PlayerController.isPlaying.value) {
                    smoothPositionMs = PlayerController.getSmoothPositionMs()
                    // syncOffsetMs — state: тикер всегда читает свежий сдвиг.
                    currentProcessor?.updatePosition(smoothPositionMs + syncOffsetMs)
                }
            }
        }
    }

    val currentLineIndex by timeProcessor?.currentLineIndex?.collectAsState() ?: remember { mutableIntStateOf(-1) }

    // Подсветка идёт целыми строками. Заливка внутри строки считалась по
    // эвристике «миллисекунд на символ» — это была догадка, а не тайминги, и
    // на распевах и быстром речитативе она заметно врала. Вернём посимвольную
    // заливку, когда ICM отдаст пословные тайминги; движок под неё цел.
    val lineEffect = LyricsFxController.WordEffect.FILL
    val sungTweenMs = 180

    // Waiting считает сам LyricsTimeProcessor (сегментная модель): строка докрашена
    // ПОЛНОСТЬЮ + до следующей строки реальный разрыв > WAIT_GAP_MS. VAD выключен.
    val showWaiting = isInterlude

    // ── Auto-scroll с fluid gliding ──
    val listState = rememberLazyListState()
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val screenHeightPx = with(density) { configuration.screenHeightDp.dp.toPx() }
    // Доступная ширина строки лирики. В split (альбом/планшет) лирика живёт в
    // ПРАВОЙ половине — считаем от неё, иначе текст рассчитан на полный экран и
    // обрезается справа. Плюс левый отступ меньше (текст ближе к обложке-шву).
    val lineHPadding = if (splitMode) 16.dp else 24.dp
    val lyricColumnWidthDp = if (splitMode) (configuration.screenWidthDp * 0.5f) else configuration.screenWidthDp.toFloat()
    val lineMaxWidthPx = with(density) { (lyricColumnWidthDp.dp - lineHPadding * 2).toPx().toInt() }
    // В узкой split-колонке шрифт строк меньше, чтобы влезал без обрезки.
    val lineFontScale = if (splitMode) 0.66f else 1f
    // Мягкий край нужен движущемуся фронту заливки; при построчной подсветке
    // фронта нет — строка загорается целиком.
    val edgeSoftPx = 0f

    // Ручной скролл (drag) ставит автоследование на паузу — фиксируем момент касания.
    var userScrolledAt by remember { mutableLongStateOf(0L) }
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start) {
                userScrolledAt = System.currentTimeMillis()
            }
        }
    }

    LaunchedEffect(currentLineIndex, userScrolledAt) {
        if (currentLineIndex >= 0) {
            // Если пользователь недавно листал руками — ждём остаток паузы,
            // потом плавно возвращаемся (новый drag перезапустит эффект и ожидание).
            val sinceTouch = System.currentTimeMillis() - userScrolledAt
            if (sinceTouch < USER_SCROLL_PAUSE_MS) delay(USER_SCROLL_PAUSE_MS - sinceTouch)
            // Поднимаем активную строку в верхнюю треть (см. ACTIVE_LINE_TOP_BIAS).
            // lineToItem: нулевой элемент списка — распорка шапки, поэтому индекс
            // строки и индекс элемента не совпадают. Без сдвига список наводился
            // на ПРЕДЫДУЩУЮ строку, и подсвеченная всегда стояла ниже якоря.
            val lineIndex = currentLineIndex.coerceAtMost((lyrics.lines.size - 1).coerceAtLeast(0))
            val targetIndex = lineIndex + 1
            // Высоту берём у самого списка, а не у экрана: в split и с вырезами
            // экранная высота не равна видимой области, и якорь промахивается.
            val viewportPx = listState.layoutInfo.viewportSize.height
                .takeIf { it > 0 }?.toFloat() ?: screenHeightPx
            val aboveCenterOffset = (viewportPx * ACTIVE_LINE_TOP_BIAS).toInt()
            listState.animateScrollToItem(
                index = targetIndex,
                scrollOffset = -aboveCenterOffset
            )
        }
    }

    // ── Duet detection ──
    val isDuet = remember(lyrics) {
        lyrics.lines.any { line ->
            line.text.contains(Regex("""\[(M|F|D|Male|Female|Duet):?\s*""", RegexOption.IGNORE_CASE))
        }
    }

    // ── Colors ──
    // Фон здесь всегда из обложки, а не из темы приложения, поэтому цвет текста
    // выбираем по яркости фона. Раньше он был белым намертво, и на светлой
    // обложке (фон поднимается почти до максимальной яркости) выходило белое по
    // белому — экран лирики про светлую тему не знал вовсе.
    val lyricInk = remember(resolvedColors.vibrant) {
        if (boostedCoverColor(resolvedColors.vibrant).luminance() > 0.55f) {
            Color(0xFF101014)
        } else {
            Color.White
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onRequestControls
            )
    ) {
        // ═══ Background layers ═══
        if (!splitMode) {
            LyricsBackground(
                albumArtUri = albumArtUri,
                coverUrl = coverUrl,
                audioFileUri = audioFileUri,
                albumId = albumId,
                albumColors = resolvedColors,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            // Split: только мягкий скрим для читаемости, левая кромка
            // растушёвана — никакого шва с обложкой слева.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.horizontalGradient(
                            0.00f to Color.Transparent,
                            0.14f to Color.Black.copy(alpha = 0.30f),
                            1.00f to Color.Black.copy(alpha = 0.42f)
                        )
                    )
            )
        }

        // ═══ Content ═══
        Box(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars)
                .windowInsetsPadding(WindowInsets.navigationBars)
        ) {
            when {
                isLoading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            color = lyricInk.copy(alpha = 0.5f),
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                lyrics.lines.isEmpty() -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No lyrics available",
                            color = lyricInk.copy(alpha = 0.5f),
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                else -> {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.Start
                    ) {
                        // Header spacer
                        item { Spacer(Modifier.height(100.dp)) }

                        // Waiting dots before first line starts
                        if (lyrics.isSynced && currentLineIndex < 0) {
                            item {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 40.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    WaitingDots(dotColor = lyricInk, progress = interludeProgress)
                                }
                            }
                        }

                        itemsIndexed(lyrics.lines) { index, line ->
                            // Несинхронная лирика — обычный текст: мельче и кучнее
                            // (32sp-строки с воздухом рассчитаны на караоке-свип,
                            // без таймкодов они просто раздувают простыню).
                            if (!lyrics.isSynced) {
                                Text(
                                    text = line.text,
                                    color = lyricInk.copy(alpha = 0.82f),
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.Medium,
                                    fontFamily = AppFontFamily,
                                    lineHeight = 24.sp,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 24.dp, vertical = 4.dp)
                                )
                                return@itemsIndexed
                            }

                            val isCurrent = index == currentLineIndex
                            val isPast = index < currentLineIndex

                            // duet-цвет
                            val duetColor = when {
                                !isDuet -> null
                                line.text.startsWith("[M", ignoreCase = true) ||
                                    line.text.contains(Regex("""^\[Male""", RegexOption.IGNORE_CASE)) -> Color(0xFF4FC3F7)
                                line.text.startsWith("[F", ignoreCase = true) ||
                                    line.text.contains(Regex("""^\[Female""", RegexOption.IGNORE_CASE)) -> Color(0xFFF48FB1)
                                line.text.startsWith("[D", ignoreCase = true) -> Color(0xFFFFF176)
                                else -> null
                            }
                            val cleanText = line.text.replace(
                                Regex("""\[(M|F|D|Male|Female|Duet):?\s*""", RegexOption.IGNORE_CASE), ""
                            )

                            // Построчная подсветка: активная строка горит целиком,
                            // спетое остаётся приглушённым, будущее не залито.
                            val fillProgress = when {
                                isPast || isCurrent -> 1f
                                else -> 0f
                            }

                            val base = duetColor ?: lyricInk

                            // Глубина списка: чем дальше строка от активной, тем сильнее
                            // растворяется (ближние читаемы, дальние — «туман»). До первой
                            // строки градации нет — весь текст ровный.
                            // Считаем напрямую: раньше на КАЖДУЮ строку висел свой
                            // animateFloatAsState, то есть десятки параллельных
                            // анимаций на один список. Плавность перехода даёт
                            // анимация цвета ниже, а отдельная анимация глубины
                            // только грузила прокрутку.
                            val dist = if (currentLineIndex >= 0) abs(index - currentLineIndex) else 1
                            val depth = if (isCurrent || currentLineIndex < 0) 1f
                                else (1f - 0.13f * (dist - 1)).coerceAtLeast(0.45f)

                            val sungColor by animateColorAsState(
                                targetValue = base.copy(alpha = if (isCurrent) 1f else 0.55f * depth),
                                animationSpec = tween(durationMillis = sungTweenMs),
                                label = "sung"
                            )
                            val unsungColor = base.copy(alpha = 0.30f * depth)

                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    // Тап по строке = перемотка на неё (Apple Music).
                                    // Только для синхронной лирики.
                                    .then(
                                        // Тап = перемотка на строку; долгий тап =
                                        // карточка «поделиться» (Apple Music).
                                        if (lyrics.isSynced) Modifier.pointerInput(line.timeMs) {
                                            detectTapGestures(
                                                onTap = { PlayerController.seekTo(line.timeMs) },
                                                onLongPress = { shareLine = cleanText }
                                            )
                                        }
                                        else Modifier.pointerInput(line.text) {
                                            detectTapGestures(onLongPress = { shareLine = cleanText })
                                        }
                                    )
                                    .padding(horizontal = lineHPadding, vertical = if (splitMode) 6.dp else 10.dp),
                                horizontalAlignment = Alignment.Start
                            ) {
                                LyricLineSweep(
                                    text = cleanText,
                                    fillProgress = fillProgress,
                                    sungColor = sungColor,
                                    unsungColor = unsungColor,
                                    isActive = isCurrent,
                                    maxWidthPx = lineMaxWidthPx,
                                    glowColor = duetColor ?: resolvedColors.vibrant,
                                    effect = lineEffect,
                                    edgeSoftPx = if (isCurrent) edgeSoftPx else 0f,
                                    fontSizeSp = 32f * lineFontScale
                                )
                                // Точки ожидания во время инструментального проигрыша
                                // (сегментная модель LyricsTimeProcessor, VAD не используется).
                                // progress: точки наливаются по мере проигрыша и схлопываются
                                // перед возвратом вокала.
                                if (isCurrent && showWaiting) {
                                    Spacer(Modifier.height(16.dp))
                                    Box(
                                        modifier = Modifier.fillMaxWidth(),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        WaitingDots(
                                            dotColor = duetColor ?: lyricInk,
                                            progress = interludeProgress
                                        )
                                    }
                                }
                            }
                        }

                        // Нижняя распорка. 200dp не хватало: под лирикой лежит
                        // растушёвка высотой 460dp, и последние строки физически
                        // нельзя было поднять из-под неё — они дочитывались уже
                        // в затемнении.
                        item { Spacer(Modifier.height((configuration.screenHeightDp * 0.42f).dp)) }
                    }
                }
            }

            // ── Градиент-скрим под заголовком ──
            // Плотный от самого верха (под статусбаром) → прозрачный вниз.
            // Цвет — тот же НАСЫЩЕННЫЙ цвет обложки, что и фон лирики
            // (boostedCoverColor от vibrant), никакого серого/чёрного.
            val headerScrimColor = boostedCoverColor(resolvedColors.vibrant)
            if (!splitMode) Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(HEADER_SCRIM_HEIGHT)
                    .align(Alignment.TopCenter)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                headerScrimColor.copy(alpha = 0.92f),
                                headerScrimColor.copy(alpha = 0.65f),
                                Color.Transparent
                            )
                        )
                    )
            )

            // ── Header: track info ──
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = trackTitle,
                    color = lyricInk,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = trackArtist,
                    color = lyricInk.copy(alpha = 0.6f),
                    fontSize = 13.sp,
                    maxLines = 1
                )
            }

            // ── Подстройка синхры: бейдж SYNC в правом верхнем углу ──
            // Тап раскрывает чипы −0.5s / +0.5s / Reset; сдвиг хранится на трек.
            if (lyrics.isSynced) {
                // Авто-скрытие чипов через 6с бездействия.
                LaunchedEffect(syncUiOpen, syncOffsetMs) {
                    if (syncUiOpen) {
                        delay(6000)
                        syncUiOpen = false
                    }
                }
                Column(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 12.dp, end = 12.dp),
                    horizontalAlignment = Alignment.End
                ) {
                    val badgeActive = syncUiOpen || syncOffsetMs != 0L
                    Text(
                        text = if (syncOffsetMs == 0L) "SYNC"
                               else "SYNC %+.1fs".format(syncOffsetMs / 1000f),
                        color = Color.White.copy(alpha = if (badgeActive) 0.95f else 0.45f),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color.Black.copy(alpha = 0.35f))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) { syncUiOpen = !syncUiOpen }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                    if (syncUiOpen) {
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SyncChip("−0.5s") { adjustSync(-500L) }
                            SyncChip("+0.5s") { adjustSync(+500L) }
                            SyncChip("Reset") { adjustSync(-syncOffsetMs) }
                        }
                    }
                }
            }
        }

        // Оверлей «поделиться строкой лирики» (долгий тап по строке).
        shareLine?.let { txt ->
            LyricShareOverlay(
                lineText = txt,
                trackTitle = trackTitle,
                trackArtist = trackArtist,
                albumArtUri = albumArtUri,
                coverUrl = coverUrl,
                albumColors = resolvedColors,
                onDismiss = { shareLine = null }
            )
        }
    }
}

/** Чип панели подстройки синхры лирики. */
@Composable
private fun SyncChip(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        color = Color.White,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 14.dp, vertical = 8.dp)
    )
}

/**
 * Line-level караоке-sweep v2 — корректно под перенос строк.
 *
 * Рисуем текст дважды: снизу inactive, сверху active, обрезанный [clipPath]
 * по визуальным рядам в порядке чтения (пройденные ряды — целиком, текущий —
 * до курсора, будущие — пусто). Одна непрерывная волна, без «подстрок».
 */
@Composable
internal fun LyricLineSweep(
    text: String,
    fillProgress: Float,
    sungColor: Color,
    unsungColor: Color,
    isActive: Boolean,
    maxWidthPx: Int,
    glowColor: Color,
    effect: LyricsFxController.WordEffect = LyricsFxController.WordEffect.FILL,
    edgeSoftPx: Float = 0f,
    // Размер строки: в узкой split-колонке (альбом/планшет) меньше 32.
    fontSizeSp: Float = 32f
) {
    if (text.isEmpty()) return

    val measurer = rememberTextMeasurer()
    // Стиль ОДИН для активной и неактивной строки: смена 30↔32sp была
    // перевёрсткой (другие переносы, прыжок высоты — дёргался весь список).
    // «Укрупнение» активной строки теперь чисто визуальное — spring-scale
    // через graphicsLayer ниже, вёрстка не меняется никогда.
    val style = TextStyle(
        fontSize = fontSizeSp.sp,
        fontWeight = FontWeight.Bold,
        fontFamily = AppFontFamily,
        lineHeight = (fontSizeSp * 1.375f).sp,
        textAlign = TextAlign.Start,
        platformStyle = PlatformTextStyle(includeFontPadding = false)
    )

    val layout = remember(text, style, maxWidthPx) {
        measurer.measure(
            text = text,
            style = style,
            constraints = Constraints(maxWidth = maxWidthPx),
            maxLines = 3,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
        )
    }

    val p = fillProgress.coerceIn(0f, 1f)
    val wDp = with(LocalDensity.current) { layout.size.width.toDp() }
    val hDp = with(LocalDensity.current) { layout.size.height.toDp() }

    // «Дыхание» активной строки: неактивные визуально ~30sp (32 × 0.94),
    // активная плавно вырастает до 100%. От левого края — без бокового дрейфа.
    val lineScale by animateFloatAsState(
        targetValue = if (isActive) 1f else 0.94f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "lineScale"
    )

    Canvas(
        modifier = Modifier
            .size(wDp, hDp)
            .graphicsLayer {
                scaleX = lineScale
                scaleY = lineScale
                transformOrigin = TransformOrigin(0f, 0.5f)
            }
    ) {
        // 0) гло-подсветка активной строки: цвет облегает текст. Блюр УЗКИЙ —
        //    свечение хватает текст вплотную, без широких колец вокруг круглых букв.
        if (isActive) {
            drawText(
                textLayoutResult = layout,
                color = glowColor.copy(alpha = 0.50f),
                shadow = androidx.compose.ui.graphics.Shadow(
                    color = glowColor.copy(alpha = 0.50f),
                    offset = Offset(0f, 0f),
                    blurRadius = 6f
                )
            )
        }
        // 1) база — весь текст неактивным цветом
        drawText(layout, color = unsungColor)
        if (p <= 0f) return@Canvas

        // 2) активный текст, обрезанный по «спетой» области в порядке чтения.
        //    Заливка считается НЕПРЕРЫВНО по суммарной ширине рядов (sub-pixel),
        //    а не по целым символам — поэтому плавно, без «лесенки».
        if (p >= 1f) {
            drawText(layout, color = sungColor)
            return@Canvas
        }

        var totalWidth = 0f
        for (i in 0 until layout.lineCount) {
            totalWidth += layout.getLineRight(i) - layout.getLineLeft(i)
        }
        val swept = p * totalWidth

        // FILL без мягкого края — ПРЕЖНИЙ жёсткий sweep (line-level не меняется).
        if (effect == LyricsFxController.WordEffect.FILL && edgeSoftPx <= 0f) {
            val clip = Path()
            var acc = 0f
            for (i in 0 until layout.lineCount) {
                val left = layout.getLineLeft(i)
                val right = layout.getLineRight(i)
                val top = layout.getLineTop(i)
                val bottom = layout.getLineBottom(i)
                val w = right - left
                when {
                    swept >= acc + w -> clip.addRect(Rect(left, top, right, bottom))
                    swept <= acc -> { }
                    else -> clip.addRect(Rect(left, top, left + (swept - acc), bottom))
                }
                acc += w
            }
            clipPath(clip) { drawText(layout, color = sungColor) }
            return@Canvas
        }

        // Мягкий край / эффекты (word-level): построчно с фезером у курсора.
        val soft = (if (effect == LyricsFxController.WordEffect.FADE) edgeSoftPx * 2.2f else edgeSoftPx)
            .coerceAtLeast(1f)
        var acc = 0f
        for (i in 0 until layout.lineCount) {
            val left = layout.getLineLeft(i)
            val right = layout.getLineRight(i)
            val top = layout.getLineTop(i)
            val bottom = layout.getLineBottom(i)
            val w = right - left
            val rowStart = acc
            val rowEnd = acc + w
            when {
                swept >= rowEnd ->
                    clipRect(left, top, right, bottom) { drawText(layout, color = sungColor) }
                swept <= rowStart -> { /* ряд ещё не начат */ }
                else -> {
                    val edge = left + (swept - rowStart)            // курсор в ряду
                    val hard = (edge - soft).coerceAtLeast(left)
                    if (hard > left) clipRect(left, top, hard, bottom) { drawText(layout, color = sungColor) }
                    if (edge > hard) {
                        val feather = Brush.horizontalGradient(
                            0f to sungColor, 1f to sungColor.copy(alpha = 0f),
                            startX = hard, endX = edge
                        )
                        clipRect(hard, top, edge, bottom) { drawText(layout, brush = feather) }
                    }
                    if (effect == LyricsFxController.WordEffect.RUNNING) {
                        val gL = (edge - soft * 0.7f).coerceAtLeast(left)
                        val gR = (edge + soft * 0.5f).coerceAtMost(right)
                        if (gR > gL) {
                            val glow = Brush.horizontalGradient(
                                0f to glowColor.copy(alpha = 0f),
                                0.5f to glowColor.copy(alpha = 0.85f),
                                1f to glowColor.copy(alpha = 0f),
                                startX = gL, endX = gR
                            )
                            clipRect(gL, top, gR, bottom) { drawText(layout, brush = glow) }
                        }
                    }
                }
            }
            acc += w
        }
    }
}

