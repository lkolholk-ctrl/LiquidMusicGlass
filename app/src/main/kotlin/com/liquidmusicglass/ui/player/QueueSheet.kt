package com.liquidmusicglass.ui.player

import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.Waves
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.zIndex
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.liquidmusicglass.engine.PlaybackBackend
import com.liquidmusicglass.engine.PlayerController
import com.liquidmusicglass.engine.Track
import com.liquidmusicglass.ui.theme.LiquidMetrics
import com.liquidmusicglass.ui.theme.LiquidSurfaces
import com.liquidmusicglass.ui.theme.LiquidTheme
import com.liquidmusicglass.ui.glass.AlbumArtImage
import com.liquidmusicglass.ui.glass.AlbumColors
import com.liquidmusicglass.ui.glass.pressScale
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Queue overlay — открывается как LyricsScreen: fadeIn/fadeOut поверх FullPlayer.
 * НЕ использует slideIn/slideOut — только прозрачность.
 *
 * Фон: динамический из обложки (как LyricsBackground).
 * Кнопки управления: те же что в FullPlayer (Shuffle, Prev, Play/Pause, Next, Repeat).
 * Без ползунка громкости.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun QueueSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    albumArtUri: Uri? = null,
    coverUrl: String? = null,
    audioFileUri: Uri? = null,
    albumId: Long = -1L,
    albumColors: AlbumColors? = null,
    currentTrack: Track? = null,
    onRequestControls: () -> Unit = {},
    modifier: Modifier = Modifier,
    // Split-режим (альбом, правая половина): свой фон не рисуем — общий фон
    // плеера уже под нами (иначе вертикальный шов-«полоса» и другой оттенок).
    splitMode: Boolean = false
) {
    val context = LocalContext.current
    val colors = LiquidTheme.colors
    val isDark = colors.isDark
    // Локальное воспроизведение через JUCE идёт мимо очереди ExoPlayer — там
    // переставлять и удалять нечего, поэтому жесты в этом режиме не навешиваем.
    val backend by PlayerController.playbackBackend.collectAsState()
    val queueEditable = backend != PlaybackBackend.JUCE_LOCAL
    val repeatMode by PlayerController.repeatMode.collectAsState()
    val sections by PlayerController.queueSections.collectAsState()
    val libraryRepo = remember { com.liquidmusicglass.data.local.db.LibraryRepository.getInstance(context) }
    // Flow нужно строить в remember: без него isFavoriteFlow() создавал новый
    // экземпляр на каждой рекомпозиции, а collectAsState кеширует подписку по
    // инстансу — то есть Room переподписывался буквально каждый кадр.
    val favoriteFlow = remember(currentTrack?.id) {
        currentTrack?.id?.let { libraryRepo.isFavoriteFlow(it) } ?: flowOf(false)
    }
    val isFavorite by favoriteFlow.collectAsState(initial = false)
    val scope = rememberCoroutineScope()

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(400)),
        exit = fadeOut(tween(350)),
        modifier = modifier
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Dynamic background from album art (в split НЕ рисуем — фон плеера
            // уже под нами, свой давал бы шов и другой оттенок).
            if (!splitMode) {
                if (albumColors != null) {
                    AnimatedPlayerBackground(
                        albumArtUri = albumArtUri,
                        coverUrl = coverUrl,
                        audioFileUri = audioFileUri,
                        albumId = albumId,
                        albumColors = albumColors
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0xFF1C1C2E))
                    )
                }
            }

            // Затемнение для читаемости: плоский Black α0.65 «гасил» весь цвет фона
            // в чёрную простыню, поэтому градиент — сверху прозрачнее (цвет обложки
            // виден), книзу плотнее (под текстом). Плотность зависит от темы.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LiquidSurfaces.queueScrim(isDark, splitMode))
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .windowInsetsPadding(WindowInsets.navigationBars)
            ) {
                // Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = LiquidMetrics.QueuePadding, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Queue",
                        color = LiquidSurfaces.onHeaderPrimary,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(50))
                            .background(Color.White.copy(alpha = 0.10f))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = onDismiss
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }

                // Now Playing section (Pinned at the top, does not scroll!)
                if (currentTrack != null) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = LiquidMetrics.QueuePadding)
                            .padding(bottom = 12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(LiquidMetrics.QueueNowPlayingCover)
                                    .clip(RoundedCornerShape(8.dp))
                            ) {
                                AlbumArtImage(
                                    uri = currentTrack.albumArtUri,
                                    coverUrl = currentTrack.coverUrl,
                                    audioFileUri = currentTrack.uri,
                                    albumId = currentTrack.albumId,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop
                                )
                            }
                            Spacer(modifier = Modifier.width(14.dp))
                            Column(
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    text = currentTrack.title,
                                    color = Color.White,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = currentTrack.artist,
                                    color = Color.White.copy(alpha = 0.60f),
                                    fontSize = 14.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            // Heart (Favorite) button
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .pressScale {
                                        scope.launch {
                                            libraryRepo.toggleFavorite(currentTrack)
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = if (isFavorite) Icons.Rounded.Favorite
                                    else Icons.Rounded.FavoriteBorder,
                                    contentDescription = null,
                                    tint = if (isFavorite) colors.accentRed
                                    else LiquidSurfaces.onHeaderSecondary,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                    }
                }

                // Реактивно: reorder/удаление обновляют список сразу.
                val queue by PlayerController.queueFlow.collectAsState()
                val currentIndex = PlayerController.getCurrentIndex()

                // Drag-reorder: ключ таскаемой строки + её текущее смещение.
                // Смещение держим объектом состояния и читаем только внутри
                // graphicsLayer: раньше это было обычное значение в состоянии
                // родителя, и каждый пиксель жеста рекомпозировал весь экран.
                var draggingKey by remember { mutableStateOf<String?>(null) }
                val dragOffsetY = remember { mutableStateOf(0f) }

                val listState = rememberLazyListState()
                // Наводимся на начало списка при открытии и при смене трека —
                // иначе очередь открывается там, где её оставили в прошлый раз.
                LaunchedEffect(visible) {
                    if (visible) listState.scrollToItem(0)
                }
                LaunchedEffect(currentTrack?.id) {
                    if (visible && !listState.isScrollInProgress && draggingKey == null) {
                        listState.animateScrollToItem(0)
                    }
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onRequestControls
                        )
                ) {
                // Стабильные ключи по id (дубли, если плейлист содержит один трек
                // дважды, получают суффикс #n) — иначе при перестановке узел строки
                // пересоздаётся и жест обрывается. В remember: раньше карта и новый
                // список строились на каждом проходе лямбды списка.
                val upNext = remember(queue, currentIndex) {
                    if (currentIndex >= 0 && currentIndex < queue.lastIndex) {
                        queue.subList(currentIndex + 1, queue.size).toList()
                    } else emptyList()
                }
                val upNextKeys = remember(upNext) {
                    val seen = HashMap<String, Int>()
                    upNext.map { t ->
                        val n = seen.getOrDefault(t.id, 0)
                        seen[t.id] = n + 1
                        if (n == 0) "q_${t.id}" else "q_${t.id}#$n"
                    }
                }

                // Раскладка по секциям. Границы приходят из движка абсолютными
                // индексами в очереди — переводим их в позиции внутри upNext.
                val manualCount = (sections.manualEnd - (currentIndex + 1))
                    .coerceIn(0, upNext.size)
                val contextCount = (sections.autoStart - sections.manualEnd)
                    .coerceIn(0, upNext.size - manualCount)
                val waveCount = upNext.size - manualCount - contextCount
                val sectionSpecs = listOf(
                    QueueSectionSpec("Далее", 0, manualCount, clearable = true),
                    QueueSectionSpec("Продолжение", manualCount, contextCount),
                    QueueSectionSpec("Волна", manualCount + contextCount, waveCount)
                ).filter { it.count > 0 }

                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(0.dp)
                ) {
                    sectionSpecs.forEach { spec ->
                        stickyHeader(key = "hdr_${spec.title}") {
                            QueueSectionHeader(
                                title = spec.title,
                                count = spec.count,
                                onClear = if (spec.clearable && queueEditable) {
                                    { PlayerController.clearManualSection() }
                                } else null
                            )
                        }
                        items(
                            count = spec.count,
                            key = { i -> upNextKeys[spec.from + i] }
                        ) { i ->
                            val idx = spec.from + i
                            DraggableQueueRow(
                                track = upNext[idx],
                                rowKey = upNextKeys[idx],
                                index = idx,
                                upNextLastIndex = upNext.lastIndex,
                                draggingKey = draggingKey,
                                dragOffsetY = dragOffsetY,
                                editable = queueEditable,
                                accent = colors.accentRed,
                                onDragStateChange = { key, offset ->
                                    draggingKey = key
                                    dragOffsetY.value = offset
                                },
                                absoluteIndex = { j -> PlayerController.getCurrentIndex() + 1 + j },
                                onClick = {
                                    PlayerController.playTrack(
                                        context,
                                        currentIndex + 1 + idx
                                    )
                                }
                            )
                        }
                    }

                    // Пусто — когда впереди ничего нет, а не когда очередь пуста
                    // целиком: на последнем треке альбома играющий трек в очереди
                    // есть, а список под ним был просто пустым прямоугольником.
                    if (upNext.isEmpty()) {
                        item {
                            // Текст по обстоятельствам: «очередь пуста» на повторе
                            // или на локальном треке — неправда, и выглядит поломкой.
                            val (icon, message) = when {
                                repeatMode != 0 ->
                                    Icons.Rounded.Repeat to "Повтор включён — играем по кругу"
                                !queueEditable ->
                                    Icons.Rounded.MusicNote to "Локальный трек играет отдельно"
                                else ->
                                    Icons.Rounded.Waves to "Подбираем продолжение"
                            }
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(
                                        imageVector = icon,
                                        contentDescription = null,
                                        tint = LiquidSurfaces.onHeaderPrimary.copy(alpha = 0.25f),
                                        modifier = Modifier.size(48.dp)
                                    )
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Text(
                                        text = message,
                                        color = LiquidSurfaces.onHeaderPrimary.copy(alpha = 0.40f),
                                        fontSize = 16.sp
                                    )
                                }
                            }
                        }
                    }

                    item { Spacer(modifier = Modifier.height(16.dp)) }
                }
                } // Box (clickable queue area)

            }
        }
    }
}

/** Одна секция очереди: заголовок и диапазон строк внутри списка предстоящего. */
private data class QueueSectionSpec(
    val title: String,
    val from: Int,
    val count: Int,
    val clearable: Boolean = false
)

/**
 * Заголовок секции.
 *
 * У Apple заголовки без счётчиков и «выталкиваются» следующей секцией. У нас
 * счётчик справа (сразу видно, сколько ещё ждёт) и заголовок примерзает, набирая
 * подложку, — так понятнее, где ты находишься в длинной очереди.
 */
@Composable
private fun QueueSectionHeader(
    title: String,
    count: Int,
    onClear: (() -> Unit)?
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(LiquidSurfaces.queueHeaderFill)
            .padding(horizontal = LiquidMetrics.QueuePadding, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            color = LiquidSurfaces.onHeaderPrimary.copy(alpha = 0.75f),
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = "$count",
            color = LiquidSurfaces.onHeaderPrimary.copy(alpha = 0.45f),
            fontSize = 12.sp
        )
        if (onClear != null) {
            Spacer(Modifier.width(10.dp))
            Box(
                modifier = Modifier
                    .clip(LiquidMetrics.Pill)
                    .clickable(onClick = onClear)
                    .padding(horizontal = 10.dp, vertical = 3.dp)
            ) {
                Text(
                    text = "Очистить",
                    color = LiquidSurfaces.onHeaderPrimary.copy(alpha = 0.60f),
                    fontSize = 12.sp
                )
            }
        }
    }
}

/**
 * Строка Up Next с двумя жестами:
 * - вертикальный drag за хэндл справа → перестановка в очереди (moveQueueItem);
 * - горизонтальный свайп по строке → удаление из очереди (removeQueueItem).
 * Состояние drag хранится у родителя по ключу строки — при reorder узел
 * не пересоздаётся (стабильные ключи) и жест не обрывается.
 */
@Composable
private fun DraggableQueueRow(
    track: Track,
    rowKey: String,
    index: Int,
    upNextLastIndex: Int,
    draggingKey: String?,
    dragOffsetY: State<Float>,
    editable: Boolean,
    accent: Color,
    onDragStateChange: (String?, Float) -> Unit,
    absoluteIndex: (Int) -> Int,
    onClick: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val rowHeightPx = remember(density) { with(density) { LiquidMetrics.QueueRowHeight.toPx() } }
    val removeThresholdPx = remember(density) { with(density) { LiquidMetrics.QueueSwipeThreshold.toPx() } }
    val indexState = rememberUpdatedState(index)
    val lastIndexState = rememberUpdatedState(upNextLastIndex)
    val removeOffset = remember(rowKey) { Animatable(0f) }
    val isDragging = draggingKey == rowKey

    fun moveBy(step: Int) {
        val from = indexState.value
        val to = (from + step).coerceIn(0, lastIndexState.value)
        if (to != from) PlayerController.moveQueueItem(absoluteIndex(from), absoluteIndex(to))
    }

    Box(
        modifier = Modifier
            .zIndex(if (isDragging) 1f else 0f)
            .fillMaxWidth()
            .height(LiquidMetrics.QueueRowHeight)
            .semantics {
                contentDescription = "${track.title}, ${track.artist}"
                if (editable) {
                    customActions = listOf(
                        CustomAccessibilityAction("Выше") { moveBy(-1); true },
                        CustomAccessibilityAction("Ниже") { moveBy(1); true },
                        CustomAccessibilityAction("Убрать из очереди") {
                            PlayerController.removeQueueItem(absoluteIndex(indexState.value)); true
                        }
                    )
                }
            }
    ) {
        // Подложка удаления: проступает по мере утягивания строки вбок. Значение
        // смещения читаем только в graphicsLayer, иначе каждый пиксель свайпа
        // рекомпозировал бы строку.
        Box(
            modifier = Modifier
                .matchParentSize()
                .graphicsLayer {
                    alpha = (abs(removeOffset.value) / removeThresholdPx).coerceIn(0f, 1f)
                }
                .background(LiquidSurfaces.queueDestructive(accent))
                .padding(horizontal = LiquidMetrics.QueuePadding)
        ) {
            Icon(
                imageVector = Icons.Rounded.DeleteOutline,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .size(22.dp)
                    .graphicsLayer { alpha = if (removeOffset.value > 0f) 1f else 0f }
            )
            Icon(
                imageVector = Icons.Rounded.DeleteOutline,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .size(22.dp)
                    .graphicsLayer { alpha = if (removeOffset.value < 0f) 1f else 0f }
            )
        }

    Row(
        modifier = Modifier
            .graphicsLayer {
                translationY = if (isDragging) dragOffsetY.value else 0f
                translationX = removeOffset.value
                alpha = 1f - (abs(removeOffset.value) / (removeThresholdPx * 3f))
                    .coerceAtMost(0.35f)
                shadowElevation = if (isDragging) LiquidMetrics.QueueDragElevation else 0f
                // Подъём строки: у Apple сжатие до 0.85 за 300 мс настолько
                // характерное, что читается как их приложение — берём едва заметное.
                val s = if (isDragging) LiquidMetrics.QueueDragLiftScale else 1f
                scaleX = s
                scaleY = s
            }
            .fillMaxSize()
            .background(
                if (isDragging) LiquidSurfaces.queueRowDragged else Color.Transparent
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .then(
                if (!editable) Modifier else Modifier.draggable(
                    orientation = Orientation.Horizontal,
                    state = rememberDraggableState { delta ->
                        scope.launch { removeOffset.snapTo(removeOffset.value + delta) }
                    },
                    onDragStopped = { velocity ->
                        val off = removeOffset.value
                        if (abs(off) > removeThresholdPx ||
                            abs(velocity) > LiquidMetrics.QueueSwipeVelocity
                        ) {
                            val target = if (off < 0 || (off == 0f && velocity < 0)) {
                                -LiquidMetrics.QueueSwipeFlyOut
                            } else {
                                LiquidMetrics.QueueSwipeFlyOut
                            }
                            scope.launch {
                                removeOffset.animateTo(target, tween(150))
                                PlayerController.removeQueueItem(absoluteIndex(indexState.value))
                                // Узел может переиспользоваться под другой трек — возвращаем на место.
                                removeOffset.snapTo(0f)
                            }
                        } else {
                            scope.launch {
                                removeOffset.animateTo(0f, spring(dampingRatio = 0.8f, stiffness = 400f))
                            }
                        }
                    }
                )
            )
            .padding(horizontal = LiquidMetrics.QueuePadding),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(LiquidMetrics.QueueRowCover)
                .clip(RoundedCornerShape(6.dp))
        ) {
            AlbumArtImage(
                uri = track.albumArtUri,
                coverUrl = track.coverUrl,
                audioFileUri = track.uri,
                albumId = track.albumId,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = track.title,
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = track.artist,
                color = Color.White.copy(alpha = 0.70f),
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(modifier = Modifier.width(8.dp))
        // Хэндл перестановки: вертикальный drag двигает трек по очереди. В
        // нередактируемом режиме место под него остаётся (иначе строки прыгают
        // при переключении источника), но жест не навешиваем вовсе — иначе он
        // съедал бы касание впустую.
        Box(
            modifier = Modifier
                .size(LiquidMetrics.QueueHandleSize)
                .then(
                    if (!editable) Modifier else Modifier.pointerInput(rowKey) {
                        var myIdx = 0
                        var acc = 0f
                        detectDragGestures(
                            onDragStart = {
                                myIdx = indexState.value
                                acc = 0f
                                onDragStateChange(rowKey, 0f)
                            },
                            onDrag = { change, amount ->
                                change.consume()
                                acc += amount.y
                                val steps = (acc / rowHeightPx).toInt()
                                if (steps != 0) {
                                    val target = (myIdx + steps)
                                        .coerceIn(0, lastIndexState.value)
                                    if (target != myIdx) {
                                        PlayerController.moveQueueItem(
                                            absoluteIndex(myIdx),
                                            absoluteIndex(target)
                                        )
                                        acc -= (target - myIdx) * rowHeightPx
                                        myIdx = target
                                    } else {
                                        // упёрлись в край — не копим смещение бесконечно
                                        acc = acc.coerceIn(-rowHeightPx, rowHeightPx)
                                    }
                                }
                                onDragStateChange(rowKey, acc)
                            },
                            onDragEnd = { onDragStateChange(null, 0f) },
                            onDragCancel = { onDragStateChange(null, 0f) }
                        )
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.DragHandle,
                contentDescription = null,
                tint = LiquidSurfaces.onHeaderPrimary.copy(alpha = if (editable) 0.55f else 0f),
                modifier = Modifier.size(LiquidMetrics.QueueHandleIcon)
            )
        }
    }
    } // Box (строка + подложка удаления)
}

