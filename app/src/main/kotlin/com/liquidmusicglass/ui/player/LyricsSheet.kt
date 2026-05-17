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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.liquidmusicglass.engine.LyricsParser
import com.liquidmusicglass.ui.glass.AlbumArtImage
import com.liquidmusicglass.ui.glass.AlbumColors
import com.liquidmusicglass.ui.theme.LiquidTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * LyricsSheet — полноэкранный экран лирики (стиль Apple Music).
 *
 * - Фон: размытая обложка трека (AnimatedPlayerBackground, статичный)
 * - Компактная шапка сверху: мини-обложка + название + артист
 * - Текст песни на весь экран, активная строка подсвечена
 * - Тап по экрану показывает контролы плеера (закрытие — кнопкой лирики)
 */
@Composable
fun LyricsSheet(
    audioFileUri: Uri?,
    lrcText: String?,
    currentPositionMs: Long,
    trackTitle: String = "",
    trackArtist: String = "",
    trackDurationMs: Long = 0L,
    albumArtUri: Uri? = null,
    coverUrl: String? = null,
    albumId: Long = -1L,
    albumColors: AlbumColors,
    onRequestControls: () -> Unit
) {
    val context = LocalContext.current
    val lc = LiquidTheme.colors

    // Загрузка lyrics (embedded -> online)
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
                scrollOffset = -300
            )
        }
    }

    // Высота компактной шапки
    val headerHeight = 96.dp

    // ── Full screen ──
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onRequestControls
            )
    ) {
        // ── Фон: размытая обложка трека ──
        AnimatedPlayerBackground(
            albumArtUri = albumArtUri,
            coverUrl = coverUrl,
            audioFileUri = audioFileUri,
            albumId = albumId,
            albumColors = albumColors,
            modifier = Modifier.fillMaxSize()
        )

        // ── Затемнение для читаемости текста ──
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.35f))
        )

        // ── Контент ──
        Box(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars)
                .windowInsetsPadding(WindowInsets.navigationBars)
        ) {
            // ─── Текст лирики ───
            if (isLoading) {
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
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 28.dp)
                ) {
                    // Отступ сверху — под компактную шапку
                    item { Spacer(Modifier.height(headerHeight + 24.dp)) }

                    itemsIndexed(lyrics.lines) { index, line ->
                        val isCurrent = index == currentLineIndex
                        val isPast = lyrics.isSynced && index < currentLineIndex

                        val textColor by animateColorAsState(
                            targetValue = when {
                                isCurrent -> Color.White
                                isPast -> Color.Transparent          // спетые строки скрыты
                                !lyrics.isSynced -> Color.White.copy(alpha = 0.8f)
                                else -> Color.White.copy(alpha = 0.45f)
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

                    // Отступ снизу — чтобы текст не уходил под контролы плеера
                    item { Spacer(Modifier.height(220.dp)) }
                }
            }

            // ─── Top fade под шапкой ───
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(headerHeight + 40.dp)
                    .align(Alignment.TopCenter)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Black.copy(alpha = 0.55f),
                                Color.Transparent
                            )
                        )
                    )
            )

            // ─── Bottom fade ───
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(160.dp)
                    .align(Alignment.BottomCenter)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.6f)
                            )
                        )
                    )
            )

            // ─── Компактная шапка: мини-обложка + название + артист ───
            // Непрозрачная подложка, чтобы текст лирики не просвечивал.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
            ) {
                // Сплошной фон шапки + мягкий переход вниз
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(headerHeight + 28.dp)
                        .background(
                            Brush.verticalGradient(
                                0.00f to Color.Black.copy(alpha = 0.92f),
                                0.72f to Color.Black.copy(alpha = 0.92f),
                                1.00f to Color.Transparent
                            )
                        )
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(8.dp))
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
                    Spacer(Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = trackTitle,
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = trackArtist,
                            color = Color.White.copy(alpha = 0.65f),
                            fontSize = 14.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}
