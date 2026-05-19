package com.liquidmusicglass.ui.lyrics

import android.net.Uri
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
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

/**
 * Полноэкранный караоке-экран лирики (Apple Music style).
 *
 * Фичи:
 * - Word-level synchronization с градиентным наплывом
 * - HSV-boosted фон с blur + scrim
 * - Duet layout: мужской/женский вокал разными цветами
 * - WaitingDots до начала первой строки
 * - Anti-jitter: позиция никогда не уменьшается
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
            // CRITICAL FIX: parse lyrics on Default, never Main
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

    // ── Time processor for word-level sync ──
    val timeProcessor = remember(lyrics) {
        if (lyrics.lines.isNotEmpty()) LyricsTimeProcessor(lyrics) else null
    }

    // Anti-jitter position flow
    var safePositionMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(currentPositionMs) {
        safePositionMs = kotlin.math.max(safePositionMs, currentPositionMs)
        timeProcessor?.updatePosition(safePositionMs)
    }

    val currentLineIndex by timeProcessor?.currentLineIndex?.collectAsState() ?: remember { mutableIntStateOf(-1) }
    val pastWords by timeProcessor?.pastWords?.collectAsState() ?: remember { mutableStateOf(emptyList<LyricsTimeProcessor.WordToken>()) }
    val currentWords by timeProcessor?.currentWords?.collectAsState() ?: remember { mutableStateOf(emptyList<LyricsTimeProcessor.WordToken>()) }
    val upcomingWords by timeProcessor?.upcomingWords?.collectAsState() ?: remember { mutableStateOf(emptyList<LyricsTimeProcessor.WordToken>()) }

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
                        if (lyrics.isSynced && safePositionMs < lyrics.lines.firstOrNull()?.timeMs ?: 0L) {
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
                                    // Word-level karaoke rendering
                                    KaraokeLine(
                                        line = cleanText,
                                        pastWords = pastWords,
                                        currentWords = currentWords,
                                        upcomingWords = upcomingWords,
                                        baseColor = duetColor ?: Color.White.copy(alpha = LyricsTimeProcessor.UPCOMING_ALPHA),
                                        activeColor = duetColor ?: Color.White,
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
 * Строка с word-level караоке-эффектом.
 * Каждое слово рендерится отдельно с градиентным наплывом.
 */
@Composable
private fun KaraokeLine(
    line: String,
    pastWords: List<LyricsTimeProcessor.WordToken>,
    currentWords: List<LyricsTimeProcessor.WordToken>,
    upcomingWords: List<LyricsTimeProcessor.WordToken>,
    baseColor: Color,
    activeColor: Color,
    fontSize: androidx.compose.ui.unit.TextUnit
) {
    val words = line.split(" ").filter { it.isNotBlank() }

    // Build word states
    val wordStates = words.map { wordText ->
        val past = pastWords.find { it.text == wordText }
        val current = currentWords.find { it.text == wordText }
        val upcoming = upcomingWords.find { it.text == wordText }

        when {
            past != null -> WordState.Past
            current != null -> WordState.Current(current.progress)
            upcoming != null -> WordState.Upcoming
            else -> WordState.Upcoming
        }
    }

    // Render as flow layout (simplified row wrapping)
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        var currentRow = mutableListOf<Pair<String, WordState>>()
        var rowWidth = 0f
        val maxRowWidth = 320f // approximate dp width

        val textMeasurer = rememberTextMeasurer()
        val textStyle = TextStyle(
            fontSize = fontSize,
            fontWeight = FontWeight.Bold
        )

        for ((i, word) in words.withIndex()) {
            val measured = textMeasurer.measure(word, textStyle)
            val wordWidth = measured.size.width.toFloat() / LocalDensity.current.density + 8f // + spacing

            if (rowWidth + wordWidth > maxRowWidth && currentRow.isNotEmpty()) {
                // Render current row
                KaraokeRow(
                    words = currentRow.toList(),
                    baseColor = baseColor,
                    activeColor = activeColor,
                    fontSize = fontSize
                )
                currentRow = mutableListOf()
                rowWidth = 0f
            }

            currentRow.add(word to wordStates[i])
            rowWidth += wordWidth
        }

        // Render last row
        if (currentRow.isNotEmpty()) {
            KaraokeRow(
                words = currentRow.toList(),
                baseColor = baseColor,
                activeColor = activeColor,
                fontSize = fontSize
            )
        }
    }
}

private sealed class WordState {
    data object Past : WordState()
    data class Current(val progress: Float) : WordState()
    data object Upcoming : WordState()
}

@Composable
private fun KaraokeRow(
    words: List<Pair<String, WordState>>,
    baseColor: Color,
    activeColor: Color,
    fontSize: androidx.compose.ui.unit.TextUnit
) {
    Row(
        modifier = Modifier.wrapContentWidth(),
        horizontalArrangement = Arrangement.Center
    ) {
        for ((text, state) in words) {
            when (state) {
                is WordState.Past -> {
                    LyricsWord(
                        text = text,
                        progress = 1f,
                        color = baseColor.copy(alpha = LyricsTimeProcessor.PAST_ALPHA),
                        activeColor = activeColor,
                        fontSize = fontSize
                    )
                }
                is WordState.Current -> {
                    LyricsWord(
                        text = text,
                        progress = state.progress,
                        color = baseColor.copy(alpha = LyricsTimeProcessor.UPCOMING_ALPHA),
                        activeColor = activeColor,
                        fontSize = fontSize
                    )
                }
                is WordState.Upcoming -> {
                    LyricsWord(
                        text = text,
                        progress = 0f,
                        color = baseColor.copy(alpha = LyricsTimeProcessor.UPCOMING_ALPHA),
                        activeColor = activeColor,
                        fontSize = fontSize
                    )
                }
            }
        }
    }
}
