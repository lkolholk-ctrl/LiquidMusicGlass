package com.liquidmusicglass.ui.lyrics

import android.net.Uri
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.ui.graphics.drawscope.clipRect
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
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlin.math.max
import kotlin.math.min

/**
 * Полноэкранный караоке-экран лирики (Apple Music style).
 *
 * Фичи:
 * - Плавное градиентное закрашивание строки через Brush.horizontalGradient
 * - Субпиксельное сглаживание Skia/Impeller — никаких лесенок
 * - 60/120 FPS smooth interpolation через withFrameMillis
 * - HSV-boosted фон с blur + scrim
 * - Duet layout: мужской/женский вокал разными цветами
 * - WaitingDots до начала первой строки
 * - Auto-scroll к активной строке
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

    // ── Auto-scroll ──
    val listState = rememberLazyListState()
    LaunchedEffect(currentLineIndex) {
        if (currentLineIndex >= 0) {
            listState.animateScrollToItem(
                index = currentLineIndex.coerceAtMost((lyrics.lines.size - 1).coerceAtLeast(0)),
                scrollOffset = -300
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
                        horizontalAlignment = Alignment.CenterHorizontally
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
                                    WaitingDots(dotColor = boostedVibrant)
                                }
                            }
                        }

                        itemsIndexed(lyrics.lines) { index, line ->
                            val isCurrentLine = index == currentLineIndex
                            val isPastLine = index < currentLineIndex
                            val isUpcomingLine = index > currentLineIndex

                            // Duet color detection
                            val duetColor = when {
                                !isDuet -> null
                                line.text.startsWith("[M", ignoreCase = true) ||
                                    line.text.contains(Regex("""^\[Male""", RegexOption.IGNORE_CASE)) ->
                                    Color(0xFF4FC3F7) // Light blue for male
                                line.text.startsWith("[F", ignoreCase = true) ||
                                    line.text.contains(Regex("""^\[Female""", RegexOption.IGNORE_CASE)) ->
                                    Color(0xFFF48FB1) // Pink for female
                                line.text.startsWith("[D", ignoreCase = true) ->
                                    Color(0xFFFFF176) // Yellow for duet
                                else -> null
                            }

                            val cleanText = line.text.replace(Regex("""\[(M|F|D|Male|Female|Duet):?\s*""", RegexOption.IGNORE_CASE), "")

                            val lineAlpha by animateFloatAsState(
                                targetValue = when {
                                    isCurrentLine -> 1f
                                    isPastLine -> LyricsTimeProcessor.PAST_ALPHA
                                    else -> LyricsTimeProcessor.UPCOMING_ALPHA
                                },
                                animationSpec = tween(400),
                                label = "lineAlpha"
                            )

                            val scale by animateFloatAsState(
                                targetValue = if (isCurrentLine) 1.05f else 1f,
                                animationSpec = tween(400),
                                label = "lineScale"
                            )

                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 32.dp, vertical = 12.dp)
                                    .graphicsLayer {
                                        scaleX = scale
                                        scaleY = scale
                                        alpha = lineAlpha
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                if (isCurrentLine && lyrics.isSynced) {
                                    // Пословное караоке-закрашивание через Canvas + clipRect
                                    KaraokeLine(
                                        words = currentWords,
                                        activeColor = duetColor ?: Color.White,
                                        inactiveColor = (duetColor ?: Color.White).copy(
                                            alpha = LyricsTimeProcessor.UPCOMING_ALPHA
                                        ),
                                        fontSize = 28.sp
                                    )
                                } else {
                                    // Static line
                                    Text(
                                        text = cleanText,
                                        color = duetColor ?: Color.White,
                                        fontSize = if (isCurrentLine) 28.sp else 26.sp,
                                        fontWeight = if (isCurrentLine) FontWeight.Bold else FontWeight.SemiBold,
                                        textAlign = TextAlign.Center,
                                        lineHeight = 38.sp,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
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
 * Строка с пословным караоке-закрашиванием.
 *
 * Каждое слово рисуется отдельно через Canvas + TextMeasurer + clipRect.
 * Это даёт точное позиционирование без "лесенки" — граница цвета всегда
 * совпадает с границей слова.
 *
 * Архитектура:
 * 1. Измеряем полную строку через TextMeasurer
 * 2. Получаем bounding box каждого слова через getBoundingBox()
 * 3. Рисуем background (inactive) — всю строку
 * 4. Для каждого слова рисуем foreground (active) через clipRect
 *
 * @param words список слов с прогрессом (0f..1f)
 * @param activeColor цвет активной (закрашенной) части
 * @param inactiveColor цвет неактивной части
 * @param fontSize размер шрифта
 */
@Composable
private fun KaraokeLine(
    words: List<LyricsTimeProcessor.WordToken>,
    activeColor: Color,
    inactiveColor: Color,
    fontSize: androidx.compose.ui.unit.TextUnit
) {
    if (words.isEmpty()) return

    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current

    // Собираем полный текст с пробелами
    val fullText = remember(words) { words.joinToString(" ") { it.text } }
    val wordRanges = remember(words) {
        val ranges = mutableListOf<IntRange>()
        var pos = 0
        for ((i, word) in words.withIndex()) {
            ranges.add(pos until pos + word.text.length)
            pos += word.text.length
            if (i < words.lastIndex) pos += 1 // space
        }
        ranges
    }

    val textStyle = TextStyle(
        fontSize = fontSize,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
        lineHeight = 38.sp
    )

    // Измеряем текст для получения точных размеров
    val layoutResult = remember(fullText, textStyle) {
        textMeasurer.measure(
            text = fullText,
            style = textStyle,
            constraints = Constraints(maxWidth = Int.MAX_VALUE)
        )
    }

    val canvasWidth = with(density) { layoutResult.size.width.toDp() }
    val canvasHeight = with(density) { layoutResult.size.height.toDp() }

    Canvas(
        modifier = Modifier
            .width(canvasWidth)
            .height(canvasHeight)
    ) {
        // Background: вся строка inactive цветом
        drawText(
            textLayoutResult = layoutResult,
            color = inactiveColor,
            topLeft = Offset.Zero
        )

        // Foreground: каждое слово активным цветом, обрезанное по progress
        for ((i, word) in words.withIndex()) {
            val range = wordRanges[i]
            if (range.isEmpty()) continue

            // Получаем bounding box первого и последнего символа слова
            val startBounds = layoutResult.getBoundingBox(range.first)
            val endBounds = layoutResult.getBoundingBox(range.last)

            val wordLeft = startBounds.left
            val wordRight = endBounds.right
            val wordTop = min(startBounds.top, endBounds.top)
            val wordBottom = max(startBounds.bottom, endBounds.bottom)
            val wordWidth = wordRight - wordLeft

            val progress = word.progress.coerceIn(0f, 1f)
            val activeWidth = wordWidth * progress

            if (activeWidth <= 0.5f) continue // Пропускаем полностью неактивные

            // Рисуем активную часть слова через clipRect
            // clipRect обрезает всё что выходит за границы activeWidth
            clipRect(
                left = wordLeft,
                top = wordTop,
                right = wordLeft + activeWidth,
                bottom = wordBottom
            ) {
                drawText(
                    textLayoutResult = layoutResult,
                    color = activeColor,
                    topLeft = Offset.Zero
                )
            }
        }
    }
}
