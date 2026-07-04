package com.liquidmusicglass.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp

/**
 * Перенос детей по строкам (замена FlowRow) на стабильном [Layout].
 *
 * ВАЖНО: НЕ используем androidx FlowRow — его сигнатура менялась между
 * версиями compose-foundation (в 1.7 добавились maxLines/overflow с типом
 * FlowRowOverflow). Скомпилировано против новой сигнатуры, а на устройство
 * попала старая библиотека → java.lang.NoSuchMethodError на РЕНДЕРЕ
 * (краш экрана, música продолжает играть). Поймано полевым дампом на
 * экране поиска. Свой Layout не зависит от версии foundation.
 */
@Composable
fun WrapRow(
    modifier: Modifier = Modifier,
    hGap: Dp = 8.dp,
    vGap: Dp = 8.dp,
    content: @Composable () -> Unit
) {
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        val h = hGap.roundToPx()
        val v = vGap.roundToPx()
        val maxW = constraints.maxWidth
        val placeables = measurables.map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
        var x = 0; var y = 0; var rowH = 0
        val pos = ArrayList<IntOffset>(placeables.size)
        for (p in placeables) {
            if (x > 0 && x + p.width > maxW) { x = 0; y += rowH + v; rowH = 0 }
            pos.add(IntOffset(x, y))
            x += p.width + h
            rowH = maxOf(rowH, p.height)
        }
        val totalH = if (placeables.isEmpty()) 0 else y + rowH
        layout(maxW, totalH) {
            placeables.forEachIndexed { i, p -> p.place(pos[i]) }
        }
    }
}
