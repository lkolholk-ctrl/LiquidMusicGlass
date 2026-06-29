package com.liquidmusicglass.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.liquidmusicglass.engine.LrcLibPublisher
import com.liquidmusicglass.engine.LyricsParser
import com.liquidmusicglass.engine.PlayerController
import com.liquidmusicglass.engine.Track
import com.liquidmusicglass.ui.theme.LiquidColors
import com.liquidmusicglass.ui.theme.LiquidTheme
import kotlinx.coroutines.launch

/**
 * Экран публикации текста в LRCLIB для ЛОКАЛЬНОГО трека. Метаданные авто из трека
 * (можно поправить), ввод plain/synced текста, опц. разметка синхронизации тапами
 * под музыку, инструментальный чекбокс. Публикация: PoW в фоне → POST /api/publish.
 * Не трогает рабочее (JUCE/RT/AutoMix/MediaSession/UI лирики).
 */
@Composable
fun LrcPublishScreen(track: Track, onBack: () -> Unit) {
    val lc = LiquidTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scroll = rememberScrollState()

    var trackName by remember { mutableStateOf(track.title) }
    var artistName by remember { mutableStateOf(track.artist) }
    var albumName by remember { mutableStateOf(track.albumName) }
    val durationSec = remember(track.id) { (track.durationMs / 1000L).toInt() }

    var plainLyrics by remember { mutableStateOf("") }
    var syncedLyrics by remember { mutableStateOf("") }
    var instrumental by remember { mutableStateOf(false) }

    var taggingMode by remember { mutableStateOf(false) }
    var wordTaggingMode by remember { mutableStateOf(false) }

    var publishing by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var resultMsg by remember { mutableStateOf<String?>(null) }
    var resultOk by remember { mutableStateOf(false) }

    // Уже есть моя пословная разметка для этого трека?
    var hasMine by remember(track.id) {
        mutableStateOf(LyricsParser.hasMyWordLyrics(context, track.artist, track.title, track.durationMs))
    }

    if (taggingMode) {
        SyncTaggingMode(
            track = track,
            lc = lc,
            lines = plainLyrics.lines().map { it.trim() }.filter { it.isNotEmpty() },
            onDone = { lrc -> syncedLyrics = lrc; taggingMode = false },
            onCancel = { taggingMode = false }
        )
        return
    }

    if (wordTaggingMode) {
        SyncWordTaggingMode(
            track = track,
            lc = lc,
            lines = plainLyrics.lines().map { it.trim() }.filter { it.isNotEmpty() },
            onDone = { enhanced ->
                runCatching {
                    LyricsParser.saveMyWordLyrics(
                        context, artistName.trim(), trackName.trim(), track.durationMs, enhanced, track.id
                    )
                }
                hasMine = true
                wordTaggingMode = false
                resultOk = true
                resultMsg = "Пословная разметка сохранена ✓ (показывается ваш текст)"
            },
            onCancel = { wordTaggingMode = false }
        )
        return
    }

    Box(modifier = Modifier.fillMaxSize().background(lc.settingsBackground)) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = 20.dp)
        ) {
            Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
            Spacer(Modifier.height(12.dp))

            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                CircleBack(lc, onBack)
                Spacer(Modifier.width(16.dp))
                Text("Publish lyrics", color = lc.textPrimary, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            }
            Text("Вклад в открытую базу LRCLIB для этого трека.",
                color = lc.textSecondary, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
            Spacer(Modifier.height(18.dp))

            // ── Метаданные ──
            Card(lc) {
                LabeledField("Название", trackName, { trackName = it }, lc, placeholder = "Track name")
                Spacer(Modifier.height(10.dp))
                LabeledField("Артист", artistName, { artistName = it }, lc, placeholder = "Artist")
                Spacer(Modifier.height(10.dp))
                LabeledField("Альбом", albumName, { albumName = it }, lc, placeholder = "Album")
                Spacer(Modifier.height(10.dp))
                Text("Длительность: ${durationSec}s (из файла)", color = lc.textSecondary, fontSize = 12.sp)
            }
            Spacer(Modifier.height(14.dp))

            // ── Инструментал ──
            Row(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .clickable { instrumental = !instrumental }.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CheckMark(instrumental, lc)
                Spacer(Modifier.width(12.dp))
                Text("Инструментальный трек (без текста)", color = lc.textPrimary, fontSize = 15.sp)
            }
            Spacer(Modifier.height(8.dp))

            if (!instrumental) {
                Card(lc) {
                    LabeledField("Обычный текст", plainLyrics, { plainLyrics = it }, lc,
                        singleLine = false, minHeight = 120.dp, placeholder = "Строки песни…")
                }
                Spacer(Modifier.height(12.dp))
                Card(lc) {
                    val canTag = plainLyrics.lines().any { it.isNotBlank() }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text("Синхронизированный (LRC)", color = lc.textSecondary, fontSize = 12.sp,
                            modifier = Modifier.weight(1f))
                        Text(
                            "По строкам ▶",
                            color = if (canTag) lc.accent else lc.textSecondary,
                            fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.clip(RoundedCornerShape(8.dp))
                                .clickable(enabled = canTag) { taggingMode = true }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    LabeledField("", syncedLyrics, { syncedLyrics = it }, lc,
                        singleLine = false, minHeight = 100.dp, placeholder = "[mm:ss.xx] строка …")
                }
                Spacer(Modifier.height(12.dp))

                // ── Пословная (Enhanced LRC) — МОЙ источник, локально, высший приоритет ──
                Card(lc) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Пословная разметка (караоке)", color = lc.textPrimary, fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold)
                            Text(
                                if (hasMine) "Сохранена — показывается ваш текст (замещает LRCLIB)."
                                else "Тап по каждому слову. Хранится локально, выше LRCLIB.",
                                color = lc.textSecondary, fontSize = 12.sp
                            )
                        }
                        val canWord = plainLyrics.lines().any { it.isNotBlank() }
                        Text(
                            "По словам ▶",
                            color = if (canWord) lc.accent else lc.textSecondary,
                            fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.clip(RoundedCornerShape(8.dp))
                                .clickable(enabled = canWord) { wordTaggingMode = true }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                    if (hasMine) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Удалить мою разметку",
                            color = lc.accentRed, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    LyricsParser.deleteMyWordLyrics(context, track.artist, track.title, track.durationMs, track.id)
                                    hasMine = false
                                    resultOk = true; resultMsg = "Пословная разметка удалена"
                                }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            // ── Публикация ──
            Box(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                    .background(if (publishing) lc.cardSurface else lc.accent)
                    .clickable(enabled = !publishing) {
                        publishing = true; resultMsg = null; status = "Запрос задачи…"
                        scope.launch {
                            val meta = LrcLibPublisher.Meta(
                                trackName.trim(), artistName.trim(), albumName.trim(), durationSec
                            )
                            val plain = if (instrumental) "" else plainLyrics.trim()
                            val synced = if (instrumental) "" else syncedLyrics.trim()
                            val res = LrcLibPublisher.publish(meta, plain, synced) { status = it }
                            publishing = false
                            when (res) {
                                is LrcLibPublisher.Result.Success -> {
                                    resultOk = true
                                    resultMsg = "Текст опубликован ✓"
                                    val lrc = synced.ifBlank { plain }
                                    if (lrc.isNotBlank()) runCatching {
                                        LyricsParser.cachePublishedLyrics(
                                            context, meta.artistName, meta.trackName,
                                            meta.albumName, durationSec, lrc, track.id
                                        )
                                    }
                                }
                                is LrcLibPublisher.Result.Error -> {
                                    resultOk = false; resultMsg = res.message
                                }
                            }
                        }
                    }
                    .padding(vertical = 14.dp),
                contentAlignment = Alignment.Center
            ) {
                if (publishing) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(color = lc.accent, strokeWidth = 2.dp,
                            modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(status, color = lc.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    }
                } else {
                    Text("Опубликовать", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            }

            resultMsg?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, color = if (resultOk) lc.accentGreen else lc.accentRed,
                    fontSize = 14.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth())
            }

            Spacer(Modifier.height(40.dp))
        }
    }
}

// ── Режим разметки синхронизации ─────────────────────────────────────────────
@Composable
private fun SyncTaggingMode(
    track: Track,
    lc: LiquidColors,
    lines: List<String>,
    onDone: (String) -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    val isPlaying by PlayerController.isPlaying.collectAsState()
    val times = remember { mutableStateListOf<Long?>().apply { repeat(lines.size) { add(null) } } }
    var nextIndex by remember { mutableStateOf(0) }
    val scroll = rememberScrollState()

    // Запускаем именно этот трек с начала, чтобы тапать строки под музыку.
    LaunchedEffect(track.id) {
        runCatching { PlayerController.playFromList(context, listOf(track), 0) }
    }

    Box(modifier = Modifier.fillMaxSize().background(lc.settingsBackground)) {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
            Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                CircleBack(lc, onCancel)
                Spacer(Modifier.width(16.dp))
                Text("Разметка", color = lc.textPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f))
                Text("Готово", color = lc.accent, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp))
                        .clickable { onDone(buildLrc(lines, times)) }
                        .padding(horizontal = 10.dp, vertical = 6.dp))
            }
            Text("Тапни по строке в момент, когда она звучит.",
                color = lc.textSecondary, fontSize = 13.sp, modifier = Modifier.padding(vertical = 6.dp))

            // транспорт
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
                RoundIcon(if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, lc) {
                    PlayerController.togglePlayPause(context)
                }
                Spacer(Modifier.width(12.dp))
                RoundIcon(Icons.Rounded.Replay, lc) { PlayerController.seekTo(0) }
                Spacer(Modifier.width(16.dp))
                Text(
                    "TAP (строка ${(nextIndex + 1).coerceAtMost(lines.size)})",
                    color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(lc.accent)
                        .clickable(enabled = nextIndex < lines.size) {
                            if (nextIndex < lines.size) {
                                times[nextIndex] = PlayerController.getSmoothPositionMs().coerceAtLeast(0)
                                nextIndex++
                            }
                        }
                        .padding(vertical = 12.dp),
                    textAlign = TextAlign.Center
                )
            }

            Column(modifier = Modifier.weight(1f).verticalScroll(scroll)) {
                lines.forEachIndexed { i, text ->
                    val t = times.getOrNull(i)
                    Row(
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                            .background(if (i == nextIndex) lc.accent.copy(alpha = 0.15f) else Color.Transparent)
                            .clickable {
                                times[i] = PlayerController.getSmoothPositionMs().coerceAtLeast(0)
                                if (i + 1 > nextIndex) nextIndex = i + 1
                            }
                            .padding(horizontal = 10.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(if (t != null) fmtTimeShort(t) else "—",
                            color = if (t != null) lc.accent else lc.textSecondary,
                            fontSize = 12.sp, modifier = Modifier.width(62.dp))
                        Text(text, color = lc.textPrimary, fontSize = 15.sp, modifier = Modifier.weight(1f))
                    }
                }
                Spacer(Modifier.height(40.dp))
            }
        }
    }
}

// ── Режим ПОСЛОВНОЙ разметки (Enhanced LRC, мой источник) ─────────────────────
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun SyncWordTaggingMode(
    track: Track,
    lc: LiquidColors,
    lines: List<String>,
    onDone: (String) -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    val isPlaying by PlayerController.isPlaying.collectAsState()

    val wordRows = remember(lines) {
        lines.map { it.split(Regex("\\s+")).filter { w -> w.isNotEmpty() } }
    }
    val lineStart = remember(wordRows) {
        IntArray(wordRows.size).also { arr ->
            var acc = 0
            for (i in wordRows.indices) { arr[i] = acc; acc += wordRows[i].size }
        }
    }
    val total = remember(wordRows) { wordRows.sumOf { it.size } }
    val times = remember(total) { mutableStateListOf<Long?>().apply { repeat(total) { add(null) } } }
    var nextIndex by remember { mutableStateOf(0) }
    val scroll = rememberScrollState()

    LaunchedEffect(track.id) {
        runCatching { PlayerController.playFromList(context, listOf(track), 0) }
    }

    Box(modifier = Modifier.fillMaxSize().background(lc.settingsBackground)) {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
            Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                CircleBack(lc, onCancel)
                Spacer(Modifier.width(16.dp))
                Text("По словам", color = lc.textPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f))
                Text("Готово", color = lc.accent, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp))
                        .clickable { onDone(buildEnhancedLrc(wordRows, lineStart, times)) }
                        .padding(horizontal = 10.dp, vertical = 6.dp))
            }
            Text("Тапни по каждому слову в момент, когда оно звучит (караоке).",
                color = lc.textSecondary, fontSize = 13.sp, modifier = Modifier.padding(vertical = 6.dp))

            // транспорт + большой TAP
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
                RoundIcon(if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, lc) {
                    PlayerController.togglePlayPause(context)
                }
                Spacer(Modifier.width(12.dp))
                RoundIcon(Icons.Rounded.Replay, lc) { PlayerController.seekTo(0) }
                Spacer(Modifier.width(16.dp))
                Text(
                    "TAP (слово ${(nextIndex + 1).coerceAtMost(total)}/$total)",
                    color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(lc.accent)
                        .clickable(enabled = nextIndex < total) {
                            if (nextIndex < total) {
                                times[nextIndex] = PlayerController.getSmoothPositionMs().coerceAtLeast(0)
                                nextIndex++
                            }
                        }
                        .padding(vertical = 12.dp),
                    textAlign = TextAlign.Center
                )
            }

            Column(modifier = Modifier.weight(1f).verticalScroll(scroll)) {
                wordRows.forEachIndexed { li, words ->
                    if (words.isEmpty()) return@forEachIndexed
                    FlowRow(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        words.forEachIndexed { wi, word ->
                            val gi = lineStart[li] + wi
                            val tagged = times.getOrNull(gi) != null
                            val isNext = gi == nextIndex
                            Text(
                                word,
                                color = when {
                                    isNext -> Color.White
                                    tagged -> lc.accent
                                    else -> lc.textPrimary
                                },
                                fontSize = 16.sp,
                                fontWeight = if (isNext) FontWeight.Bold else FontWeight.Normal,
                                modifier = Modifier.clip(RoundedCornerShape(8.dp))
                                    .background(
                                        when {
                                            isNext -> lc.accent
                                            tagged -> lc.accent.copy(alpha = 0.15f)
                                            else -> Color.Transparent
                                        }
                                    )
                                    .clickable {
                                        times[gi] = PlayerController.getSmoothPositionMs().coerceAtLeast(0)
                                        if (gi + 1 > nextIndex) nextIndex = gi + 1
                                    }
                                    .padding(horizontal = 8.dp, vertical = 5.dp)
                            )
                        }
                    }
                }
                Spacer(Modifier.height(40.dp))
            }
        }
    }
}

// ── мелкие части ──────────────────────────────────────────────────────────────
@Composable
private fun Card(lc: LiquidColors, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(lc.cardSurface).padding(16.dp),
        content = content
    )
}

@Composable
private fun LabeledField(
    label: String, value: String, onValueChange: (String) -> Unit, lc: LiquidColors,
    singleLine: Boolean = true, minHeight: androidx.compose.ui.unit.Dp = 0.dp,
    placeholder: String = "", number: Boolean = false
) {
    if (label.isNotEmpty()) {
        Text(label, color = lc.textSecondary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(4.dp))
    }
    BasicTextField(
        value = value, onValueChange = onValueChange,
        textStyle = TextStyle(color = lc.textPrimary, fontSize = 15.sp),
        cursorBrush = SolidColor(lc.accent),
        singleLine = singleLine,
        keyboardOptions = if (number) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default,
        modifier = Modifier.fillMaxWidth(),
        decorationBox = { inner ->
            Box(
                modifier = Modifier.fillMaxWidth().heightIn(min = minHeight)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (lc.isDark) Color(0xFF2C2C2E) else Color(0xFFF2F2F7))
                    .padding(12.dp),
                contentAlignment = if (singleLine) Alignment.CenterStart else Alignment.TopStart
            ) {
                if (value.isEmpty()) Text(placeholder, color = lc.textSecondary, fontSize = 15.sp)
                inner()
            }
        }
    )
}

@Composable
private fun CheckMark(checked: Boolean, lc: LiquidColors) {
    Box(
        modifier = Modifier.size(22.dp).clip(RoundedCornerShape(6.dp))
            .background(if (checked) lc.accent else (if (lc.isDark) Color(0xFF2C2C2E) else Color(0xFFE5E5EA))),
        contentAlignment = Alignment.Center
    ) {
        if (checked) Text("✓", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun RoundIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, lc: LiquidColors, onClick: () -> Unit) {
    Box(
        modifier = Modifier.size(44.dp).clip(CircleShape)
            .background(if (lc.isDark) Color(0xFF2C2C2E) else Color(0xFFF2F2F7))
            .clickable(remember { MutableInteractionSource() }, null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Icon(icon, null, tint = lc.iconDefault, modifier = Modifier.size(22.dp)) }
}

@Composable
private fun CircleBack(lc: LiquidColors, onClick: () -> Unit) {
    Box(
        modifier = Modifier.size(40.dp).clip(CircleShape)
            .background(if (lc.isDark) Color(0xFF1C1C1E) else Color(0xFFF2F2F7))
            .clickable(remember { MutableInteractionSource() }, null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(Icons.AutoMirrored.Rounded.ArrowBack, null, tint = lc.iconDefault, modifier = Modifier.size(22.dp))
    }
}

private fun fmtTs(ms: Long): String {
    val m = ms / 60000; val s = (ms % 60000) / 1000; val cs = (ms % 1000) / 10
    return "[%02d:%02d.%02d]".format(m, s, cs)
}

private fun fmtTimeShort(ms: Long): String {
    val m = ms / 60000; val s = (ms % 60000) / 1000; val cs = (ms % 1000) / 10
    return "%02d:%02d.%02d".format(m, s, cs)
}

/** LRC из строк и таймкодов. Нерасставленные строки наследуют предыдущий таймкод. */
private fun buildLrc(lines: List<String>, times: List<Long?>): String {
    var last = 0L
    return lines.mapIndexed { i, text ->
        val t = times.getOrNull(i) ?: last
        last = t
        fmtTs(t) + text
    }.joinToString("\n")
}

/**
 * Enhanced LRC из пословных таймкодов:
 *   [mm:ss.xx]<mm:ss.xx>слово <mm:ss.xx>слово …
 * Таймкод строки = таймкод первого слова. Неотмеченные слова наследуют предыдущий.
 */
private fun buildEnhancedLrc(wordRows: List<List<String>>, lineStart: IntArray, times: List<Long?>): String {
    var last = 0L
    val t = LongArray(times.size)
    for (i in times.indices) { val v = times[i] ?: last; last = v; t[i] = v }
    val sb = StringBuilder()
    for ((li, words) in wordRows.withIndex()) {
        if (words.isEmpty()) continue
        val base = lineStart[li]
        sb.append(fmtTs(t[base]))                                  // [mm:ss.xx] начало строки
        for (wi in words.indices) {
            sb.append('<').append(fmtTimeShort(t[base + wi])).append('>').append(words[wi])
            if (wi < words.size - 1) sb.append(' ')
        }
        sb.append('\n')
    }
    return sb.toString().trimEnd()
}
