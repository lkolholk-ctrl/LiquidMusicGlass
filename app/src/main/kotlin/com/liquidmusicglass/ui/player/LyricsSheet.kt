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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.liquidmusicglass.engine.LyricsParser
import com.liquidmusicglass.ui.glass.AlbumColors
import com.liquidmusicglass.ui.theme.LiquidTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * LyricsSheet — полноэкранный экран лирики (стиль Apple Music).
 *
 * - Фон: размытая обложка трека
 * - Шапка: только название трека и артист (центр, без мини-обложки)
 * - Текст по центру экрана
 * - Активная строка белая, остальные полупрозрачные
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
    trackId: String? = null,
    albumColors: AlbumColors,
    onRequestControls: () -> Unit
) {
    val context = LocalContext.current
    val lc = LiquidTheme.colors

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

    // Check cache first for instant display
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

        // Use cached lyrics if available
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

    var currentLineIndex by remember { mutableIntStateOf(-1) }
    LaunchedEffect(lyrics, currentPositionMs) {
        if (lyrics.isSynced) {
            currentLineIndex = LyricsParser.findCurrentLine(lyrics, currentPositionMs)
        }
    }

    val listState = rememberLazyListState()
    LaunchedEffect(currentLineIndex) {
        if (currentLineIndex >= 0 && lyrics.isSynced) {
            listState.animateScrollToItem(
                index = currentLineIndex,
                scrollOffset = -250
            )
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
        // ── Background: blurred album art ──
        AnimatedPlayerBackground(
            albumArtUri = albumArtUri,
            coverUrl = coverUrl,
            audioFileUri = audioFileUri,
            albumId = albumId,
            albumColors = albumColors,
            modifier = Modifier.fillMaxSize()
        )

        // ── Light darkening overlay (Apple Music style) ──
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.25f))
        )

        // ── Content ──
        Box(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars)
                .windowInsetsPadding(WindowInsets.navigationBars)
        ) {
            if (isLoading) {
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
            } else if (lyrics.lines.isEmpty()) {
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
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Top spacer for header
                    item { Spacer(Modifier.height(80.dp)) }

                    itemsIndexed(lyrics.lines) { index, line ->
                        val isCurrent = index == currentLineIndex

                        val textColor by animateColorAsState(
                            targetValue = if (isCurrent) {
                                Color.White
                            } else {
                                Color.White.copy(alpha = 0.35f)
                            },
                            animationSpec = tween(400),
                            label = "lyricColor"
                        )

                        val scale by animateFloatAsState(
                            targetValue = if (isCurrent) 1.08f else 1f,
                            animationSpec = tween(400),
                            label = "lyricScale"
                        )

                        Text(
                            text = line.text,
                            color = textColor,
                            fontSize = if (isCurrent) 28.sp else 26.sp,
                            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.SemiBold,
                            textAlign = TextAlign.Center,
                            lineHeight = 38.sp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 32.dp, vertical = 10.dp)
                                .graphicsLayer {
                                    scaleX = scale
                                    scaleY = scale
                                    alpha = if (isCurrent) 1f else 0.5f
                                }
                        )
                    }

                    // Bottom spacer
                    item { Spacer(Modifier.height(200.dp)) }
                }
            }

            // ── Header: track title + artist (centered, no album art) ──
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
