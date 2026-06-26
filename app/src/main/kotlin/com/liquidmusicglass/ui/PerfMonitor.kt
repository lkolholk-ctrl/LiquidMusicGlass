package com.liquidmusicglass.ui

import android.view.Choreographer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Одноразовый прогрев старта (НЕ деградация по FPS).
 *
 * Первые [WARMUP_FRAMES] отрисованных кадров экран рисует ДЕШЁВЫЕ версии тяжёлых
 * элементов (плоское стекло вместо blur/lens, упрощённый фон лирики), чтобы первый
 * тяжёлый кадр на холодном старте не давил GPU и не возникал ANR. После того как
 * экран отрисовался и стабилизировался, [degraded] ОДИН раз становится false и
 * больше НИКОГДА не возвращается в true — эффекты включаются и работают постоянно.
 *
 * Это принципиально не «деградация»: тут нет порогов FPS, нет гистерезиса, нет
 * откатов. Раньше FPS-логика залипала в degraded и убирала эффекты насовсем —
 * теперь это просто отложенный старт с гарантированным однократным включением.
 *
 * Дым и AGSL-мудкарточки прогреваются ОТДЕЛЬНО, по своему расписанию в самих
 * композаблах (через withFrameNanos), чтобы тяжёлые компиляции шейдеров легли на
 * РАЗНЫЕ кадры, а не все разом в момент включения.
 *
 * [degraded] — Compose-состояние: чтение в @Composable триггерит рекомпозицию.
 * Пишется только из Choreographer-колбэка (main-поток).
 */
object PerfMonitor {

    // true = идёт стартовый прогрев (дешёвые версии стекла). Однократно → false.
    var degraded by mutableStateOf(true)
        private set

    // ~16 отрисованных кадров (~0.27с при 60 Гц) — экран успел появиться и осесть.
    private const val WARMUP_FRAMES = 16

    private var frameCount = 0
    private var posting = false

    private val frameCallback = Choreographer.FrameCallback { onFrame() }

    /** Запустить прогрев (идемпотентно). Вызывать на main-потоке. */
    fun start() = restart()

    /**
     * Перезапустить прогрев — при холодном старте и при ВОЗВРАТЕ ИЗ ФОНА. Стекло
     * снова кратко рисуется плоско (лёгкий кадр возврата + пере-применение
     * RenderEffect), затем возвращается к полному. Идемпотентно к двойному вызову.
     */
    fun restart() {
        frameCount = 0
        degraded = true
        if (!posting) {
            posting = true
            Choreographer.getInstance().postFrameCallback(frameCallback)
        }
    }

    private fun onFrame() {
        frameCount++
        if (frameCount >= WARMUP_FRAMES) {
            degraded = false
            posting = false
            return // прогрев завершён — кадры не считаем, колбэк не перепостим
        }
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }
}
