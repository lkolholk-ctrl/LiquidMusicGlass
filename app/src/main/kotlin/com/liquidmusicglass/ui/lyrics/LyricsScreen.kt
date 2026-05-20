package com.liquidmusicglass.ui.lyrics

import android.net.Uri
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
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
            // Центрируем активную строку на экране
            val targetIndex = currentLineIndex.coerceAtMost((lyrics.lines.size - 1).coerceAtLeast(0))
            val centerOffset = (screenHeightPx / 2 - with(density) { 40.dp.toPx() }).toInt()
            listState.animateScrollToItem(
                index = targetIndex,
                scrollOffset = -centerOffset
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

                            val distance = remember(currentLineIndex) { abs(index - currentLineIndex) }

                            // Единая spring-анимация для всех строк — никакого staggered offset
                            val lineAlpha by animateFloatAsState(
                                targetValue = when {
                                    isCurrentLine -> 1.0f
                                    isPastLine -> 0.5f
                                    else -> 0.25f
                                },
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioNoBouncy,
                                    stiffness = Spring.StiffnessLow
                                ),
                                label = "lineAlpha"
                            )

                            // Убрано масштабирование активной строки — предотвращает вылет за экран
                            val scale by animateFloatAsState(
                                targetValue = 1.0f, // ВСЕГДА 1.0 — никакого scale
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioNoBouncy,
                                    stiffness = Spring.StiffnessLow
                                ),
                                label = "lineScale"
                            )

                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 24.dp, vertical = 10.dp)
                                    .graphicsLayer {
                                        scaleX = scale
                                        scaleY = scale
                                        alpha = lineAlpha
                                    },
                                contentAlignment = Alignment.CenterStart
                            ) {
                                if (isCurrentLine && lyrics.isSynced) {
                                    // ПОСИМВОЛЬНОЕ караоке с бесконфликтной плавной градиентной волной
                                    FluidLyricRenderer(
                                        text = cleanText,
                                        progress = currentLineProgress,
                                        activeColor = duetColor ?: Color.White,
                                        inactiveColor = (duetColor ?: Color.White).copy(
                                            alpha = 0.3f
                                        ),
                                        fontSize = 26.sp,
                                        maxWidthPx = with(density) { (configuration.screenWidthDp - 48).dp.roundToPx() },
                                        isActiveLine = true
                                    )
                                } else {
                                    // Static line — строгое левое выравнивание
                                    Text(
                                        text = cleanText,
                                        color = (duetColor ?: Color.White).copy(
                                            alpha = if (isPastLine) 0.5f else 0.25f
                                        ),
                                        style = TextStyle(
                                            fontSize = 26.sp,
                                            fontWeight = FontWeight.Normal,
                                            textAlign = TextAlign.Start,
                                            lineHeight = 36.sp,
                                            platformStyle = PlatformTextStyle(includeFontPadding = false)
                                        ),
                                        modifier = Modifier.fillMaxWidth(),
                                        maxLines = 3,
                                        softWrap = true,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
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
