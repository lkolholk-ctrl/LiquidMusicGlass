package com.liquidmusicglass.ui.navigation

import com.liquidmusicglass.ui.icons.LiquidGlyphs
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.liquidmusicglass.ui.glass.liquidClickable
import com.liquidmusicglass.ui.theme.LiquidMotion
import com.liquidmusicglass.ui.theme.LiquidTheme

/**
 * Плоский нижний бар в стиле Яндекс Музыки — без стекла/блюра (тяжёлые
 * эффекты убраны ради производительности).
 *
 * 4 вкладки сопоставлены с глобальными индексами экранов в [AppRoot]:
 * Wave = 0, New = 4, Playlist = 2, Settings = 3. Индекс 1 (Поиск) не в баре —
 * поиск открывается с экрана Wave. Профиль вынесен в иконку слева вверху Wave.
 */
private data class BottomNavItem(
    val icon: ImageVector,
    val label: String,
    val index: Int
)

// Бледно-зелёный акцент выбранного таба (жёлтый Яндекса убран).
private val WaveAccent = Color(0xFF88C088)

@Composable
fun BottomBar(
    selectedIndex: Int,
    onItemSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val items = remember {
        listOf(
            BottomNavItem(LiquidGlyphs.Equalizer, "Wave", 0),
            BottomNavItem(LiquidGlyphs.Star, "New", 4),
            BottomNavItem(Icons.Rounded.Movie, "Video", SIDE_INDEX_VIDEO),
            BottomNavItem(LiquidGlyphs.Playlist, "Playlist", 2),
            BottomNavItem(LiquidGlyphs.Settings, "Settings", 3)
        )
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(60.dp)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        items.forEach { item ->
            BottomTab(
                item = item,
                selected = item.index == selectedIndex,
                onClick = { onItemSelected(item.index) },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun BottomTab(
    item: BottomNavItem,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val inactiveColor =
        if (LiquidTheme.colors.isDark) Color.White.copy(alpha = 0.55f)
        else Color.Black.copy(alpha = 0.5f)

    val color by animateColorAsState(
        targetValue = if (selected) WaveAccent else inactiveColor,
        animationSpec = tween(180),
        label = "tabColor"
    )
    // Активная вкладка чуть крупнее и пружинисто «подпрыгивает» при выборе
    // (низкий damping → overshoot). Неактивная — спокойный масштаб 1.
    val iconScale by animateFloatAsState(
        targetValue = if (selected) 1.15f else 1f,
        animationSpec = spring(dampingRatio = 0.42f, stiffness = 520f),
        label = "tabScale"
    )

    Box(
        modifier = modifier
            .height(60.dp)
            .liquidClickable(pressedScale = LiquidMotion.PressIcon, onClick = onClick)
            .padding(vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = item.icon,
            contentDescription = item.label,
            modifier = Modifier
                .size(34.dp)
                .graphicsLayer { scaleX = iconScale; scaleY = iconScale },
            tint = color
        )
    }
}
