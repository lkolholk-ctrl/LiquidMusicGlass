package com.liquidmusicglass.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.liquidmusicglass.ui.glass.liquidClickable
import com.liquidmusicglass.ui.icons.LiquidGlyphs
import com.liquidmusicglass.ui.theme.LiquidMotion
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Текущая секция раздела ЯМ — общий стейт между [YandexMusicScreen] и его
 * нижним баром, который рисует [com.liquidmusicglass.ui.AppRoot] вместо
 * основного бара, пока открыт экран ЯМ.
 */
object YandexSection {
    const val WAVE = 0
    const val SEARCH = 1
    const val LIKED = 2
    const val PLAYLISTS = 3
    const val AUTH = 4

    val current = MutableStateFlow(SEARCH)
    fun set(section: Int) { current.value = section }
}

private val YandexYellowBar = Color(0xFFFFCC00)

private data class YandexBarItem(val icon: ImageVector, val section: Int, val label: String)

/**
 * Нижний бар раздела ЯМ: пять секций, только иконки (без подписей), жёлтый
 * акцент активной. Фон — с лёгким жёлтым оттенком, чтобы бар читался как
 * «фирменный» для ЯМ и отличался от основного бара приложения.
 */
@Composable
fun YandexBottomBar(
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val items = listOf(
        YandexBarItem(LiquidGlyphs.Equalizer, YandexSection.WAVE, "Wave"),
        YandexBarItem(LiquidGlyphs.Search, YandexSection.SEARCH, "Search"),
        YandexBarItem(LiquidGlyphs.Heart, YandexSection.LIKED, "Liked"),
        YandexBarItem(LiquidGlyphs.Playlist, YandexSection.PLAYLISTS, "Playlists"),
        YandexBarItem(LiquidGlyphs.Person, YandexSection.AUTH, "Account"),
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(60.dp)
            .background(YandexYellowBar.copy(alpha = 0.10f))
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        items.forEach { item ->
            YandexBarTab(
                item = item,
                selected = item.section == selected,
                onClick = { onSelect(item.section) },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun YandexBarTab(
    item: YandexBarItem,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val color by animateColorAsState(
        targetValue = if (selected) YandexYellowBar else YandexYellowBar.copy(alpha = 0.45f),
        animationSpec = tween(180),
        label = "yandexTabColor"
    )
    // Активная секция пружинисто «подпрыгивает» при выборе (низкий damping →
    // overshoot), как и в основном баре приложения.
    val iconScale by animateFloatAsState(
        targetValue = if (selected) 1.15f else 1f,
        animationSpec = spring(dampingRatio = 0.42f, stiffness = 520f),
        label = "yandexTabScale"
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
                .size(30.dp)
                .graphicsLayer { scaleX = iconScale; scaleY = iconScale },
            tint = color
        )
    }
}
