package com.liquidmusicglass.ui.glass

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import com.liquidmusicglass.ui.theme.LiquidMotion

/**
 * Press Scale — элемент сжимается при нажатии и пружинит обратно.
 *
 * Пружина берётся из единого словаря [LiquidMotion] (snappy), чтобы отклик
 * ощущался одинаково во всём приложении.
 *
 * Использование:
 * Box(Modifier.pressScale { onClick() })
 * Box(Modifier.pressScale(LiquidMotion.PressButton) { onClick() })
 */
fun Modifier.pressScale(
    pressedScale: Float = 0.92f,
    onClick: () -> Unit
): Modifier = composed {
    var isPressed by remember { mutableStateOf(false) }

    val scale by animateFloatAsState(
        targetValue = if (isPressed) pressedScale else 1f,
        animationSpec = LiquidMotion.snappy(),
        label = "pressScale"
    )

    this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .pointerInput(Unit) {
            detectTapGestures(
                onPress = {
                    isPressed = true
                    try {
                        awaitRelease()
                    } finally {
                        isPressed = false
                    }
                    onClick()
                }
            )
        }
}

/**
 * Press Scale только визуальный — без onClick.
 * Для элементов которые уже имеют свой clickable.
 */
fun Modifier.pressScaleVisual(
    pressedScale: Float = 0.95f
): Modifier = composed {
    var isPressed by remember { mutableStateOf(false) }

    val scale by animateFloatAsState(
        targetValue = if (isPressed) pressedScale else 1f,
        animationSpec = LiquidMotion.snappy(),
        label = "pressScaleVisual"
    )

    this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .pointerInput(Unit) {
            detectTapGestures(
                onPress = {
                    isPressed = true
                    try {
                        awaitRelease()
                    } finally {
                        isPressed = false
                    }
                }
            )
        }
}

/**
 * liquidClickable — единый тапабельный модификатор: клик без ripple + пружинное
 * пресс-сжатие в одном. Замена для `clickable(interactionSource, indication = null)`
 * по всему приложению, чтобы КАЖДОЕ нажатие ощущалось «живым».
 *
 * Владеет жестом целиком (никаких двойных detectTapGestures), поэтому ставится
 * ВМЕСТО существующего clickable, а не рядом.
 *
 * @param pressedScale степень сжатия; дефолт [LiquidMotion.PressCard] для
 *   карточек/строк, для кнопок/иконок передавай PressButton/PressIcon.
 * @param enabled когда false — ни сжатия, ни клика.
 */
fun Modifier.liquidClickable(
    enabled: Boolean = true,
    pressedScale: Float = LiquidMotion.PressCard,
    onClick: () -> Unit
): Modifier = composed {
    var isPressed by remember { mutableStateOf(false) }

    val scale by animateFloatAsState(
        targetValue = if (isPressed && enabled) pressedScale else 1f,
        animationSpec = LiquidMotion.snappy(),
        label = "liquidClickable"
    )

    this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .pointerInput(enabled) {
            if (!enabled) return@pointerInput
            detectTapGestures(
                onPress = {
                    isPressed = true
                    try {
                        awaitRelease()
                    } finally {
                        isPressed = false
                    }
                },
                onTap = { onClick() }
            )
        }
}
