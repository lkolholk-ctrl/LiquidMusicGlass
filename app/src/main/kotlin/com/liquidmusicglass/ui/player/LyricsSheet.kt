package com.liquidmusicglass.ui.player

import android.net.Uri
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.liquidmusicglass.engine.LyricsParser
import com.liquidmusicglass.ui.theme.LiquidTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

private val AppleRed = Color(0xFFFC3C44)

/**
 * LyricsSheet — полноэкранный overlay с текстом песни.
 *
 * Фичи:
 * - Парсинг LRC с таймстампами
 * - Автоскролл к текущей строке
 * - Подсветка активной строки (белый bold, остальные полупрозрачные)
 * - Градиент сверху/снизу
 * - Тап для закрытия
 */
@Composable
fun LyricsSheet(
    audioFileUri: Uri?,
    lrcText: String?,       // Если есть внешний LRC текст
    currentPositionMs: Long,
    trackTitle: String = "",
    trackArtist: String = "",
    trackDurationMs: Long = 0L,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val lc = LiquidTheme.colors

    // Загрузка lyrics (embedded → online)
    var lyrics by remember { mutableStateOf(LyricsParser.Lyrics.EMPTY) }
    var isLoading by remember { mutableStateOf(false) }

    LaunchedEffect(audioFileUri, lrcText, trackTitle, trackArtist) {
        isLoading = true
        lyrics = withContext(Dispatchers.IO) {
            when {
                !lrcText.isNullOrBlank() -> LyricsParser.parseLyrics(lrcText)
                else -> LyricsParser.loadLyrics(
                    context = context,
                    uri = audioFileUri,
                    title = trackTitle,
                    artist = trackArtist,
                    durationMs = trackDurationMs
                )
            }
        }
        isLoading = false
    }

    // Текущая строка
    var currentLineIndex by remember { mutableIntStateOf(-1) }
    LaunchedEffect(lyrics, currentPositionMs) {
        if (lyrics.isSynced) {
            currentLineIndex = LyricsParser.findCurrentLine(lyrics, currentPositionMs)
        }
    }

    // Auto-scroll
    val listState = rememberLazyListState()
    LaunchedEffect(currentLineIndex) {
        if (currentLineIndex >= 0 && lyrics.isSynced) {
            listState.animateScrollToItem(
                index = currentLineIndex,
                scrollOffset = -300 // center-ish
            )
        }
    }

    // Full screen overlay
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.85f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss
            )
            .windowInsetsPadding(WindowInsets.statusBars)
            .windowInsetsPadding(WindowInsets.navigationBars)
    ) {
        if (isLoading) {
            // ── Loading ──
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(
                        color = Color.White.copy(alpha = 0.6f),
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "Searching lyrics...",
                        color = lc.textTertiary,
                        fontSize = 15.sp
                    )
                }
            }
        } else if (lyrics.lines.isEmpty()) {
            // ── No lyrics ──
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No lyrics available",
                    color = lc.textTertiary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        } else {
            // ── Lyrics list ──
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 32.dp)
            ) {
                item { Spacer(Modifier.height(120.dp)) }

                itemsIndexed(lyrics.lines) { index, line ->
                    val isCurrent = index == currentLineIndex
                    val isPast = lyrics.isSynced && index < currentLineIndex

                    val textColor by animateColorAsState(
                        targetValue = when {
                            isCurrent -> Color.White
                            isPast -> Color.White.copy(alpha = 0.3f)
                            !lyrics.isSynced -> Color.White.copy(alpha = 0.8f)
                            else -> Color.White.copy(alpha = 0.4f)
                        },
                        animationSpec = tween(300),
                        label = "lyricColor"
                    )

                    val scale by animateFloatAsState(
                        targetValue = if (isCurrent) 1.05f else 1f,
                        animationSpec = tween(300),
                        label = "lyricScale"
                    )

                    Text(
                        text = line.text,
                        color = textColor,
                        fontSize = if (isCurrent) 26.sp else 22.sp,
                        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                        textAlign = TextAlign.Start,
                        lineHeight = 34.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp)
                            .graphicsLayer { scaleX = scale; scaleY = scale }
                    )
                }

                item { Spacer(Modifier.height(300.dp)) }
            }

            // ── Top fade gradient ──
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(100.dp)
                    .align(Alignment.TopCenter)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Black.copy(alpha = 0.85f),
                                Color.Transparent
                            )
                        )
                    )
            )

            // ── Bottom fade gradient ──
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(100.dp)
                    .align(Alignment.BottomCenter)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.85f)
                            )
                        )
                    )
            )
        }

        // ── Title at top ──
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp, start = 32.dp, end = 32.dp)
                .align(Alignment.TopCenter)
        ) {
            Text(
                text = "Lyrics",
                color = lc.textTertiary,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )
        }
    }
}
