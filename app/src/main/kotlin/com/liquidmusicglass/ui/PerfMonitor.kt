package com.liquidmusicglass.ui

import android.view.Choreographer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Глобальный детектор просадки FPS. Считает интервалы между кадрами через
 * [Choreographer] (main-поток) и при УСТОЙЧИВОЙ просадке поднимает флаг [degraded] —
 * тяжёлые эффекты (AGSL-аура, дым, мудовые карточки, blur, стекло) по нему деградируют
 * до дешёвых версий, чтобы RenderThread на слабом GPU успевал выдавать кадры.
 *
 * ВОЗВРАТ ГАРАНТИРОВАН. Гистерезис ДВУСТОРОННИЙ и БЕЗ постоянных защёлок:
 *  - вход в degraded: [ENTER_SLOW_STREAK] подряд по-настоящему медленных кадров
 *    (устойчивая просадка, а не разовый хитч на переходе/декоде обложки);
 *  - выход из degraded: [EXIT_FAST_STREAK] подряд гладких кадров — ВСЕГДА, на любом
 *    кадре сессии. Как только GPU освободился, полные эффекты возвращаются.
 *
 * Раньше здесь была защёлка `jankProven`, которая после ПЕРВОЙ же просадки навсегда
 * блокировала выход — degraded залипал на всю сессию (AGSL-карточки и дым не
 * возвращались даже на флагмане). Защёлка удалена: стартовый ANR теперь снимается
 * отложенным/стаггер-прогревом самих шейдеров (см. WaveMoodTiles/AuraBackground),
 * а PerfMonitor отвечает только за временную деградацию с надёжным возвратом.
 *
 * Анти-дребезг обеспечивает асимметрия порогов + сброс встречного счётчика: чтобы
 * перевернуть состояние, нужна УСТОЙЧИВАЯ серия в одну сторону; чередующиеся кадры
 * ни одну серию не накапливают, поэтому строб «градиент↔шейдер» по кадрам невозможен.
 *
 * Стартуем в degraded=TRUE (первые кадры дешёвые), затем — при гладком FPS —
 * быстро (~0.3–0.4с) возвращаемся к полному качеству.
 *
 * [degraded] — Compose-состояние: чтение в @Composable триггерит рекомпозицию.
 * Пишется только из Choreographer-колбэка (main-поток).
 */
object PerfMonitor {

    // true = дешёвые версии эффектов. Стартуем так, чтобы первый кадр был лёгким;
    // при гладком FPS гарантированно возвращаемся в false (полные эффекты).
    var degraded by mutableStateOf(true)
        private set

    private const val SLOW_FRAME_MS = 40L      // >~25 fps — кадр реально «медленный»
    private const val ENTER_SLOW_STREAK = 18   // ~0.3с устойчивой просадки → деградация
    private const val EXIT_FAST_STREAK = 24    // ~0.4с гладких кадров → возврат к полному
    // Огромный интервал = приложение было свёрнуто/простаивало (vsync не шёл). Это НЕ
    // просадка рендера — сбрасываем счётчики, чтобы не уронить в degraded после простоя.
    private const val IDLE_GAP_MS = 200L

    private var lastFrameNs = 0L
    private var slowStreak = 0
    private var fastStreak = 0
    private var started = false

    private val frameCallback = Choreographer.FrameCallback { now -> onFrame(now) }

    /** Запустить мониторинг (идемпотентно). Вызывать на main-потоке. */
    fun start() {
        if (started) return
        started = true
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    private fun onFrame(now: Long) {
        if (lastFrameNs != 0L) {
            val deltaMs = (now - lastFrameNs) / 1_000_000L
            when {
                deltaMs > IDLE_GAP_MS -> {
                    // Простой/возврат из фона — нейтральный кадр, не считаем за просадку.
                    slowStreak = 0
                    fastStreak = 0
                }
                deltaMs > SLOW_FRAME_MS -> {
                    slowStreak++
                    fastStreak = 0
                }
                else -> {
                    fastStreak++
                    slowStreak = 0
                }
            }

            if (degraded) {
                // Гладкий FPS набран — возвращаем полные эффекты (всегда, без защёлок).
                if (fastStreak >= EXIT_FAST_STREAK) degraded = false
            } else {
                // Устойчивая просадка — временно упрощаем.
                if (slowStreak >= ENTER_SLOW_STREAK) degraded = true
            }
        }
        lastFrameNs = now
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }
}
