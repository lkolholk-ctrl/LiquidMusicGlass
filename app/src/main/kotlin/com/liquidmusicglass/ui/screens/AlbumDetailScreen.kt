package com.liquidmusicglass.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.liquidmusicglass.api.icm.IcmAlbumResponse
import com.liquidmusicglass.api.icm.IcmRepository
import com.liquidmusicglass.api.icm.toTrack
import com.liquidmusicglass.engine.PlayerController
import com.liquidmusicglass.ui.glass.AlbumArtImage
import com.liquidmusicglass.ui.glass.liquidClickable
import com.liquidmusicglass.ui.theme.LiquidMetrics
import com.liquidmusicglass.ui.theme.LiquidMotion
import com.liquidmusicglass.ui.theme.LiquidSurfaces
import com.liquidmusicglass.ui.theme.LiquidTheme

/** Обложки каталога приходят огромными; для экрана это лишний трафик и память. */
private fun String?.toAlbumThumb(): String? = this
    ?.replace("1000x1000", "600x600")
    ?.replace("1500x1500", "600x600")
    ?.replace("300x300", "600x600")

/**
 * Экран альбома — в той же подаче, что и экран артиста.
 *
 * Отличие одно: у артиста в шапке фотография во всю ширину, здесь обложка по
 * центру. Она квадратная, и растягивать её на всю ширину значит либо обрезать
 * половину, либо оставлять пустые поля по краям.
 */
@Composable
fun AlbumDetailScreen(
    albumId: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val colors = LiquidTheme.colors
    val isDark = colors.isDark

    var album by remember { mutableStateOf<IcmAlbumResponse?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(albumId) {
        isLoading = true
        error = null
        try {
            album = IcmRepository.getAlbum(albumId)
            if (album == null) error = IcmRepository.lastError.value ?: "Album not found"
        } catch (e: Exception) {
            error = e.message
        } finally {
            isLoading = false
        }
    }

    val albumTracks = remember(album) {
        album?.tracks?.map { it.toTrack() }?.distinctBy { it.id } ?: emptyList()
    }

    val listState = rememberLazyListState()
    val showTopBarTitle by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 320
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(LiquidSurfaces.sheet(isDark))) {
        when {
            isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = colors.accent, modifier = Modifier.size(32.dp))
            }

            error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = error.orEmpty(),
                    color = LiquidSurfaces.textSecondary(isDark),
                    fontSize = 14.sp
                )
            }

            else -> {
                val info = album?.album
                LazyColumn(
                    state = listState,
                    // Ограничение ширины для планшетов и ландшафта: без него строки
                    // трека растягиваются на весь экран, и номер с длительностью
                    // оказываются в разных концах.
                    modifier = Modifier
                        .fillMaxSize()
                        .widthIn(max = 640.dp)
                        .align(Alignment.TopCenter),
                    contentPadding = PaddingValues(bottom = 140.dp)
                ) {
                    item {
                        AlbumHeader(
                            title = info?.title.orEmpty(),
                            artist = info?.artist.orEmpty(),
                            year = info?.year,
                            trackCount = albumTracks.size,
                            totalDurationMs = albumTracks.sumOf { it.durationMs },
                            coverUrl = info?.cover.toAlbumThumb(),
                            isDark = isDark,
                            onPlay = {
                                if (albumTracks.isNotEmpty()) {
                                    PlayerController.play(context, albumTracks, 0)
                                }
                            },
                            onShuffle = {
                                if (albumTracks.isNotEmpty()) {
                                    PlayerController.play(context, albumTracks.shuffled(), 0)
                                }
                            }
                        )
                    }

                    itemsIndexed(albumTracks, key = { _, track -> track.id }) { index, track ->
                        AlbumTrackRow(
                            position = index + 1,
                            title = track.title,
                            durationMs = track.durationMs,
                            isDark = isDark,
                            showDivider = index < albumTracks.lastIndex,
                            onClick = { PlayerController.play(context, albumTracks, index) }
                        )
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    if (showTopBarTitle) LiquidSurfaces.sheet(isDark) else Color.Transparent
                )
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(LiquidMetrics.GlassButtonSize)
                    .clip(CircleShape)
                    .background(LiquidSurfaces.card(isDark))
                    .liquidClickable(pressedScale = LiquidMotion.PressButton, onClick = onBack),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = "Back",
                    tint = LiquidSurfaces.textPrimary(isDark),
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(
                text = album?.album?.title.orEmpty(),
                color = LiquidSurfaces.textPrimary(isDark),
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.alpha(if (showTopBarTitle) 1f else 0f)
            )
        }
    }
}

/**
 * Шапка альбома: обложка, название, исполнитель, факты и кнопки.
 *
 * Кнопки стоят прямо под обложкой и уезжают вместе с ней — это один смысловой
 * блок, и разъезжаться при прокрутке им нельзя.
 */
@Composable
private fun AlbumHeader(
    title: String,
    artist: String,
    year: String?,
    trackCount: Int,
    totalDurationMs: Long,
    coverUrl: String?,
    isDark: Boolean,
    onPlay: () -> Unit,
    onShuffle: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = LiquidMetrics.ScreenPadding,
                end = LiquidMetrics.ScreenPadding,
                top = 72.dp,
                bottom = 16.dp
            ),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        AlbumArtImage(
            uri = null,
            coverUrl = coverUrl,
            contentDescription = title,
            modifier = Modifier
                .size(260.dp)
                .clip(LiquidMetrics.CardShape),
            contentScale = ContentScale.Crop
        )

        Spacer(Modifier.height(20.dp))

        Text(
            text = title,
            color = LiquidSurfaces.textPrimary(isDark),
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = LiquidMetrics.SectionTitleSpacing,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = artist,
            color = LiquidSurfaces.textSecondary(isDark),
            fontSize = LiquidMetrics.RowTitle,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(top = 4.dp)
        )

        // Год, число песен и общее время. Длительность считаем сами: у альбома
        // каталог её не отдаёт, а без неё строка фактов выглядит куцей.
        val facts = buildList {
            year?.takeIf { it.isNotBlank() }?.let(::add)
            if (trackCount > 0) add("$trackCount songs")
            if (totalDurationMs > 0) add(formatTotalDuration(totalDurationMs))
        }
        if (facts.isNotEmpty()) {
            Text(
                text = facts.joinToString(" · "),
                color = LiquidSurfaces.textTertiary(isDark),
                fontSize = LiquidMetrics.Caption,
                modifier = Modifier.padding(top = 6.dp)
            )
        }

        Spacer(Modifier.height(20.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            AlbumActionButton(
                label = "Play",
                icon = Icons.Rounded.PlayArrow,
                filled = true,
                isDark = isDark,
                onClick = onPlay
            )
            AlbumActionButton(
                label = "Shuffle",
                icon = Icons.Rounded.Shuffle,
                filled = false,
                isDark = isDark,
                onClick = onShuffle
            )
        }
    }
}

@Composable
private fun RowScope.AlbumActionButton(
    label: String,
    icon: ImageVector,
    filled: Boolean,
    isDark: Boolean,
    onClick: () -> Unit
) {
    // Здесь кнопки лежат на листе, а не поверх фотографии, поэтому заливка берётся
    // из палитры темы: белая кнопка в светлой теме слилась бы с фоном.
    val background =
        if (filled) LiquidSurfaces.textPrimary(isDark) else LiquidSurfaces.card(isDark)
    val contentColor =
        if (filled) LiquidSurfaces.sheet(isDark) else LiquidSurfaces.textPrimary(isDark)

    Row(
        modifier = Modifier
            .weight(1f)
            .height(LiquidMetrics.ActionButtonHeight)
            .clip(CircleShape)
            .background(background)
            .liquidClickable(pressedScale = LiquidMotion.PressButton, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = label,
            color = contentColor,
            fontSize = LiquidMetrics.ActionLabel,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun AlbumTrackRow(
    position: Int,
    title: String,
    durationMs: Long,
    isDark: Boolean,
    showDivider: Boolean,
    onClick: () -> Unit
) {
    Column(modifier = Modifier.padding(horizontal = LiquidMetrics.ScreenPadding)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(LiquidMetrics.CoverShapeSmall)
                .liquidClickable(pressedScale = LiquidMotion.PressButton, onClick = onClick)
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "$position",
                color = LiquidSurfaces.textTertiary(isDark),
                fontSize = LiquidMetrics.LinkLabel,
                modifier = Modifier.width(28.dp)
            )
            Text(
                text = title,
                color = LiquidSurfaces.textPrimary(isDark),
                fontSize = LiquidMetrics.RowTitle,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (durationMs > 0) {
                Text(
                    text = formatTrackDuration(durationMs),
                    color = LiquidSurfaces.textTertiary(isDark),
                    fontSize = LiquidMetrics.Caption
                )
            }
        }
        if (showDivider) {
            // Разделитель начинается под текстом, а не под номером: так список
            // читается колонкой, а не решёткой.
            Box(
                modifier = Modifier
                    .padding(start = 28.dp)
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(LiquidSurfaces.divider(isDark))
            )
        }
    }
}

private fun formatTrackDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "$minutes:${seconds.toString().padStart(2, '0')}"
}

private fun formatTotalDuration(ms: Long): String {
    val totalMinutes = ms / 60000
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return if (hours > 0) "$hours h $minutes min" else "$minutes min"
}
