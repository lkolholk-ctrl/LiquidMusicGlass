package com.liquidmusicglass.automix

import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Key Detector — определение тональности трека.
 *
 * Алгоритм Крумханзл-Шмуклера:
 * 1. Вычисляем chromagram (12-bin хрома вектор)
 * 2. Корреляция с профилями всех 24 тональностей (12 мажорных + 12 минорных)
 * 3. Тональность с максимальной корреляцией = результат
 */
object KeyDetector {

    // Крумханзл-Шмуклер профили
    private val MAJOR_PROFILE = floatArrayOf(
        6.35f, 2.23f, 3.48f, 2.33f, 4.38f, 4.09f,
        2.52f, 5.19f, 2.39f, 3.66f, 2.29f, 2.88f
    )

    private val MINOR_PROFILE = floatArrayOf(
        6.33f, 2.68f, 3.52f, 5.38f, 2.60f, 3.53f,
        2.54f, 4.75f, 3.98f, 2.69f, 3.34f, 3.17f
    )

    private val KEY_NAMES = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

    data class KeyResult(
        val key: Int,          // 0-11 (C=0, C#=1, ... B=11)
        val isMinor: Boolean,
        val confidence: Float, // 0..1
        val name: String       // "C major", "A minor" etc.
    )

    /**
     * Определяет тональность из PCM samples.
     */
    fun detectKey(
        samples: FloatArray,
        sampleRate: Int = MelSpectrogram.SAMPLE_RATE
    ): KeyResult? {
        if (samples.size < sampleRate) return null

        val chroma = computeChromagram(samples, sampleRate)
        if (chroma.all { it == 0f }) return null

        return krumhanslSchmuckler(chroma)
    }

    /**
     * Вычисляет 12-bin chromagram усреднённый по всему треку.
     */
    private fun computeChromagram(
        samples: FloatArray,
        sampleRate: Int
    ): FloatArray {
        val chroma = FloatArray(12)
        val fftSize = MelSpectrogram.N_FFT
        val hopLength = MelSpectrogram.HOP_LENGTH
        val numFrames = (samples.size - fftSize) / hopLength + 1
        if (numFrames < 1) return chroma

        val hannWindow = FloatArray(fftSize) { i ->
            (0.5 - 0.5 * kotlin.math.cos(2.0 * Math.PI * i / (fftSize - 1))).toFloat()
        }

        val freqResolution = sampleRate.toFloat() / fftSize
        var frameCount = 0

        for (frame in 0 until numFrames) {
            val start = frame * hopLength
            val windowed = FloatArray(fftSize) { i ->
                if (start + i < samples.size) samples[start + i] * hannWindow[i] else 0f
            }
            val mag = FFT.magnitudeSpectrum(windowed, fftSize)

            // Map FFT bins to chroma bins
            for (bin in 1 until mag.size) {
                val freq = bin * freqResolution
                if (freq < 65f || freq > 2000f) continue // A2 to B6

                val midiNote = 12f * log2(freq / 440f) + 69f
                val chromaBin = ((midiNote.toInt() % 12) + 12) % 12
                chroma[chromaBin] += mag[bin] * mag[bin]
            }
            frameCount++
        }

        // Нормализация
        if (frameCount > 0) {
            for (i in chroma.indices) chroma[i] /= frameCount
        }
        val maxVal = chroma.maxOrNull() ?: 1f
        if (maxVal > 0f) {
            for (i in chroma.indices) chroma[i] /= maxVal
        }

        return chroma
    }

    /**
     * Крумханзл-Шмуклер: корреляция chroma с 24 тональностями.
     */
    private fun krumhanslSchmuckler(chroma: FloatArray): KeyResult {
        var bestKey = 0
        var bestMinor = false
        var bestCorr = -Float.MAX_VALUE

        for (shift in 0 until 12) {
            val majorCorr = pearsonCorrelation(chroma, rotateProfile(MAJOR_PROFILE, shift))
            val minorCorr = pearsonCorrelation(chroma, rotateProfile(MINOR_PROFILE, shift))

            if (majorCorr > bestCorr) {
                bestCorr = majorCorr
                bestKey = shift
                bestMinor = false
            }
            if (minorCorr > bestCorr) {
                bestCorr = minorCorr
                bestKey = shift
                bestMinor = true
            }
        }

        val confidence = ((bestCorr + 1f) / 2f).coerceIn(0f, 1f)
        val modeName = if (bestMinor) "minor" else "major"
        val name = "${KEY_NAMES[bestKey]} $modeName"

        return KeyResult(bestKey, bestMinor, confidence, name)
    }

    private fun rotateProfile(profile: FloatArray, shift: Int): FloatArray {
        return FloatArray(12) { i -> profile[(i - shift + 12) % 12] }
    }

    private fun pearsonCorrelation(x: FloatArray, y: FloatArray): Float {
        val n = min(x.size, y.size)
        var sumX = 0f; var sumY = 0f
        for (i in 0 until n) { sumX += x[i]; sumY += y[i] }
        val meanX = sumX / n; val meanY = sumY / n

        var cov = 0f; var varX = 0f; var varY = 0f
        for (i in 0 until n) {
            val dx = x[i] - meanX
            val dy = y[i] - meanY
            cov += dx * dy
            varX += dx * dx
            varY += dy * dy
        }

        val denom = sqrt(varX) * sqrt(varY)
        return if (denom > 0.0001f) cov / denom else 0f
    }

    /**
     * Совместимость тональностей по кругу квинт.
     * Camelot wheel: совместимые = те же, ±1, параллельная тональность.
     * @return 0..1
     */
    fun keyCompatibility(key1: KeyResult?, key2: KeyResult?): Float {
        if (key1 == null || key2 == null) return 0.5f

        val semitones = ((key2.key - key1.key) + 12) % 12

        // Одна и та же тональность
        if (semitones == 0 && key1.isMinor == key2.isMinor) return 1.0f

        // Параллельная (мажор ↔ минор, тот же ключ)
        if (semitones == 0 && key1.isMinor != key2.isMinor) return 0.85f

        // Относительная тональность (минор +3 = мажор, мажор -3 = минор)
        if (key1.isMinor && !key2.isMinor && semitones == 3) return 0.9f
        if (!key1.isMinor && key2.isMinor && semitones == 9) return 0.9f

        // Квинта вверх/вниз (±7 полутонов = ±1 по кругу квинт)
        if (semitones == 7 || semitones == 5) return 0.75f

        // Секунда (±2 полутона)
        if (semitones == 2 || semitones == 10) return 0.55f

        // Всё остальное
        return 0.2f
    }
}
