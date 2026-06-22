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
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
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
import com.liquidmusicglass.engine.LyricsParser
import com.liquidmusicglass.engine.PlayerController
import com.liquidmusicglass.ui.glass.AlbumArtImage
import com.liquidmusicglass.ui.glass.AlbumColors
import com.liquidmusicglass.ui.glass.rememberAlbumColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.PlatformTextStyle
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

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
    onClose: () -> Unit = {}
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

    // ── Smooth 60/120 FPS position ticker ──
    val isPlaying by PlayerController.isPlaying.collectAsState()
    var smoothPositionMs by remember { mutableLongStateOf(0L) }

    // Sync with coarse position when it changes (seek, track change)
    LaunchedEffect(currentPositionMs) {
        smoothPositionMs = currentPositionMs
        timeProcessor?.updatePosition(smoothPositionMs)
    }

    // High-frequency frame-synced ticker for butter-smooth animation
    LaunchedEffect(isPlaying) {
        if (isPlaying) {
            while (isActive) {
                withFrameMillis { _ ->
                    smoothPositionMs = PlayerController.getSmoothPositionMs()
                    timeProcessor?.updatePosition(smoothPositionMs)
                }
            }
        }
    }

    val currentLineIndex by timeProcessor?.currentLineIndex?.collectAsState() ?: remember { mutableIntStateOf(-1) }
    val currentLineProgress by timeProcessor?.currentLineProgress?.collectAsState() ?: remember { mutableFloatStateOf(0f) }

    // Получаем слова текущей строки для пословного караоке
    val currentWords = remember(currentLineIndex, smoothPositionMs) {
        timeProcessor?.getCurrentLineWords() ?: emptyList()
    }

    // ── Auto-scroll с fluid gliding ──
    val listState = rememberLazyListState()
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val screenHeightPx = with(density) { configuration.screenHeightDp.dp.toPx() }

    LaunchedEffect(currentLineIndex) {
        if (currentLineIndex >= 0) {
            // Поднимаем активную строку заметно ВЫШЕ центра (~18% от высоты)
            val targetIndex = currentLineIndex.coerceAtMost((lyrics.lines.size - 1).coerceAtLeast(0))
            val aboveCenterOffset = (screenHeightPx * 0.18f - with(density) { 40.dp.toPx() }).toInt()
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
    val boostedVibrant = remember(resolvedColors.vibrant) {
        timeProcessor?.boostSaturation(resolvedColors.vibrant) ?: resolvedColors.vibrant
    }
    val boostedMuted = remember(resolvedColors.muted) {
        timeProcessor?.boostSaturation(resolvedColors.muted) ?: resolvedColors.muted
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
        LyricsBackground(
            albumArtUri = albumArtUri,
            coverUrl = coverUrl,
            audioFileUri = audioFileUri,
            albumId = albumId,
            albumColors = resolvedColors,
            modifier = Modifier.fillMaxSize()
        )

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
                            color = Color.White.copy(alpha = 0.5f),
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
                            color = Color.White.copy(alpha = 0.5f),
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
                        if (lyrics.isSynced && smoothPositionMs < lyrics.lines.firstOrNull()?.timeMs ?: 0L) {
                            item {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 40.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    WaitingDots(dotColor = Color.White)
                                }
                            }
                        }

                        itemsIndexed(lyrics.lines) { index, line ->
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

                            // прогресс заливки: прошлое = залито, текущая = sweep, будущее = пусто
                            val fillProgress = when {
                                isPast -> 1f
                                isCurrent -> currentLineProgress
                                else -> 0f
                            }

                            val base = duetColor ?: Color.White
                            // короткий tween (180мс) — без двойного свечения соседних строк
                            val sungColor by animateColorAsState(
                                targetValue = base.copy(alpha = if (isCurrent) 1f else 0.55f),
                                animationSpec = tween(durationMillis = 180),
                                label = "sung"
                            )
                            val unsungColor = base.copy(alpha = 0.30f)

                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 24.dp, vertical = 10.dp),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                LyricLineSweep(
                                    text = cleanText,
                                    fillProgress = fillProgress,
                                    sungColor = sungColor,
                                    unsungColor = unsungColor,
                                    isActive = isCurrent
                                )
                            }
                        }

                        // Bottom spacer
                        item { Spacer(Modifier.height(200.dp)) }
                    }
                }
            }

            // ── Header: track info ──
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = trackTitle,
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = trackArtist,
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 13.sp,
                    maxLines = 1
                )
            }
        }
    }
}

/**
 * Line-level караоке-sweep: одна движущаяся граница заливки по строке
 * (от [fillProgress]). Без длинных кросс-фейдов — в кадре ровно одна
 * активная строка, без «лесенки» и без двойного свечения соседних.
 */
@Composable
private fun LyricLineSweep(
    text: String,
    fillProgress: Float,
    sungColor: Color,
    unsungColor: Color,
    isActive: Boolean
) {
    val p = fillProgress.coerceIn(0f, 1f)
    val fade = 0.045f // мягкий край заливки (доля ширины строки)

    val brush = remember(p, sungColor, unsungColor) {
        when {
            p <= 0f -> SolidColor(unsungColor)
            p >= 1f -> SolidColor(sungColor)
            else -> {
                val a = (p - fade).coerceIn(0f, 1f)
                val b = (p + fade).coerceIn(0f, 1f)
                val stops = listOf(
                    0f to sungColor,
                    a to sungColor,
                    b to unsungColor,
                    1f to unsungColor
                ).distinctBy { it.first }.sortedBy { it.first }
                Brush.horizontalGradient(colorStops = stops.toTypedArray())
            }
        }
    }

    Text(
        text = text,
        style = TextStyle(
            brush = brush,
            fontSize = if (isActive) 32.sp else 30.sp,
            fontWeight = if (isActive) FontWeight.Bold else FontWeight.SemiBold,
            lineHeight = 44.sp,
            textAlign = TextAlign.Start,
            shadow = androidx.compose.ui.graphics.Shadow(
                color = Color.Black.copy(alpha = 0.85f),
                offset = Offset(0f, 2f),
                blurRadius = 8f
            ),
            platformStyle = PlatformTextStyle(includeFontPadding = false)
        ),
        maxLines = 3,
        softWrap = true,
        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
    )
}

/**
 * ПОСИМВОЛЬНОЕ караоке с fluid color bleed — Apple Music style.
 *
 * Каждый символ плавно переходит от inactive к active через точный
 * horizontalGradient с colorStops на физических границах символов.
 *
 * Масштабирование активной строки: transformOrigin = (0f, 0.5f) —
 * строго от левого края, никакого horizontal jitter.
 *
 * @param text полный текст строки
 * @param words список слов с прогрессом (0f..1f)
 * @param activeColor цвет активной части
 * @param inactiveColor цвет неактивной части
 * @param fontSize размер шрифта
 * @param maxWidthPx максимальная ширина в пикселях
 * @param isActiveLine true для текущей строки (включает scale + bold)
 */
@Composable
private fun KaraokeLineFluid(
    text: String,
    words: List<LyricsTimeProcessor.WordToken>,
    activeColor: Color,
    inactiveColor: Color,
    fontSize: androidx.compose.ui.unit.TextUnit,
    maxWidthPx: Int,
    isActiveLine: Boolean = true
) {
    if (text.isEmpty()) {
        Text(
            text = text,
            color = inactiveColor,
            style = TextStyle(
                fontSize = fontSize,
                fontWeight = FontWeight.Normal,
                textAlign = TextAlign.Start,
                lineHeight = 36.sp
            ),
            modifier = Modifier.fillMaxWidth(),
            maxLines = 3,
            softWrap = true
        )
        return
    }

    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current

    // Активная строка: bold + чуть крупнее; неактивная: normal
    val fontWeight = if (isActiveLine) FontWeight.Bold else FontWeight.Normal
    val effectiveFontSize = if (isActiveLine) fontSize * 1.04f else fontSize

    val textStyle = TextStyle(
        fontSize = effectiveFontSize,
        fontWeight = fontWeight,
        textAlign = TextAlign.Start,
        lineHeight = 36.sp,
        platformStyle = PlatformTextStyle(includeFontPadding = false)
    )

    // Измеряем полный текст
    val layoutResult = remember(text, textStyle, maxWidthPx) {
        textMeasurer.measure(
            text = text,
            style = textStyle,
            constraints = Constraints(maxWidth = maxWidthPx)
        )
    }

    // Карта: индекс символа → прогресс (0f..1f)
    val charProgressMap = remember(text, words) {
        val map = MutableList(text.length) { 0f }
        var charIdx = 0
        for (word in words) {
            while (charIdx < text.length && text[charIdx] == ' ') {
                charIdx++
            }
            for (i in word.text.indices) {
                if (charIdx < text.length) {
                    map[charIdx] = word.progress.coerceIn(0f, 1f)
                    charIdx++
                }
            }
        }
        map
    }

    // Вычисляем colorStops на основе ТОЧНЫХ физических координат символов
    val colorStops = remember(layoutResult, charProgressMap, activeColor, inactiveColor) {
        val stops = mutableListOf<Pair<Float, Color>>()
        val totalWidth = layoutResult.size.width.toFloat().coerceAtLeast(1f)
        val fadePx = 2.5f // 2.5px fade zone для плавного bleed

        for (i in text.indices) {
            val bounds = try {
                layoutResult.getBoundingBox(i)
            } catch (_: Exception) {
                continue
            }

            val charLeft = (bounds.left / totalWidth).coerceIn(0f, 1f)
            val charRight = (bounds.right / totalWidth).coerceIn(0f, 1f)
            val progress = charProgressMap.getOrElse(i) { 0f }

            if (charRight <= charLeft) continue

            // Граница внутри символа с fade zone
            val boundary = charLeft + (charRight - charLeft) * progress
            val fade = (fadePx / totalWidth).coerceAtMost((charRight - charLeft) * 0.25f)

            when {
                progress <= 0f -> {
                    stops.add(charLeft to inactiveColor)
                    stops.add(charRight to inactiveColor)
                }
                progress >= 1f -> {
                    stops.add(charLeft to activeColor)
                    stops.add(charRight to activeColor)
                }
                else -> {
                    // Fluid bleed: active → fade → inactive
                    stops.add(charLeft to activeColor)
                    stops.add((boundary - fade).coerceAtLeast(charLeft) to activeColor)
                    stops.add((boundary + fade).coerceAtMost(charRight) to inactiveColor)
                    stops.add(charRight to inactiveColor)
                }
            }
        }

        // Сортируем, удаляем дубликаты по позиции
        stops.sortBy { it.first }
        val deduped = mutableListOf<Pair<Float, Color>>()
        var lastPos = -1f
        for (stop in stops) {
            if (kotlin.math.abs(stop.first - lastPos) > 0.0001f) {
                deduped.add(stop)
                lastPos = stop.first
            }
        }
        deduped.toTypedArray()
    }

    val brush = remember(colorStops) {
        if (colorStops.size >= 2) {
            Brush.horizontalGradient(colorStops = colorStops)
        } else {
            SolidColor(inactiveColor)
        }
    }

    val canvasWidth = with(density) { layoutResult.size.width.toDp() }
    val canvasHeight = with(density) { layoutResult.size.height.toDp() }

    // Scale для активной строки: ТОЛЬКО от левого края
    val scale by animateFloatAsState(
        targetValue = if (isActiveLine) 1.04f else 1.0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "karaokeScale"
    )

    Canvas(
        modifier = Modifier
            .width(canvasWidth * scale)
            .height(canvasHeight * scale)
            .graphicsLayer {
                this.scaleX = scale
                this.scaleY = scale
                // КРИТИЧЕСКИ ВАЖНО: масштабирование от левого края
                transformOrigin = TransformOrigin(0f, 0.5f)
            }
    ) {
        drawText(
            textLayoutResult = layoutResult,
            brush = brush,
            topLeft = Offset.Zero
        )
    }
}
