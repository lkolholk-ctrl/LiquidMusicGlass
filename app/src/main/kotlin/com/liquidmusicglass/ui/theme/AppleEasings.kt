package com.liquidmusicglass.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing

/**
 * Кривые анимации Apple Music (сняты реверсом их Android-плеера — набор
 * PathInterpolator'ов, которые они используют по всему UI). Пружин у Apple нет
 * вообще: всё на этих кривых + ValueAnimator. Единый «почерк» переходов.
 */
object AppleEasings {
    /** Обычные переходы (у Apple самый частый — CSS `ease`). */
    val Standard: Easing = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f)
    /** Сильный ease-out — «оседание» элемента на место. */
    val EaseOut: Easing = CubicBezierEasing(1f, 0f, 0.35f, 1f)
    /** Резкий — закрытия/дисмиссы. */
    val Sharp: Easing = CubicBezierEasing(0.25f, 0f, 1f, 0.2f)
    /** Заливка лирики (заливка слова / переход строки). */
    val Lyrics: Easing = CubicBezierEasing(0.75f, 0f, 0.25f, 1f)
}
