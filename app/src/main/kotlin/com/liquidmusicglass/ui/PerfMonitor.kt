package com.liquidmusicglass.ui

import android.view.Choreographer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Глобальный детектор просадки FPS. Считает интервалы между кадрами через
 * [Choreographer] (main-поток) и при УСТОЙЧИВОЙ просадке поднимает флаг [degraded] —
 * тяжёлые эффекты (AGSL-аура, лирика, мудовые карточки, blur) по нему деградируют до
 * дешёвых версий, чтобы RenderThread на слабом GPU успевал выдавать кадры.
 *
 * Гистерезис: нужно [ENTER_SLOW_STREAK] подряд медленных кадров, чтобы включить
 * деградацию, и [EXIT_FAST_STREAK] подряд гладких, чтобы выключить — без дребезга.
 *
 * ВАЖНО: стартуем в degraded=TRUE (warmup). Тяжёлые AGSL-шейдеры НЕ создаются и НЕ
 * рисуются на первых кадрах экрана (именно их компиляция+рендер разом давали ANR на
 * старте My Wave при uptime ~0.5с). Полные эффекты включаются ТОЛЬКО когда устройство
 * показало [EXIT_FAST_STREAK] гладких кадров подряд — т.е. экран уже отрисовался и
 * тянет. На слабом GPU гладких кадров не наберётся → эффекты так и останутся дешёвыми.
 *
 * [degraded] — Compose-состояние: чтение в @Composable триггерит рекомпозицию.
 * Пишется только из Choreographer-колбэка (main-поток).
 */
object PerfMonitor {

    // true = дешёвые градиенты. Стартуем именно так, чтобы первый кадр был лёгким.
    var degraded by mutableStateOf(true)
        private set

    private const val SLOW_FRAME_MS = 32L     // >~31 fps — кадр «медленный»
    private const val ENTER_SLOW_STREAK = 12  // ~0.4с устойчивой просадки → деградация
    private const val WARMUP_FAST_STREAK = 30  // ~0.5с гладких кадров → закончить прогрев

    private var lastFrameNs = 0L
    private var slowStreak = 0
    private var fastStreak = 0
    private var started = false
    // AGSL однажды включился и НЕ потянул → устройство слабое, остаёмся на дешёвых
    // эффектах до перезапуска (анти-дребезг: без мигания градиент↔шейдер каждую секунду).
    private var jankProven = false

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
            if (deltaMs > SLOW_FRAME_MS) {
                slowStreak++; fastStreak = 0
            } else {
                fastStreak++; slowStreak = 0
            }
            if (degraded) {
                // Выходим из degraded ТОЛЬКО на стартовом прогреве (пока AGSL ни разу не
                // спотыкался). Прошло WARMUP_FAST_STREAK гладких кадров на дешёвых
                // градиентах → экран отрисовался, пробуем включить полные эффекты.
                if (!jankProven && fastStreak >= WARMUP_FAST_STREAK) degraded = false
            } else {
                // AGSL включён и пошла устойчивая просадка → откатываемся НАВСЕГДА (сессия).
                if (slowStreak >= ENTER_SLOW_STREAK) {
                    degraded = true
                    jankProven = true
                }
            }
        }
        lastFrameNs = now
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }
}
