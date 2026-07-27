package com.liquidmusicglass.engine

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Нормализация громкости между треками (Sound Check / ReplayGain-стиль).
 *
 * ReplayGain-тегов у стримов нет, поэтому громкость измеряется на слух: первые
 * [MEASURE_SECONDS] секунд копим энергию сигнала и подстраиваем усиление, а
 * дальше **фиксируем его до конца трека**.
 *
 * Раньше подстройка шла всё время — то есть регулятор громкости жил своей
 * жизнью внутри трека: тихий куплет подтягивался вверх, громкий припев
 * приглушался, и динамика записи размывалась. Ровнее звучало между треками, но
 * хуже внутри каждого. У Apple усиление — одно число на трек, посчитанное
 * заранее; фиксация после короткого замера даёт то же самое без их метаданных.
 *
 * Пики отслеживаются отдельно: усиление ограничивается так, чтобы самый громкий
 * сэмпл замера не вышел за пределы разрядной сетки. Прежний вариант умножал и
 * срезал всё, что вылезло, — это слышимое искажение на громких мастерах.
 *
 * Включается флагом [PlayerSettings.volumeNormalization]; при выключенном —
 * прозрачный проброс. Работает с 16-бит PCM.
 */
@UnstableApi
class VolumeNormalizationProcessor : BaseAudioProcessor() {

    private var loudnessEnv = 0f       // огибающая RMS трека (0..32768)
    private var gain = 1f              // текущее усиление
    private var peak = 0f              // максимум модуля сэмпла за замер
    private var framesSeen = 0L        // сколько кадров прошло с начала трека
    private var framesToMeasure = 0L   // длительность замера в кадрах
    private var frozen = false         // замер окончен, усиление больше не меняем

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        return if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT) {
            framesToMeasure = (inputAudioFormat.sampleRate.toLong() * MEASURE_SECONDS)
                .coerceAtLeast(1L)
            inputAudioFormat
        } else {
            AudioProcessor.AudioFormat.NOT_SET
        }
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val sizeBytes = inputBuffer.remaining()
        if (sizeBytes == 0) return

        val output = replaceOutputBuffer(sizeBytes)

        // Выключено → прозрачный проброс (звук один-в-один).
        if (!PlayerSettings.volumeNormalization.value) {
            output.put(inputBuffer)
            output.flip()
            return
        }

        val inShorts = inputBuffer.asShortBuffer()   // абсолютный доступ, позицию не двигает
        val n = inShorts.remaining()
        if (n == 0) {
            output.put(inputBuffer)
            output.flip()
            return
        }

        if (!frozen) {
            // ── RMS и пик текущего буфера ──
            var sumSq = 0.0
            var i = 0
            while (i < n) {
                val s = inShorts.get(i).toInt()
                sumSq += (s * s).toDouble()
                val a = abs(s).toFloat()
                if (a > peak) peak = a
                i++
            }
            val rms = sqrt(sumSq / n).toFloat()

            // Медленная интеграция громкости трека (только на не-тишине).
            if (rms > NOISE_FLOOR) {
                loudnessEnv = if (loudnessEnv <= 0f) rms
                else loudnessEnv + ENV_COEF * (rms - loudnessEnv)
            }

            // Целевое усиление к опорному уровню, плавно.
            val ref = if (loudnessEnv > NOISE_FLOOR) loudnessEnv else rms.coerceAtLeast(1f)
            val targetGain = (TARGET_RMS / ref).coerceIn(MIN_GAIN, MAX_GAIN)
            gain += GAIN_SMOOTH * (targetGain - gain)

            framesSeen += n
            if (framesSeen >= framesToMeasure) {
                // Потолок по пику: усиливать больше нельзя, иначе срежем верхушки.
                val headroom = if (peak > 1f) (PEAK_CEILING / peak) else MAX_GAIN
                gain = gain.coerceAtMost(headroom).coerceIn(MIN_GAIN, MAX_GAIN)
                frozen = true
            }
        }

        // ── Применяем к сэмплам. Клип остаётся страховкой на случай, если пик
        //    после замера окажется выше замеренного, но в норме не срабатывает.
        val outShorts = output.asShortBuffer()
        var i = 0
        while (i < n) {
            val v = inShorts.get(i) * gain
            val clamped = when {
                v > 32767f -> 32767
                v < -32768f -> -32768
                else -> v.toInt()
            }
            outShorts.put(clamped.toShort())
            i++
        }

        // Сдвигаем позиции: вход — потреблён, выход — заполнен на sizeBytes.
        inputBuffer.position(inputBuffer.limit())
        output.position(sizeBytes)
        output.flip()
    }

    override fun onFlush() = reset0()
    override fun onReset() = reset0()

    private fun reset0() {
        loudnessEnv = 0f
        gain = 1f
        peak = 0f
        framesSeen = 0L
        frozen = false
    }

    private companion object {
        const val TARGET_RMS = 6500f     // ≈ −14 dBFS RMS для 16-бит
        const val NOISE_FLOOR = 200f     // ниже — тишина/интро, не учитываем
        const val ENV_COEF = 0.04f       // интеграция громкости трека (плавно)
        const val GAIN_SMOOTH = 0.05f    // сглаживание усиления (без «пыхтения»)
        const val MIN_GAIN = 0.4f        // ≈ −8 dB (приглушить громкий мастер)
        const val MAX_GAIN = 2.5f        // ≈ +8 dB (подтянуть тихий трек)

        /** Сколько секунд слушаем трек, прежде чем зафиксировать усиление. */
        const val MEASURE_SECONDS = 20L

        /** Потолок пика после усиления: чуть ниже максимума, с запасом. */
        const val PEAK_CEILING = 32000f
    }
}
