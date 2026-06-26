package com.liquidmusicglass.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

/**
 * Сигнал «перепрогрева эффектов» на возврат из фона.
 *
 * При уходе в фон поверхность окна уничтожается и GPU/RenderThread-контекст
 * AGSL-шейдеров (дым, мудкарточки) теряется. Если процесс жив (играет музыка в
 * foreground), возврат идёт через onStart/onResume БЕЗ onCreate — композиция
 * сохраняется, `remember`-состояние арминга остаётся true, и тяжёлые эффекты
 * продолжают рисовать СТАРЫМИ (уже невалидными) RuntimeShader → пропадают и не
 * восстанавливаются.
 *
 * [epoch] инкрементится на КАЖДЫЙ реальный возврат из фона. Композаблы эффектов
 * читают его как ключ своих warmup-эффектов: смена epoch → сброс арминга →
 * пере-прогрев через N кадров → СОЗДАНИЕ НОВЫХ RuntimeShader. Так эффекты
 * восстанавливаются и при холодном старте, и при возврате из фона.
 *
 * Пишется из MainActivity (onStart после onStop). Читается в @Composable.
 */
object EffectsLifecycle {

    var epoch by mutableIntStateOf(0)
        private set

    /** Вызвать при реальном возврате из фона (onStart после предшествующего onStop). */
    fun onReturnedToForeground() {
        epoch++
    }
}
