package com.liquidmusicglass.automix

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Energy Analyzer — карта энергии трека и определение секций.
 *
 * Определяет:
 * - Энергетическую кривую (energy curve) по всему треку
 * - Секции: intro, verse, chorus/drop, outro
 * - Лучшую точку для начала кроссфейда (outro start)
 * - Лучшую точку входа в следующий трек (после intro)
 */
object EnergyAnalyzer {

    /** Секция трека */
    enum class SectionType { INTRO, VERSE, CHORUS, OUTRO, UNKNOWN }

    data class Section(
        val type: SectionType,
        val startMs: Long,
        val endMs: Long,
        val avgEnergy: Float
    )

    data class TrackEnergy(
        /** Энергетическая кривая: значения 0..1, один элемент на ~100мс */
        val curve: FloatArray,
        /** Средняя энергия всего трека */
        val avgEnergy: Float,
        /** Пиковая энергия */
        val peakEnergy: Float,
        /** Определённые секции */
        val sections: List<Section>,
        /** Рекомендуемая точка начала outro (мс от начала) */
        val outroStartMs: Long,
        /** Рекомендуемая точка входа после intro (мс от начала) */
        val introEndMs: Long,
        /** BPM (если определён) */
        val bpm: Float?,
        /** Тональность (если определена) */
        val key: KeyDetector.KeyResult?
    )

    private const val ENERGY_HOP_MS = 100 // один элемент кривой = 100мс
    private const val SMOOTH_WINDOW = 5   // сглаживание энергетической кривой

    /**
     * Полный анализ трека.
     * @param samples моно PCM float [-1, 1]
     * @param sampleRate частота дискретизации
     * @param durationMs длительность трека
     */
    fun analyze(
        samples: FloatArray,
        sampleRate: Int = MelSpectrogram.SAMPLE_RATE,
        durationMs: Long
    ): TrackEnergy {
        val samplesPerBin = (sampleRate * ENERGY_HOP_MS) / 1000
        val numBins = samples.size / samplesPerBin
        if (numBins < 4) return defaultEnergy(durationMs)

        // 1. Вычисляем RMS энергию по бинам
        val rawEnergy = FloatArray(numBins) { bin ->
            val start = bin * samplesPerBin
            val end = min(start + samplesPerBin, samples.size)
            var sumSq = 0f
            for (i in start until end) {
                sumSq += samples[i] * samples[i]
            }
            sqrt(sumSq / (end - start).coerceAtLeast(1))
        }

        // 2. Сглаживание
        val smoothed = smoothCurve(rawEnergy, SMOOTH_WINDOW)

        // 3. Нормализация 0..1
        val maxE = smoothed.maxOrNull() ?: 1f
        if (maxE > 0f) {
            for (i in smoothed.indices) smoothed[i] /= maxE
        }

        val avgEnergy = smoothed.average().toFloat()
        val peakEnergy = smoothed.maxOrNull() ?: 0f

        // 4. Определение секций
        val sections = detectSections(smoothed, durationMs)

        // 5. Outro start: где энергия начинает падать в конце
        val outroStartMs = findOutroStart(smoothed, durationMs)

        // 6. Intro end: где энергия стабилизируется в начале
        val introEndMs = findIntroEnd(smoothed, durationMs)

        // 7. BPM и Key (опционально, может быть вычислено отдельно)
        val bpm = try { BPMDetector.detectBPM(samples, sampleRate) } catch (_: Exception) { null }
        val key = try { KeyDetector.detectKey(samples, sampleRate) } catch (_: Exception) { null }

        return TrackEnergy(
            curve = smoothed,
            avgEnergy = avgEnergy,
            peakEnergy = peakEnergy,
            sections = sections,
            outroStartMs = outroStartMs,
            introEndMs = introEndMs,
            bpm = bpm,
            key = key
        )
    }

    /**
     * Детекция секций: делим трек на 8 равных частей,
     * анализируем энергию каждой и классифицируем.
     */
    private fun detectSections(energy: FloatArray, durationMs: Long): List<Section> {
        val numSections = 8
        val binsPerSection = energy.size / numSections
        if (binsPerSection < 1) return emptyList()

        val avgEnergy = energy.average().toFloat()
        val sections = mutableListOf<Section>()

        for (s in 0 until numSections) {
            val start = s * binsPerSection
            val end = if (s == numSections - 1) energy.size else (s + 1) * binsPerSection

            var sum = 0f
            for (i in start until end) sum += energy[i]
            val sectionAvg = sum / (end - start)

            val startMs = (s.toLong() * durationMs) / numSections
            val endMs = ((s + 1).toLong() * durationMs) / numSections

            val type = when {
                s == 0 && sectionAvg < avgEnergy * 0.7f -> SectionType.INTRO
                s >= numSections - 2 && sectionAvg < avgEnergy * 0.6f -> SectionType.OUTRO
                sectionAvg > avgEnergy * 1.2f -> SectionType.CHORUS
                else -> SectionType.VERSE
            }

            sections.add(Section(type, startMs, endMs, sectionAvg))
        }

        return sections
    }

    /**
     * Найти точку начала outro: сканируем последние 30% трека,
     * ищем устойчивое падение энергии.
     */
    private fun findOutroStart(energy: FloatArray, durationMs: Long): Long {
        val searchStart = (energy.size * 0.7f).toInt()
        val avgLastThird = energy.sliceArray(searchStart until energy.size).average().toFloat()
        val avgFull = energy.average().toFloat()

        // Ищем момент когда энергия стабильно ниже 60% от средней
        val threshold = avgFull * 0.6f
        var dropStart = energy.size - 1

        for (i in searchStart until energy.size) {
            // Проверяем 3 подряд бина ниже порога
            if (i + 2 < energy.size &&
                energy[i] < threshold &&
                energy[i + 1] < threshold &&
                energy[i + 2] < threshold
            ) {
                dropStart = i
                break
            }
        }

        val outroMs = (dropStart.toLong() * durationMs) / energy.size
        // Не раньше чем за 20 секунд до конца
        return max(outroMs, durationMs - 20_000L)
    }

    /**
     * Найти конец intro: сканируем первые 20% трека,
     * ищем момент когда энергия стабилизируется.
     */
    private fun findIntroEnd(energy: FloatArray, durationMs: Long): Long {
        val searchEnd = (energy.size * 0.2f).toInt()
        val avgFull = energy.average().toFloat()

        // Ищем момент когда энергия поднимается до 50% от средней
        val threshold = avgFull * 0.5f
        var riseEnd = 0

        for (i in 0 until min(searchEnd, energy.size)) {
            if (energy[i] >= threshold) {
                riseEnd = i
                break
            }
        }

        val introEndMs = (riseEnd.toLong() * durationMs) / energy.size.coerceAtLeast(1)
        // Не больше 10 секунд
        return min(introEndMs, 10_000L)
    }

    private fun smoothCurve(data: FloatArray, window: Int): FloatArray {
        val result = FloatArray(data.size)
        val halfW = window / 2
        for (i in data.indices) {
            var sum = 0f
            var count = 0
            for (j in max(0, i - halfW)..min(data.size - 1, i + halfW)) {
                sum += data[j]
                count++
            }
            result[i] = sum / count
        }
        return result
    }

    private fun defaultEnergy(durationMs: Long) = TrackEnergy(
        curve = floatArrayOf(0.5f),
        avgEnergy = 0.5f,
        peakEnergy = 0.5f,
        sections = emptyList(),
        outroStartMs = max(0L, durationMs - 10_000L),
        introEndMs = 0L,
        bpm = null,
        key = null
    )

    /**
     * Совместимость энергии: похожий уровень энергии = хороший переход.
     */
    fun energyCompatibility(energy1: Float, energy2: Float): Float {
        val diff = kotlin.math.abs(energy1 - energy2)
        return when {
            diff < 0.1f -> 1.0f
            diff < 0.2f -> 0.85f
            diff < 0.35f -> 0.6f
            diff < 0.5f -> 0.4f
            else -> 0.2f
        }
    }
}
