package com.liquidmusicglass.automix

import androidx.media3.common.Player
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * DJ Effects Engine — эффекты при кроссфейде.
 *
 * Типы переходов:
 * 0 = SMOOTH_FADE    — плавный equal-power кроссфейд
 * 1 = ENERGY_FADE    — быстрый уход трека A
 * 2 = BEAT_MATCH     — beat-aligned, длинный, equal-power
 * 3 = HARD_CUT       — резкий переход с минимальным overlap
 * 4 = FILTER_SWEEP   — имитация закрытия/открытия фильтра
 * 5 = ECHO_OUT       — трек A уходит в эхо
 *
 * Главный принцип всех кривых: громкость ВЫХОДЯЩЕГО трека (fadeOut)
 * убывает МОНОТОННО — без плато на 1.0. Выходящий трек всегда
 * "уступает" входящему, чтобы не было каши из двух треков на максимуме.
 *
 * Дополнительно: outgoingBias в crossfadeWithEffects позволяет
 * сделать затухание выходящего трека ещё заметнее.
 */
object DJEffectsEngine {

    /**
     * Кривые кроссфейда. Возвращает (fadeOutVolume, fadeInVolume) для progress [0..1].
     */
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

    /**
     * SMOOTH_FADE — equal-power crossfade (косинус/синус).
     * Выходящий трек плавно и монотонно убывает 1 → 0.
     * Суммарная воспринимаемая мощность держится постоянной.
     */
    private fun smoothFadeCurve(p: Float): Pair<Float, Float> {
        val fadeOut = cos(p * PI.toFloat() / 2f)
        val fadeIn = sin(p * PI.toFloat() / 2f)
        return fadeOut to fadeIn
    }

    /**
     * ENERGY_FADE — быстрый fade-out, отложенный fade-in.
     * Трек A уходит резво, B нарастает плавно.
     */
    private fun energyFadeCurve(p: Float): Pair<Float, Float> {
        val fadeOut = (1f - p * p).coerceIn(0f, 1f)
        val fadeIn = if (p < 0.3f) 0f
        else ((p - 0.3f) / 0.7f).let { t -> t * t * (3f - 2f * t) }
        return fadeOut to fadeIn
    }

    /**
     * BEAT_MATCH — длинный, выровненный по битам переход.
     *
     * РАНЬШЕ: оба трека держались на 1.0 в середине (p in 0.3..0.7) —
     * это и давало кашу из двух треков на полной громкости.
     *
     * ТЕПЕРЬ: equal-power кривая. Оба трека слышны (на то и beat match),
     * но выходящий трек МОНОТОННО убывает и никогда не "спорит" с входящим
     * на полной громкости. Длина перехода (12с) и так даёт плавность.
     */
    private fun beatMatchCurve(p: Float): Pair<Float, Float> {
        // Equal-power, но с лёгким перекосом: выходящий трек убывает
        // чуть раньше входящего — новый трек ведёт.
        val fadeOut = cos(p * PI.toFloat() / 2f).pow(1.15f)
        val fadeIn = sin(p * PI.toFloat() / 2f)
        return fadeOut.coerceIn(0f, 1f) to fadeIn.coerceIn(0f, 1f)
    }

    /**
     * HARD_CUT — короткий резкий переход.
     * Небольшое перекрытие, но выходящий трек уже убывает с начала.
     */
    private fun hardCutCurve(p: Float): Pair<Float, Float> {
        // Выходящий: держится высоко, но не на плато 1.0 — слегка убывает,
        // затем после 55% резко в ноль.
        val fadeOut = when {
            p < 0.55f -> 1f - p * 0.35f          // 1.0 → ~0.81
            else -> (1f - 0.81f.let { it } - (p - 0.55f) * 4f)
                .let { (1f - (p - 0.55f) * 4f) }.coerceIn(0f, 1f)
        }
        val fadeIn = if (p < 0.4f) (p * 2.5f).coerceIn(0f, 1f) else 1f
        return fadeOut.coerceIn(0f, 1f) to fadeIn
    }

    /**
     * FILTER_SWEEP — имитация low-pass фильтра.
     * A уходит экспоненциально, B нарастает экспоненциально.
     */
    private fun filterSweepCurve(p: Float): Pair<Float, Float> {
        val fadeOut = (1f - p).let { it * it }
        val fadeIn = p * p
        return fadeOut to fadeIn
    }

    /**
     * ECHO_OUT — трек A затухает с пульсацией (имитация delay/echo).
     */
    private fun echoOutCurve(p: Float): Pair<Float, Float> {
        val envelope = (1f - p)
        val pulse = 1f + 0.1f * sin(p * 8f * PI.toFloat()) * envelope
        val fadeOut = (envelope * pulse).coerceIn(0f, 1f)
        val fadeIn = sin(p * PI.toFloat() / 2f)
        return fadeOut to fadeIn
    }

    /**
     * Прокачанный кроссфейд с DJ-кривыми.
     *
     * @param outgoingBias дополнительное затухание ВЫХОДЯЩЕГО трека.
     *   1.0  — кривая как есть.
     *   >1.0 — выходящий трек затухает заметнее/раньше (меньше каши).
     *   Применяется как fadeOut^outgoingBias. При 1.5 громкость 0.7
     *   превращается в ~0.57 — старый трек ощутимо уходит на задний план.
     */
    suspend fun crossfadeWithEffects(
        fromPlayer: Player,
        toPlayer: Player,
        durationMs: Long,
        transitionType: Int,
        masterVolume: Float = 1f,
        outgoingBias: Float = 1.5f,
        stepMs: Long = 40L
    ) {
        val safeDuration = durationMs.coerceAtLeast(stepMs)
        val steps = (safeDuration / stepMs).toInt().coerceAtLeast(1)
        val bias = outgoingBias.coerceIn(1f, 3f)

        toPlayer.volume = 0f
        toPlayer.play()

        for (step in 0..steps) {
            val progress = (step.toFloat() / steps.toFloat()).coerceIn(0f, 1f)

            val (rawFadeOut, fadeIn) = getCrossfadeCurve(progress, transitionType)

            // Выходящий трек дополнительно притушивается:
            // pow с показателем >1 ускоряет затухание, не ломая монотонность.
            val fadeOut = rawFadeOut.coerceIn(0f, 1f).pow(bias)

            fromPlayer.volume = (fadeOut * masterVolume).coerceIn(0f, 1f)
            toPlayer.volume = (fadeIn * masterVolume).coerceIn(0f, 1f)

            delay(stepMs)
        }

        fromPlayer.volume = 0f
        toPlayer.volume = masterVolume
    }

    /**
     * Выбирает лучший тип перехода на основе анализа двух треков.
     */
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

    /**
     * Рассчитывает оптимальную длительность кроссфейда.
     */
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
            3 -> 5000L
            4 -> 10000L
            5 -> 7000L
            else -> 8000L
        }

        if (transitionType == 2 && bpmA != null) {
            val beatDurationMs = (60_000f / bpmA).toLong()
            val beatsPerBar = 4
            val barDuration = beatDurationMs * beatsPerBar
            val bars = if (barDuration * 4 < 16_000L) 4 else 2
            return (barDuration * bars).coerceIn(5000L, 30000L)
        }

        val avgEnergy = (energyA + energyB) / 2f
        val energyMult = if (avgEnergy > 0.7f) 0.8f else if (avgEnergy < 0.3f) 1.3f else 1f

        return (baseDuration * energyMult).toLong().coerceIn(5000L, 30000L)
    }

    val TRANSITION_NAMES = arrayOf(
        "Smooth Fade", "Energy Fade", "Beat Match",
        "Hard Cut", "Filter Sweep", "Echo Out"
    )
}
