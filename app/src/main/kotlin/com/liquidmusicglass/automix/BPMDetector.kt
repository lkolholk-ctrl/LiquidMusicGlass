package com.liquidmusicglass.automix

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * BPM Detector — определение темпа трека.
 *
 * Алгоритм:
 * 1. Вычисляем onset strength envelope из мел-спектрограммы
 * 2. Autocorrelation onset envelope
 * 3. Находим пик в диапазоне 60–200 BPM
 * 4. Уточняем через параболическую интерполяцию
 */
object BPMDetector {

    private const val MIN_BPM = 60f
    private const val MAX_BPM = 200f

    /**
     * Определяет BPM из raw PCM samples.
     * @param samples моно float samples нормализованные [-1, 1]
     * @param sampleRate частота дискретизации
     * @return BPM или null если не удалось определить
     */
    fun detectBPM(
        samples: FloatArray,
        sampleRate: Int = MelSpectrogram.SAMPLE_RATE
    ): Float? {
        if (samples.size < sampleRate * 2) return null // минимум 2 секунды

        // 1. Onset strength envelope
        val onsetEnv = computeOnsetStrength(samples, sampleRate)
        if (onsetEnv.size < 10) return null

        // 2. Autocorrelation
        val hopRate = sampleRate.toFloat() / MelSpectrogram.HOP_LENGTH
        val minLag = (hopRate * 60f / MAX_BPM).roundToInt()
        val maxLag = (hopRate * 60f / MIN_BPM).roundToInt()

        if (maxLag >= onsetEnv.size) return null

        val autocorr = FloatArray(maxLag + 1)
        for (lag in minLag..maxLag) {
            var sum = 0f
            val n = onsetEnv.size - lag
            for (i in 0 until n) {
                sum += onsetEnv[i] * onsetEnv[i + lag]
            }
            autocorr[lag] = sum / n
        }

        // 3. Найти пик
        var bestLag = minLag
        var bestVal = autocorr[minLag]
        for (lag in (minLag + 1)..maxLag) {
            if (autocorr[lag] > bestVal) {
                bestVal = autocorr[lag]
                bestLag = lag
            }
        }

        // 4. Параболическая интерполяция для точности
        val refinedLag = if (bestLag > minLag && bestLag < maxLag) {
            val a = autocorr[bestLag - 1]
            val b = autocorr[bestLag]
            val c = autocorr[bestLag + 1]
            val denom = 2f * (2f * b - a - c)
            if (denom > 0.001f) {
                bestLag + (a - c) / denom
            } else bestLag.toFloat()
        } else bestLag.toFloat()

        val bpm = hopRate * 60f / refinedLag

        // Валидация
        return if (bpm in MIN_BPM..MAX_BPM) bpm else null
    }

    /**
     * Onset strength: спектральный flux из кадров FFT.
     * Берём только положительные приращения энергии (атаки).
     */
    private fun computeOnsetStrength(
        samples: FloatArray,
        sampleRate: Int
    ): FloatArray {
        val hopLength = MelSpectrogram.HOP_LENGTH
        val fftSize = MelSpectrogram.N_FFT
        val numFrames = (samples.size - fftSize) / hopLength + 1
        if (numFrames < 2) return floatArrayOf()

        val hannWindow = FloatArray(fftSize) { i ->
            (0.5 - 0.5 * kotlin.math.cos(2.0 * Math.PI * i / (fftSize - 1))).toFloat()
        }

        // Вычисляем спектральную энергию по бандам
        val numBands = 40
        val bandEnergies = Array(numFrames) { FloatArray(numBands) }

        for (frame in 0 until numFrames) {
            val start = frame * hopLength
            val windowed = FloatArray(fftSize) { i ->
                if (start + i < samples.size) samples[start + i] * hannWindow[i] else 0f
            }
            val mag = FFT.magnitudeSpectrum(windowed, fftSize)

            // Группируем по mel-бандам (упрощённо — линейно)
            val binsPerBand = max(1, mag.size / numBands)
            for (band in 0 until numBands) {
                var energy = 0f
                val binStart = band * binsPerBand
                val binEnd = min((band + 1) * binsPerBand, mag.size)
                for (b in binStart until binEnd) {
                    energy += mag[b] * mag[b]
                }
                bandEnergies[frame][band] = energy
            }
        }

        // Onset strength: сумма положительных приращений по бандам
        val onsetEnv = FloatArray(numFrames - 1)
        for (frame in 1 until numFrames) {
            var flux = 0f
            for (band in 0 until numBands) {
                val diff = bandEnergies[frame][band] - bandEnergies[frame - 1][band]
                if (diff > 0) flux += diff
            }
            onsetEnv[frame - 1] = flux
        }

        // Нормализация
        val maxFlux = onsetEnv.maxOrNull() ?: 1f
        if (maxFlux > 0f) {
            for (i in onsetEnv.indices) onsetEnv[i] /= maxFlux
        }

        return onsetEnv
    }

    /**
     * Определяет совместимость BPM двух треков.
     * @return 0..1, где 1 = идеальное совпадение
     */
    fun bpmCompatibility(bpm1: Float?, bpm2: Float?): Float {
        if (bpm1 == null || bpm2 == null) return 0.5f // неизвестно — нейтрально

        val ratio = max(bpm1, bpm2) / min(bpm1, bpm2)
        // Точное совпадение или кратное (2x, 0.5x)
        val diff = min(
            abs(bpm1 - bpm2),
            min(abs(bpm1 - bpm2 * 2f), abs(bpm1 * 2f - bpm2))
        )

        return when {
            diff < 2f -> 1.0f    // почти одинаковый BPM
            diff < 5f -> 0.9f
            diff < 10f -> 0.7f
            diff < 20f -> 0.4f
            else -> 0.15f
        }
    }
}
