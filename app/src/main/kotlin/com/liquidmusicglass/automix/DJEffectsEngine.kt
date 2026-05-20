package com.liquidmusicglass.automix

import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * DJ Effects Engine — эффекты при кроссфейде.
 */
object DJEffectsEngine {

    fun getCrossfadeCurve(
        progress: Float,
        transitionType: Int
    ): Pair<Float, Float> {
        val p = progress.coerceIn(0f, 1f)

        return when (transitionType) {
            0 -> smoothFadeCurve(p)
            1 -> energyFadeCurve(p)
            2 -> beatMatchCurve(p)
            3 -> hardCutCurve(p)
            4 -> filterSweepCurve(p)
            5 -> echoOutCurve(p)
            else -> smoothFadeCurve(p)
        }
    }

    private fun smoothFadeCurve(p: Float): Pair<Float, Float> {
        // Constant Power Crossfade:
        // Входящий — синус (мягкий старт), исходящий — кубический нырок (быстрое освобождение частот)
        val fadeIn = sin(p * PI.toFloat() / 2f)
        val fadeOut = 1f - p * p * p
        return fadeOut.coerceIn(0f, 1f) to fadeIn.coerceIn(0f, 1f)
    }

    private fun energyFadeCurve(p: Float): Pair<Float, Float> {
        // Исходящий уходит быстро (куб), входящий нарастает синусом после небольшой задержки
        val fadeOut = (1f - p * p * p).coerceIn(0f, 1f)
        val fadeIn = if (p < 0.2f) 0f
        else sin(((p - 0.2f) / 0.8f) * PI.toFloat() / 2f)
        return fadeOut to fadeIn.coerceIn(0f, 1f)
    }

    private fun beatMatchCurve(p: Float): Pair<Float, Float> {
        // Быстрый нырок исходящего, синус входящего — минимум конфликта басов при бит-матче
        val fadeOut = (1f - p * p * p).coerceIn(0f, 1f)
        val fadeIn = sin(p * PI.toFloat() / 2f)
        return fadeOut to fadeIn.coerceIn(0f, 1f)
    }

    private fun hardCutCurve(p: Float): Pair<Float, Float> {
        // Быстрый резкий срез: исходящий уходит кубически, входящий — синус
        val fadeOut = (1f - p * p * p).coerceIn(0f, 1f)
        val fadeIn = sin(p * PI.toFloat() / 2f)
        return fadeOut to fadeIn.coerceIn(0f, 1f)
    }

    private fun filterSweepCurve(p: Float): Pair<Float, Float> {
        val fadeOut = (1f - p * p * p).coerceIn(0f, 1f)
        val fadeIn = sin(p * PI.toFloat() / 2f)
        return fadeOut to fadeIn.coerceIn(0f, 1f)
    }

    private fun echoOutCurve(p: Float): Pair<Float, Float> {
        val envelope = (1f - p * p * p).coerceIn(0f, 1f)
        val pulse = 1f + 0.1f * sin(p * 8f * PI.toFloat()) * envelope
        val fadeOut = (envelope * pulse).coerceIn(0f, 1f)
        val fadeIn = sin(p * PI.toFloat() / 2f)
        return fadeOut to fadeIn.coerceIn(0f, 1f)
    }

    /**
     * ИСПРАВЛЕНО: Полная синхронизация на Main-треде Android + применение ИИ-параметра bpmDrift!
     */
    suspend fun crossfadeWithEffects(
        fromPlayer: Player,
        toPlayer: Player,
        durationMs: Long,
        transitionType: Int,
        masterVolume: Float = 1f,
        outgoingBias: Float = 1.5f,
        bpmDrift: Float = 0f, // Применяем ИИ-предсказание
        stepMs: Long = 40L
    ) = withContext(Dispatchers.Main) { // Железный фикс потоков!
        val safeDuration = durationMs.coerceAtLeast(stepMs)
        val steps = (safeDuration / stepMs).toInt().coerceAtLeast(1)
        val bias = outgoingBias.coerceIn(1f, 3f)

        val targetMediaId = toPlayer.currentMediaItem?.mediaId

        toPlayer.volume = 0f
        toPlayer.play()

        // Если включен Бит-Мэтч, подгоняем скорость воспроизведения входящего трека
        if (transitionType == 2 && bpmDrift != 0f) {
            toPlayer.playbackParameters = PlaybackParameters(1f + bpmDrift)
        }

        for (step in 0..steps) {
            // Проверяем, не нажал ли пользователь паузу во время кроссфейда
            if (!toPlayer.playWhenReady) {
                fromPlayer.pause()
                fromPlayer.volume = 0f
                break
            }
            // Проверяем, не переключили ли трек вручную
            if (toPlayer.currentMediaItem?.mediaId != targetMediaId) {
                fromPlayer.volume = 0f
                fromPlayer.stop()
                break
            }

            val progress = (step.toFloat() / steps.toFloat()).coerceIn(0f, 1f)
            val (rawFadeOut, fadeIn) = getCrossfadeCurve(progress, transitionType)
            val fadeOut = rawFadeOut.coerceIn(0f, 1f).pow(bias)

            fromPlayer.volume = (fadeOut * masterVolume).coerceIn(0f, 1f)
            toPlayer.volume = (fadeIn * masterVolume).coerceIn(0f, 1f)

            delay(stepMs)
        }

        // Принудительно останавливаем старый плеер
        fromPlayer.volume = 0f
        fromPlayer.stop()

        toPlayer.volume = masterVolume

        // Мягко возвращаем дефолтную скорость по окончании микса
        if (transitionType == 2) {
            toPlayer.playbackParameters = PlaybackParameters(1f)
        }
    }

    fun selectTransitionType(
        energyA: EnergyAnalyzer.TrackEnergy?,
        energyB: EnergyAnalyzer.TrackEnergy?,
        bpmCompat: Float,
        keyCompat: Float
    ): Int {
        val aEnergy = energyA?.avgEnergy ?: 0.5f
        val bEnergy = energyB?.avgEnergy ?: 0.5f
        val energyDiff = kotlin.math.abs(aEnergy - bEnergy)

        return when {
            bpmCompat > 0.85f && keyCompat > 0.6f -> 2 // BEAT_MATCH
            aEnergy > 0.7f && bEnergy > 0.7f -> 1 // ENERGY_FADE
            energyDiff > 0.3f -> 4 // FILTER_SWEEP
            aEnergy < 0.4f && bEnergy < 0.4f -> 5 // ECHO_OUT
            aEnergy < 0.2f || bEnergy < 0.2f -> 3 // HARD_CUT
            else -> 0 // SMOOTH_FADE
        }
    }

    fun calculateCrossfadeDuration(
        transitionType: Int,
        bpmA: Float?,
        bpmB: Float?,
        energyA: Float,
        energyB: Float
    ): Long {
        val baseDuration = when (transitionType) {
            0 -> 8000L
            1 -> 5000L
            2 -> 12000L
            3 -> 2000L
            4 -> 10000L
            5 -> 7000L
            else -> 8000L
        }

        if (transitionType == 2 && bpmA != null) {
            val beatDurationMs = (60_000f / bpmA).toLong()
            val beatsPerBar = 4
            val barDuration = beatDurationMs * beatsPerBar
            val bars = if (barDuration * 4 < 16_000L) 4 else 2
            return (barDuration * bars).coerceIn(4000L, 16000L)
        }

        val avgEnergy = (energyA + energyB) / 2f
        val energyMult = if (avgEnergy > 0.7f) 0.8f else if (avgEnergy < 0.3f) 1.3f else 1f

        return (baseDuration * energyMult).toLong().coerceIn(2000L, 16000L)
    }

    val TRANSITION_NAMES = arrayOf(
        "Smooth Fade", "Energy Fade", "Beat Match",
        "Hard Cut", "Filter Sweep", "Echo Out"
    )
}
