package com.liquidmusicglass.ui.lyrics

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.liquidmusicglass.engine.LyricsFxController
import com.liquidmusicglass.engine.LyricsParser
import com.liquidmusicglass.engine.PlayerController
import kotlinx.coroutines.isActive

/**
 * Превью («Тест») разметки: проигрывает трек с подсветкой по ТЕКУЩЕЙ разметке
 * (как будет в реальном плеере — те же sweep/эффект/плавность), чтобы проверить
 * синхронность. Можно стартовать с конкретной строки. Внизу — выбор эффекта и
 * ползунок плавности (применяются глобально, сохраняются). Кнопка — назад к правке.
 *
 * Это ПРОВЕРОЧНЫЙ оверлей; ничего не сохраняет и не трогает движок/MediaSession,
 * кроме обычных seek/play (как и сама разметка).
 */
@Composable
fun MarkupPreviewView(
    lyrics: LyricsParser.Lyrics,
    startLineIndex: Int,
    accent: Color,
    onBackToEdit: () -> Unit
) {
    val context = LocalContext.current
    val processor = remember(lyrics) {
        if (lyrics.lines.isNotEmpty()) LyricsTimeProcessor(lyrics) else null
    }
    val isPlaying by PlayerController.isPlaying.collectAsState()
    var smoothPos by remember { mutableLongStateOf(0L) }
    val currentProcessor by rememberUpdatedState(processor)
    val isWordLevel = lyrics.isWordLevel

    // Старт с выбранной строки.
    LaunchedEffect(startLineIndex, lyrics) {
        val t = lyrics.lines.getOrNull(startLineIndex)?.timeMs?.coerceAtLeast(0L) ?: 0L
        runCatching { PlayerController.seekTo(t) }
        if (!PlayerController.isPlaying.value) runCatching { PlayerController.togglePlayPause(context) }
    }

    // Покадровый тикер (как в реальном экране лирики).
    LaunchedEffect(Unit) {
        while (isActive) {
            withFrameMillis {
                if (PlayerController.isPlaying.value) {
                    smoothPos = PlayerController.getSmoothPositionMs()
                    currentProcessor?.updatePosition(smoothPos)
                }
            }
        }
    }
    val coarse by PlayerController.currentPositionMs.collectAsState()
    LaunchedEffect(coarse, isPlaying) {
        if (!isPlaying) { smoothPos = coarse; processor?.updatePosition(coarse) }
    }

    val curIdx by processor?.currentLineIndex?.collectAsState() ?: remember { mutableIntStateOf(-1) }
    val curProg by processor?.currentLineProgress?.collectAsState() ?: remember { mutableFloatStateOf(0f) }

    val effect by LyricsFxController.effect.collectAsState()
    val smooth by LyricsFxController.smoothness.collectAsState()
    val speed by PlayerController.playbackSpeed.collectAsState()

    val listState = rememberLazyListState()
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val maxWidthPx = with(density) { (configuration.screenWidthDp.dp - 48.dp).toPx().toInt() }
    val softPx = (0.02f + smooth * 0.16f) * maxWidthPx

    LaunchedEffect(curIdx) {
        if (curIdx >= 0) runCatching {
            listState.animateScrollToItem(curIdx.coerceAtMost((lyrics.lines.size - 1).coerceAtLeast(0)))
        }
    }

    Box(Modifier.fillMaxSize().background(Color(0xFF0A0A0C))) {
        Column(Modifier.fillMaxSize()) {
            Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))

            // ── Топ-бар ──
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier.clip(RoundedCornerShape(20.dp)).background(accent)
                        .clickable(remember { MutableInteractionSource() }, null, onClick = onBackToEdit)
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, null, tint = Color.White,
                            modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("К разметке", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
                Spacer(Modifier.weight(1f))
                Text("ТЕСТ", color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }

            // ── Строки с подсветкой ──
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth()
            ) {
                item { Spacer(Modifier.height(40.dp)) }
                itemsIndexed(lyrics.lines) { index, line ->
                    val isCurrent = index == curIdx
                    val isPast = index < curIdx
                    val fillProgress = when {
                        isPast -> 1f
                        isCurrent -> curProg
                        else -> 0f
                    }
                    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 10.dp)) {
                        LyricLineSweep(
                            text = line.text,
                            fillProgress = fillProgress,
                            sungColor = Color.White.copy(alpha = if (isCurrent) 1f else 0.55f),
                            unsungColor = Color.White.copy(alpha = 0.30f),
                            isActive = isCurrent,
                            maxWidthPx = maxWidthPx,
                            glowColor = accent,
                            effect = if (isWordLevel) effect else LyricsFxController.WordEffect.FILL,
                            edgeSoftPx = if (isCurrent && isWordLevel) softPx else 0f
                        )
                    }
                }
                item { Spacer(Modifier.height(120.dp)) }
            }

            // ── Транспорт ──
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                CircleBtn(if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, accent) {
                    PlayerController.togglePlayPause(context)
                }
                CircleBtn(Icons.Rounded.Replay, accent) {
                    val t = lyrics.lines.getOrNull(startLineIndex)?.timeMs?.coerceAtLeast(0L) ?: 0L
                    PlayerController.seekTo(t)
                }
                Text(
                    if (isWordLevel) "Пословно — проверь, вовремя ли загораются слова"
                    else "Построчно — проверь тайминг строк",
                    color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp
                )
            }

            // ── Скорость (можно проверять и в замедлении) ──
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Скорость:", color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
                for (i in 10 downTo 1) {
                    val v = i / 10f
                    val sel = kotlin.math.abs(speed - v) < 0.01f
                    Text(
                        "$v×", color = Color.White, fontSize = 13.sp,
                        fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp))
                            .background(if (sel) accent else Color.White.copy(alpha = 0.12f))
                            .clickable(remember { MutableInteractionSource() }, null) {
                                PlayerController.setPlaybackSpeed(v)
                            }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }

            // ── Эффекты + плавность (применяются глобально, сохраняются) ──
            if (isWordLevel) {
                Column(
                    Modifier.fillMaxWidth()
                        .background(Color.White.copy(alpha = 0.05f))
                        .windowInsetsPadding(WindowInsets.navigationBars)
                        .padding(horizontal = 20.dp, vertical = 12.dp)
                ) {
                    Text("Эффект перетекания", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        EffectChip("Заливка", effect == LyricsFxController.WordEffect.FILL, accent) {
                            LyricsFxController.setEffect(LyricsFxController.WordEffect.FILL)
                        }
                        EffectChip("Затухание", effect == LyricsFxController.WordEffect.FADE, accent) {
                            LyricsFxController.setEffect(LyricsFxController.WordEffect.FADE)
                        }
                        EffectChip("Бегущий", effect == LyricsFxController.WordEffect.RUNNING, accent) {
                            LyricsFxController.setEffect(LyricsFxController.WordEffect.RUNNING)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("Плавность: ${(smooth * 100).toInt()}%", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
                    Slider(
                        value = smooth,
                        onValueChange = { LyricsFxController.setSmoothness(it) },
                        valueRange = 0f..1f
                    )
                }
            }
        }
    }
}

@Composable
private fun EffectChip(label: String, selected: Boolean, accent: Color, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(20.dp))
            .background(if (selected) accent else Color.White.copy(alpha = 0.12f))
            .clickable(remember { MutableInteractionSource() }, null, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = Color.White, fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
    }
}

@Composable
private fun CircleBtn(icon: androidx.compose.ui.graphics.vector.ImageVector, accent: Color, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).clip(CircleShape).background(accent)
            .clickable(remember { MutableInteractionSource() }, null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Icon(icon, null, tint = Color.White, modifier = Modifier.size(22.dp)) }
}
