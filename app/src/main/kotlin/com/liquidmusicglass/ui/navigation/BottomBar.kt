package com.liquidmusicglass.ui.navigation

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.liquidmusicglass.ui.theme.AppFontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
            BottomNavItem(Icons.Rounded.GraphicEq, "Wave", 0),
            BottomNavItem(Icons.Rounded.AutoAwesome, "New", 4),
            BottomNavItem(Icons.AutoMirrored.Rounded.PlaylistPlay, "Playlist", 2),
            BottomNavItem(Icons.Rounded.Settings, "Settings", 3)
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

    val interaction = remember { MutableInteractionSource() }

    Column(
        modifier = modifier
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick
            )
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = item.icon,
            contentDescription = item.label,
            modifier = Modifier.size(24.dp),
            tint = color
        )
        Spacer(modifier = Modifier.height(3.dp))
        Text(
            text = item.label,
            color = color,
            fontSize = 10.sp,
            fontFamily = AppFontFamily,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}
