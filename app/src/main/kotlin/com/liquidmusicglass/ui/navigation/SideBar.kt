package com.liquidmusicglass.ui.navigation

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.liquidmusicglass.ui.glass.liquidClickable
import com.liquidmusicglass.ui.icons.LiquidGlyphs
import com.liquidmusicglass.ui.theme.LiquidTheme

/**
 * Боковая навигация для широких окон (телефон-альбом / планшет) — замена
 * нижнего [BottomBar]. По референсу друга: логотип LMG сверху, пункты
 * навигации, разделитель и профиль внизу. Стиль — НАШ: палитра из
 * [LiquidTheme.colors], поэтому корректно работает и в светлой, и в тёмной
 * теме. Индексы вкладок совпадают с BottomBar/AppRoot:
 *   Wave = 0, Search = 1, Playlist = 2, Settings = 3, New = 4.
 */
val SideBarWidth = 176.dp

private data class SideNavItem(val icon: ImageVector, val label: String, val index: Int)

@Composable
fun SideBar(
    selectedIndex: Int,
    onItemSelected: (Int) -> Unit,
    onOpenProfile: () -> Unit,
    profileName: String?,
    avatarUrl: String?,
    modifier: Modifier = Modifier,
) {
    val lc = LiquidTheme.colors
    val items = remember {
        listOf(
            SideNavItem(LiquidGlyphs.Home, "Home", 0),
            SideNavItem(LiquidGlyphs.Search, "Search", 1),
            SideNavItem(LiquidGlyphs.Star, "New", 4),
            SideNavItem(LiquidGlyphs.Playlist, "Playlist", 2),
            SideNavItem(LiquidGlyphs.Settings, "Settings", 3),
        )
    }

    Column(
        modifier = modifier
            .fillMaxHeight()
            .width(SideBarWidth)
            .padding(10.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(lc.cardSurface)
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = 10.dp, vertical = 12.dp)
    ) {
        // Логотип
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 6.dp, bottom = 16.dp)
        ) {
            Text("LMG", color = lc.accent, fontSize = 19.sp, fontWeight = FontWeight.Bold)
        }

        // Пункты навигации
        items.forEach { item ->
            SideNavRow(
                item = item,
                selected = item.index == selectedIndex,
                onClick = { onItemSelected(item.index) }
            )
            Spacer(Modifier.height(2.dp))
        }

        Spacer(Modifier.weight(1f))

        // Разделитель + профиль внизу (по структуре референса)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(0.5.dp)
                .background(lc.divider)
        )
        Spacer(Modifier.height(10.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .liquidClickable(onClick = onOpenProfile)
                .padding(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(if (lc.isDark) Color(0xFF2C2C2E) else Color(0xFFE3E3E8)),
                contentAlignment = Alignment.Center
            ) {
                if (!avatarUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = avatarUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    Icon(Icons.Rounded.Person, null, tint = lc.iconMuted, modifier = Modifier.size(16.dp))
                }
            }
            Spacer(Modifier.width(8.dp))
            Column {
                Text(
                    profileName?.takeIf { it.isNotBlank() } ?: "Guest",
                    color = lc.textPrimary, fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold, maxLines = 1
                )
                Text("LiquidMusicGlass", color = lc.textTertiary, fontSize = 9.5.sp, maxLines = 1)
            }
        }
    }
}

@Composable
private fun SideNavRow(item: SideNavItem, selected: Boolean, onClick: () -> Unit) {
    val lc = LiquidTheme.colors
    val color by animateColorAsState(
        if (selected) lc.accent else lc.textSecondary, tween(180), label = "sideColor"
    )
    val bg by animateColorAsState(
        if (selected) lc.accent.copy(alpha = 0.14f) else Color.Transparent, tween(180), label = "sideBg"
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
        modifier = Modifier
            .fillMaxWidth()
            .height(38.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .liquidClickable(onClick = onClick)
            .padding(horizontal = 10.dp)
    ) {
        Icon(item.icon, contentDescription = item.label, tint = color, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(
            item.label, color = color, fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
        )
    }
}
