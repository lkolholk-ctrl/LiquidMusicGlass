package com.liquidmusicglass.ui.screens

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.liquidmusicglass.data.local.db.AppDatabase
import com.liquidmusicglass.data.local.db.ListenHistoryEntity
import com.liquidmusicglass.engine.PlayerController
import com.liquidmusicglass.engine.Track
import com.liquidmusicglass.ui.glass.AlbumArtImage
import com.liquidmusicglass.ui.glass.liquidClickable
import com.liquidmusicglass.ui.theme.LiquidMotion
import com.liquidmusicglass.ui.theme.LiquidTheme
import kotlinx.coroutines.launch

/**
 * Экран «История прослушивания» — реальный список из Room ([ListenHistoryEntity]),
 * реактивно обновляется (Flow). Тап по строке — играет трек. Есть очистка.
 */
@Composable
fun HistoryScreen(
    onBack: () -> Unit = {},
    showBack: Boolean = true,
    title: String = "History"
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lc = LiquidTheme.colors

    val dao = remember { AppDatabase.getInstance(context).listenHistoryDao() }
    val history by remember { dao.observe() }.collectAsState(initial = emptyList())

    // Адаптив: в широком окне (телефон-альбом / планшет) не растягиваем строки
    // на всю ширину — центрируем список узкой колонкой ~600dp боковыми отступами.
    val win = com.liquidmusicglass.ui.rememberWindowInfo()
    val histSidePad = if (win.useSideBySide)
        (((win.widthDp - 600) / 2).coerceAtLeast(20)).dp else 20.dp

    Box(modifier = Modifier.fillMaxSize().background(lc.settingsBackground)) {
        Column(modifier = Modifier.fillMaxSize()) {
            Spacer(modifier = Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
            Spacer(modifier = Modifier.height(12.dp))

            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (showBack) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(if (lc.isDark) Color(0xFF1C1C1E) else Color(0xFFF2F2F7))
                            .liquidClickable(pressedScale = LiquidMotion.PressIcon) { onBack() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = null,
                            tint = lc.iconDefault,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                }
                Text(
                    text = title,
                    color = lc.textPrimary,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                if (history.isNotEmpty()) {
                    Text(
                        text = "Clear",
                        color = lc.accent,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .liquidClickable(pressedScale = LiquidMotion.PressButton) { scope.launch { dao.clear() } }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (history.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Nothing played yet",
                        color = lc.textSecondary,
                        fontSize = 15.sp
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = histSidePad, end = histSidePad, bottom = 96.dp
                    )
                ) {
                    items(history, key = { it.trackId }) { entry ->
                        HistoryRow(entry = entry, onClick = { playEntry(context, entry) })
                    }
                }
            }
        }
    }
}

private fun playEntry(context: android.content.Context, entry: ListenHistoryEntity) {
    val track = Track(
        id = entry.trackId,
        title = entry.title,
        artist = entry.artist,
        albumName = "",
        uri = Uri.parse("https://byicloud.online/track/${entry.trackId}"),
        durationMs = entry.durationMs,
        albumId = -1L,
        coverUrl = entry.coverUrl
    )
    PlayerController.playFromList(context, listOf(track), 0)
}

@Composable
private fun HistoryRow(entry: ListenHistoryEntity, onClick: () -> Unit) {
    val lc = LiquidTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .liquidClickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AlbumArtImage(
            uri = null,
            coverUrl = entry.coverUrl,
            contentDescription = entry.title,
            modifier = Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(10.dp))
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.title,
                color = lc.textPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = entry.artist,
                color = lc.textSecondary,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = relativeTime(entry.playedAt),
            color = lc.textTertiary,
            fontSize = 12.sp
        )
    }
}

/** Грубое относительное время: «just now / 5m / 3h / 2d». */
private fun relativeTime(playedAt: Long): String {
    if (playedAt <= 0L) return ""
    val diff = System.currentTimeMillis() - playedAt
    val min = diff / 60_000L
    return when {
        min < 1 -> "now"
        min < 60 -> "${min}m"
        min < 60 * 24 -> "${min / 60}h"
        else -> "${min / (60 * 24)}d"
    }
}
