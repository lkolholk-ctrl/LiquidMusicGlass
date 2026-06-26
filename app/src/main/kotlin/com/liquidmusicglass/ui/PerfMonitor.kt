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
 * [degraded] — Compose-состояние: чтение в @Composable триггерит рекомпозицию.
 * Пишется только из Choreographer-колбэка (main-поток).
 */
object PerfMonitor {

    var degraded by mutableStateOf(false)
        private set

    private const val SLOW_FRAME_MS = 32L     // >~31 fps — кадр «медленный»
    private const val ENTER_SLOW_STREAK = 12  // ~0.4с устойчивой просадки → деградация
    private const val EXIT_FAST_STREAK = 60    // ~1с гладких кадров → восстановление

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
            if (deltaMs > SLOW_FRAME_MS) {
                slowStreak++; fastStreak = 0
            } else {
                fastStreak++; slowStreak = 0
            }
            if (!degraded && slowStreak >= ENTER_SLOW_STREAK) degraded = true
            else if (degraded && fastStreak >= EXIT_FAST_STREAK) degraded = false
        }
        lastFrameNs = now
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }
}
