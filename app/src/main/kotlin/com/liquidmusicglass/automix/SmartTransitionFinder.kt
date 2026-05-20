package com.liquidmusicglass.automix

import kotlin.math.max
import kotlin.math.min

/**
 * Smart Transition Finder — определяет оптимальные точки перехода.
 */
object SmartTransitionFinder {

    data class TransitionPlan(
        val compatibility: Float,
        val transitionStartMs: Long,
        val crossfadeDurationMs: Long,
        val entryOffsetMs: Long,
        val transitionType: Int,
        val bpmA: Float?,
        val bpmB: Float?,
        val keyA: KeyDetector.KeyResult?,
        val keyB: KeyDetector.KeyResult?,
        val debugInfo: String
    )

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

        val bpmCompat = BPMDetector.bpmCompatibility(bpmA, bpmB)
        val keyCompat = KeyDetector.keyCompatibility(keyA, keyB)
        val energyCompat = EnergyAnalyzer.energyCompatibility(energyA.avgEnergy, energyB.avgEnergy)

        val compatibility = (
            bpmCompat * 0.35f +
            keyCompat * 0.30f +
            energyCompat * 0.35f
        ).coerceIn(0f, 1f)

        val transitionType = DJEffectsEngine.selectTransitionType(
            energyA = energyA,
            energyB = energyB,
            bpmCompat = bpmCompat,
            keyCompat = keyCompat
        )

        val crossfadeDurationMs = DJEffectsEngine.calculateCrossfadeDuration(
            transitionType = transitionType,
            bpmA = bpmA,
            bpmB = bpmB,
            energyA = energyA.avgEnergy,
            energyB = energyB.avgEnergy
        )

        val outroStart = energyA.outroStartMs
        val latestStart = max(0L, durationA - crossfadeDurationMs)
        val transitionStartMs = min(outroStart, latestStart).coerceAtLeast(durationA / 2)

        val entryOffsetMs = energyB.introEndMs

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

    fun fallbackPlan(durationA: Long): TransitionPlan {
        return TransitionPlan(
            compatibility = 0.6f,
            transitionStartMs = max(0L, durationA - 8000L),
            crossfadeDurationMs = 8000L,
            entryOffsetMs = 0L,
            transitionType = 0,
            bpmA = null,
            bpmB = null,
            keyA = null,
            keyB = null,
            debugInfo = "Fallback: smooth fade 8s"
        )
    }
}
