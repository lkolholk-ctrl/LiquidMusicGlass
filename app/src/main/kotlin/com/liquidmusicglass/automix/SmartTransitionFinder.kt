package com.liquidmusicglass.automix

import kotlin.math.max
import kotlin.math.min

/**
 * Smart Transition Finder — определяет оптимальные точки перехода.
 *
 * Вместо простого "последние N секунд", анализирует:
 * - Энергетическую карту (ищет outro в текущем, intro в следующем)
 * - BPM совместимость
 * - Тональность
 * - Общую "совместимость" пары треков
 *
 * Возвращает полный TransitionPlan.
 */
object SmartTransitionFinder {

    data class TransitionPlan(
        /** Общая совместимость 0..1 */
        val compatibility: Float,
        /** Когда начинать переход (мс от начала текущего трека) */
        val transitionStartMs: Long,
        /** Длительность кроссфейда в мс */
        val crossfadeDurationMs: Long,
        /** Откуда стартовать следующий трек (мс) */
        val entryOffsetMs: Long,
        /** Тип перехода (индекс DJEffectsEngine) */
        val transitionType: Int,
        /** BPM текущего */
        val bpmA: Float?,
        /** BPM следующего */
        val bpmB: Float?,
        /** Тональность текущего */
        val keyA: KeyDetector.KeyResult?,
        /** Тональность следующего */
        val keyB: KeyDetector.KeyResult?,
        /** Детали для дебага */
        val debugInfo: String
    )

    /**
     * Полный анализ пары треков и формирование плана перехода.
     */
    fun findTransition(
        energyA: EnergyAnalyzer.TrackEnergy,
        energyB: EnergyAnalyzer.TrackEnergy,
        durationA: Long,
        durationB: Long
    ): TransitionPlan {
        val bpmA = energyA.bpm
        val bpmB = energyB.bpm
        val keyA = energyA.key
        val keyB = energyB.key

        // 1. Совместимости
        val bpmCompat = BPMDetector.bpmCompatibility(bpmA, bpmB)
        val keyCompat = KeyDetector.keyCompatibility(keyA, keyB)
        val energyCompat = EnergyAnalyzer.energyCompatibility(energyA.avgEnergy, energyB.avgEnergy)

        // Взвешенная совместимость
        val compatibility = (
            bpmCompat * 0.35f +
            keyCompat * 0.30f +
            energyCompat * 0.35f
        ).coerceIn(0f, 1f)

        // 2. Тип перехода
        val transitionType = DJEffectsEngine.selectTransitionType(
            energyA = energyA,
            energyB = energyB,
            bpmCompat = bpmCompat,
            keyCompat = keyCompat
        )

        // 3. Длительность кроссфейда
        val crossfadeDurationMs = DJEffectsEngine.calculateCrossfadeDuration(
            transitionType = transitionType,
            bpmA = bpmA,
            bpmB = bpmB,
            energyA = energyA.avgEnergy,
            energyB = energyB.avgEnergy
        )

        // 4. Точка начала перехода
        //    Используем outro detection из EnergyAnalyzer
        val outroStart = energyA.outroStartMs
        //    Но не раньше чем crossfadeDuration от конца
        val latestStart = max(0L, durationA - crossfadeDurationMs)
        val transitionStartMs = min(outroStart, latestStart).coerceAtLeast(durationA / 2)

        // 5. Entry offset следующего трека
        //    Пропускаем intro если он тихий
        val entryOffsetMs = energyB.introEndMs

        // Debug info
        val debug = buildString {
            append("BPM: ${bpmA?.let { "%.1f".format(it) } ?: "?"}")
            append(" → ${bpmB?.let { "%.1f".format(it) } ?: "?"}")
            append(" (${(bpmCompat * 100).toInt()}%)")
            append(" | Key: ${keyA?.name ?: "?"}")
            append(" → ${keyB?.name ?: "?"}")
            append(" (${(keyCompat * 100).toInt()}%)")
            append(" | Energy: ${(energyCompat * 100).toInt()}%")
            append(" | Type: ${DJEffectsEngine.TRANSITION_NAMES.getOrElse(transitionType) { "?" }}")
            append(" | Fade: ${crossfadeDurationMs}ms")
        }

        return TransitionPlan(
            compatibility = compatibility,
            transitionStartMs = transitionStartMs,
            crossfadeDurationMs = crossfadeDurationMs,
            entryOffsetMs = entryOffsetMs,
            transitionType = transitionType,
            bpmA = bpmA,
            bpmB = bpmB,
            keyA = keyA,
            keyB = keyB,
            debugInfo = debug
        )
    }

    /**
     * Быстрый fallback — без полного анализа.
     */
    fun fallbackPlan(durationA: Long): TransitionPlan {
        return TransitionPlan(
            compatibility = 0.6f,
            transitionStartMs = max(0L, durationA - 8000L),
            crossfadeDurationMs = 8000L,
            entryOffsetMs = 0L,
            transitionType = 0, // SMOOTH_FADE
            bpmA = null,
            bpmB = null,
            keyA = null,
            keyB = null,
            debugInfo = "Fallback: smooth fade 8s"
        )
    }
}
