package com.liquidmusicglass.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.liquidmusicglass.ui.glass.AlbumArtImage
import com.liquidmusicglass.ui.glass.liquidClickable
import com.liquidmusicglass.ui.theme.LiquidMetrics
import com.liquidmusicglass.ui.theme.LiquidMotion
import com.liquidmusicglass.ui.theme.LiquidSurfaces

/**
 * Общие части экранов-подборок: альбом, плейлист, избранное, история.
 *
 * Живут отдельно намеренно. Пока каждый экран рисовал свою шапку и свою строку
 * трека, они неизбежно расходились в мелочах — то отступ на два пункта другой,
 * то разделитель начинается не оттуда. Здесь одна реализация на всех, и правка
 * применяется сразу везде.
 */

/** Обложки каталога приходят огромными; для экрана это лишний трафик и память. */
fun String?.toDetailThumb(): String? = this
    ?.replace("1000x1000", "600x600")
    ?.replace("1500x1500", "600x600")
    ?.replace("300x300", "600x600")

/**
 * Шапка подборки: обложка во всю ширину, поверх неё название и кнопки.
 *
 * Занимает примерно половину экрана и считается от его высоты, а не задана
 * числом: на маленьком телефоне фиксированная высота съела бы весь первый экран,
 * на большом — выглядела бы полоской.
 *
 * Снизу к ней примыкает верхушка листа контента. Она нарисована здесь же, внутри
 * общего контейнера: если сдвигать лист отдельным элементом списка, уезжает
 * только он, а следующие остаются на месте — между ними появляется пустая полоса.
 */
@Composable
fun DetailHeader(
    title: String,
    subtitle: String,
    facts: List<String>,
    coverUrl: String?,
    isDark: Boolean,
    onPlay: () -> Unit,
    onShuffle: () -> Unit
) {
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    val headerHeight = (screenHeight * 0.52f).coerceIn(360.dp, 560.dp)

    Box(modifier = Modifier.fillMaxWidth()) {
        Box(modifier = Modifier.fillMaxWidth().height(headerHeight)) {
            Box(modifier = Modifier.fillMaxSize().clipToBounds()) {
                AlbumArtImage(
                    uri = null,
                    coverUrl = coverUrl,
                    contentDescription = title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
                // Затемнение снизу: белый текст поверх светлой обложки иначе
                // нечитаем, а обложки бывают любые.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                0f to Color.Transparent,
                                0.45f to Color.Black.copy(alpha = 0.15f),
                                1f to Color.Black.copy(alpha = 0.85f)
                            )
                        )
                )
            }

            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(
                        start = LiquidMetrics.ScreenPadding,
                        end = LiquidMetrics.ScreenPadding,
                        // Ровно столько, чтобы кнопки не ушли под край листа.
                        bottom = LiquidMetrics.SheetOverlap + 8.dp
                    )
            ) {
                Text(
                    text = title,
                    color = LiquidSurfaces.onHeaderPrimary,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-1.2).sp,
                    lineHeight = 36.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (subtitle.isNotBlank()) {
                    Text(
                        text = subtitle,
                        color = LiquidSurfaces.onHeaderSecondary,
                        fontSize = LiquidMetrics.HeaderCaption,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
                if (facts.isNotEmpty()) {
                    Text(
                        text = facts.joinToString(" · "),
                        color = LiquidSurfaces.onHeaderSecondary,
                        fontSize = LiquidMetrics.Caption,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }

                Spacer(Modifier.height(14.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    DetailActionButton(
                        label = "Play",
                        icon = Icons.Rounded.PlayArrow,
                        filled = true,
                        isDark = isDark,
                        onPhoto = true,
                        onClick = onPlay
                    )
                    DetailActionButton(
                        label = "Shuffle",
                        icon = Icons.Rounded.Shuffle,
                        filled = false,
                        isDark = isDark,
                        onPhoto = true,
                        onClick = onShuffle
                    )
                }
            }
        }

        DetailSheetTop(isDark = isDark, modifier = Modifier.align(Alignment.BottomCenter))
    }
}

/** Верхушка листа контента: наезжает на шапку и скруглена сверху. */
@Composable
fun DetailSheetTop(isDark: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(LiquidMetrics.SheetShape)
            .background(LiquidSurfaces.sheet(isDark))
            .padding(top = 12.dp, bottom = 4.dp),
        contentAlignment = Alignment.TopCenter
    ) {
        Box(
            modifier = Modifier
                .size(width = 36.dp, height = 5.dp)
                .clip(CircleShape)
                .background(LiquidSurfaces.grabber(isDark))
        )
    }
}

@Composable
fun RowScope.DetailActionButton(
    label: String,
    icon: ImageVector,
    filled: Boolean,
    isDark: Boolean,
    onPhoto: Boolean = false,
    onClick: () -> Unit
) {
    // Поверх обложки главная кнопка всегда белая: под ней может быть любой кадр,
    // и только плотная заливка гарантирует читаемость. На листе — цвета темы,
    // иначе белая кнопка в светлой теме слилась бы с фоном.
    val background = when {
        onPhoto && filled -> Color.White
        onPhoto -> LiquidSurfaces.glassAction
        filled -> LiquidSurfaces.textPrimary(isDark)
        else -> LiquidSurfaces.card(isDark)
    }
    val contentColor = when {
        onPhoto && filled -> Color.Black
        onPhoto -> Color.White
        filled -> LiquidSurfaces.sheet(isDark)
        else -> LiquidSurfaces.textPrimary(isDark)
    }

    Row(
        modifier = Modifier
            .weight(1f)
            .height(LiquidMetrics.ActionButtonHeight)
            .shadow(
                elevation = if (filled) LiquidMetrics.ButtonElevation else 2.dp,
                shape = CircleShape,
                ambientColor = LiquidSurfaces.shadowTint(isDark),
                spotColor = LiquidSurfaces.shadowTint(isDark)
            )
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

/**
 * Строка трека.
 *
 * @param coverUrl если задана — вместо номера показывается обложка. У альбома
 *   обложка одна на всех, там нужнее номер; в плейлисте и истории треки разные,
 *   и обложка помогает узнать вещь быстрее номера.
 */
@Composable
fun DetailTrackRow(
    position: Int,
    title: String,
    subtitle: String?,
    durationMs: Long,
    coverUrl: String?,
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
                .padding(vertical = if (coverUrl != null) 8.dp else 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (coverUrl != null) {
                AlbumArtImage(
                    uri = null,
                    coverUrl = coverUrl,
                    contentDescription = title,
                    modifier = Modifier
                        .size(LiquidMetrics.TrackCoverSize)
                        .shadow(
                            elevation = LiquidMetrics.CoverElevation,
                            shape = LiquidMetrics.CoverShapeSmall,
                            ambientColor = LiquidSurfaces.shadowTint(isDark),
                            spotColor = LiquidSurfaces.shadowTint(isDark)
                        )
                        .clip(LiquidMetrics.CoverShapeSmall),
                    contentScale = ContentScale.Crop
                )
                Spacer(Modifier.width(14.dp))
            } else {
                Text(
                    text = "$position",
                    color = LiquidSurfaces.textTertiary(isDark),
                    fontSize = LiquidMetrics.LinkLabel,
                    modifier = Modifier.width(28.dp)
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = LiquidSurfaces.textPrimary(isDark),
                    fontSize = LiquidMetrics.RowTitle,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = subtitle,
                        color = LiquidSurfaces.textSecondary(isDark),
                        fontSize = LiquidMetrics.Caption,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }

            if (durationMs > 0) {
                Text(
                    text = formatTrackDuration(durationMs),
                    color = LiquidSurfaces.textTertiary(isDark),
                    fontSize = LiquidMetrics.Caption,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
        }
        if (showDivider) {
            // Разделитель начинается под текстом, а не под номером или обложкой:
            // так список читается колонкой, а не решёткой.
            Box(
                modifier = Modifier
                    .padding(start = if (coverUrl != null) LiquidMetrics.DividerInset - LiquidMetrics.ScreenPadding else 28.dp)
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(LiquidSurfaces.divider(isDark))
            )
        }
    }
}

/**
 * Верхняя панель: кнопка возврата всегда, название — только после прокрутки.
 * Пока шапка видна целиком, дублировать её название в панели незачем.
 */
@Composable
fun DetailTopBar(
    title: String,
    showTitle: Boolean,
    isDark: Boolean,
    onBack: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (showTitle) LiquidSurfaces.sheet(isDark) else Color.Transparent)
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(LiquidMetrics.GlassButtonSize)
                .clip(CircleShape)
                // Пока шапка видна, кнопка лежит поверх обложки: там нужен
                // стеклянный фон, иначе на светлом кадре её не видно.
                .background(
                    if (showTitle) LiquidSurfaces.card(isDark) else LiquidSurfaces.glassFill
                )
                .liquidClickable(pressedScale = LiquidMotion.PressButton, onClick = onBack),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = "Back",
                tint = if (showTitle) LiquidSurfaces.textPrimary(isDark) else Color.White,
                modifier = Modifier.size(20.dp)
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = title,
            color = LiquidSurfaces.textPrimary(isDark),
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.alpha(if (showTitle) 1f else 0f)
        )
    }
}

fun formatTrackDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "$minutes:${seconds.toString().padStart(2, '0')}"
}

fun formatTotalDuration(ms: Long): String {
    val totalMinutes = ms / 60000
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return if (hours > 0) "$hours h $minutes min" else "$minutes min"
}
