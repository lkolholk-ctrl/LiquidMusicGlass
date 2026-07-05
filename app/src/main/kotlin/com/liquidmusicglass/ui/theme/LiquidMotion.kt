package com.liquidmusicglass.ui.theme

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring

/**
 * LiquidMotion — единый словарь пружин для всего приложения.
 *
 * Смысл: любое движение в UI (пресс-сжатие, раскрытие карточки, переезд
 * элемента списка) должно ощущаться как одна физика, а не как набор
 * случайных `tween`/`spring`. Отсюда «живость», которую хвалят на карточках
 * импорта — это пружина, а не таймер.
 *
 * Правила подбора:
 *  - [snappy] — короткие отклики (пресс, тумблеры, чипы). Быстро схватывает,
 *    почти без перелёта.
 *  - [gentle] — раскрытия/сворачивания и смена размера (animateContentSize,
 *    reveal). Мягко, без дребезга.
 *  - [bouncy] — редкие «радостные» акценты (лайк, попадание). Заметный отскок.
 *
 * Функции дженерик, чтобы подходить и под Float (scale/alpha), и под IntSize
 * (animateContentSize), и под Dp/Offset — тип выводится из места вызова.
 */
object LiquidMotion {

    fun <T> snappy(): SpringSpec<T> = spring(
        dampingRatio = 0.72f,
        stiffness = Spring.StiffnessMedium
    )

    fun <T> gentle(): SpringSpec<T> = spring(
        dampingRatio = 0.90f,
        stiffness = 300f
    )

    fun <T> bouncy(): SpringSpec<T> = spring(
        dampingRatio = Spring.DampingRatioMediumBouncy,
        stiffness = Spring.StiffnessMedium
    )

    /** Дефолтные степени сжатия под тип элемента (для [liquidClickable]/pressScale). */
    const val PressCard = 0.97f    // крупные карточки/строки списка — деликатно
    const val PressButton = 0.93f  // кнопки/пилюли/чипы
    const val PressIcon = 0.88f    // мелкие иконки-кнопки — заметнее
}
