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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
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
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.Brush
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
import com.liquidmusicglass.engine.PlayerController
import com.liquidmusicglass.engine.Track
import com.liquidmusicglass.ui.glass.AlbumArtImage
import com.liquidmusicglass.ui.glass.AlbumColors
import com.liquidmusicglass.ui.glass.pressScale
import kotlinx.coroutines.launch
import kotlin.math.abs

private val AppleRed = Color(0xFFFC3C44)

/**
 * Queue overlay — открывается как LyricsScreen: fadeIn/fadeOut поверх FullPlayer.
 * НЕ использует slideIn/slideOut — только прозрачность.
 *
 * Фон: динамический из обложки (как LyricsBackground).
 * Кнопки управления: те же что в FullPlayer (Shuffle, Prev, Play/Pause, Next, Repeat).
 * Без ползунка громкости.
 */
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
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val libraryRepo = remember { com.liquidmusicglass.data.local.db.LibraryRepository.getInstance(context) }
    val isFavorite by if (currentTrack != null) {
        libraryRepo.isFavoriteFlow(currentTrack.id).collectAsState(initial = false)
    } else {
        remember { mutableStateOf(false) }
    }
    val scope = rememberCoroutineScope()

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(400)),
        exit = fadeOut(tween(350)),
        modifier = modifier
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Dynamic background from album art
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

            // Dark scrim for readability — раньше плоский Black α0.65 «гасил» весь
            // цвет фона в чёрную простыню. Градиент: сверху почти прозрачный (цвет
            // виден), книзу плотнее (под текстом очереди — читаемость). Текст белый,
            // так что верх можно держать светлым.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colorStops = arrayOf(
                                0.00f to Color.Black.copy(alpha = 0.18f),
                                0.45f to Color.Black.copy(alpha = 0.30f),
                                1.00f to Color.Black.copy(alpha = 0.48f)
                            )
                        )
                    )
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
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Queue",
                        color = Color.White,
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
                            .padding(horizontal = 20.dp)
                            .padding(bottom = 12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(60.dp)
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
                                    tint = if (isFavorite) Color(0xFFFC3C44)
                                    else Color.White.copy(alpha = 0.70f),
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
                var draggingKey by remember { mutableStateOf<String?>(null) }
                var dragOffsetY by remember { mutableStateOf(0f) }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onRequestControls
                        )
                ) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(0.dp)
                ) {
                    // Up Next section
                    val upNext = if (currentIndex >= 0 && currentIndex < queue.lastIndex) {
                        queue.subList(currentIndex + 1, queue.size)
                    } else emptyList()

                    if (upNext.isNotEmpty()) {
                        item(key = "upnext_header") {
                            Text(
                                text = "Up Next",
                                color = Color.White.copy(alpha = 0.75f),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(
                                    horizontal = 20.dp,
                                    vertical = 8.dp
                                )
                            )
                        }
                        // Стабильные ключи по id (дубли, если плейлист содержит
                        // один трек дважды, получают суффикс #n) — иначе при
                        // reorder узел строки пересоздаётся и жест обрывается.
                        val upNextKeys = run {
                            val seen = HashMap<String, Int>()
                            upNext.map { t ->
                                val n = seen.getOrDefault(t.id, 0)
                                seen[t.id] = n + 1
                                if (n == 0) "q_${t.id}" else "q_${t.id}#$n"
                            }
                        }
                        itemsIndexed(
                            upNext,
                            key = { index, _ -> upNextKeys[index] }
                        ) { idx, track ->
                            DraggableQueueRow(
                                track = track,
                                rowKey = upNextKeys[idx],
                                index = idx,
                                upNextLastIndex = upNext.lastIndex,
                                draggingKey = draggingKey,
                                dragOffsetY = dragOffsetY,
                                onDragStateChange = { key, offset ->
                                    draggingKey = key
                                    dragOffsetY = offset
                                },
                                absoluteIndex = { i -> PlayerController.getCurrentIndex() + 1 + i },
                                onClick = {
                                    PlayerController.playTrack(
                                        context,
                                        currentIndex + 1 + idx
                                    )
                                }
                            )
                        }
                    }

                    if (queue.isEmpty()) {
                        item {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(
                                        imageVector = Icons.Rounded.MusicNote,
                                        contentDescription = null,
                                        tint = Color.White.copy(alpha = 0.25f),
                                        modifier = Modifier.size(48.dp)
                                    )
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Text(
                                        text = "Queue is empty",
                                        color = Color.White.copy(alpha = 0.40f),
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
    dragOffsetY: Float,
    onDragStateChange: (String?, Float) -> Unit,
    absoluteIndex: (Int) -> Int,
    onClick: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val rowHeightPx = remember(density) { with(density) { 56.dp.toPx() } }
    val removeThresholdPx = remember(density) { with(density) { 96.dp.toPx() } }
    val indexState = rememberUpdatedState(index)
    val lastIndexState = rememberUpdatedState(upNextLastIndex)
    val removeOffset = remember(rowKey) { Animatable(0f) }
    val isDragging = draggingKey == rowKey

    Row(
        modifier = Modifier
            .zIndex(if (isDragging) 1f else 0f)
            .graphicsLayer {
                translationY = if (isDragging) dragOffsetY else 0f
                translationX = removeOffset.value
                alpha = 1f - (abs(removeOffset.value) / (removeThresholdPx * 3f))
                    .coerceAtMost(0.35f)
                shadowElevation = if (isDragging) 12f else 0f
            }
            .fillMaxWidth()
            .height(56.dp)
            .background(
                if (isDragging) Color.White.copy(alpha = 0.06f) else Color.Transparent
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .draggable(
                orientation = Orientation.Horizontal,
                state = rememberDraggableState { delta ->
                    scope.launch { removeOffset.snapTo(removeOffset.value + delta) }
                },
                onDragStopped = { velocity ->
                    val off = removeOffset.value
                    if (abs(off) > removeThresholdPx || abs(velocity) > 3000f) {
                        val target = if (off < 0 || (off == 0f && velocity < 0)) -1400f else 1400f
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
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
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
        // Хэндл перестановки: вертикальный drag двигает трек по очереди.
        Box(
            modifier = Modifier
                .size(36.dp)
                .pointerInput(rowKey) {
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
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.DragHandle,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.55f),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

