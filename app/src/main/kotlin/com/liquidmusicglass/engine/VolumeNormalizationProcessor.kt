package com.liquidmusicglass.engine

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import kotlin.math.sqrt

/**
 * Нормализация громкости между треками (Sound Check / ReplayGain-стиль).
 *
 * ReplayGain-тегов у стримов нет, поэтому громкость измеряется **в реальном
 * времени**: считаем RMS PCM-буфера → медленно интегрируем в огибающую громкости
 * трека → вычисляем усиление к целевому уровню [TARGET_RMS] → плавно применяем к
 * сэмплам. Итог — тихие треки подтягиваются, громкие приглушаются, так что
 * соседние треки звучат ровно (как Sound Check у Apple).
 *
 * Огибающая и усиление сбрасываются на каждом [onFlush] (смена трека/seek), так
 * что подстройка идёт под КАЖДЫЙ трек заново.
 *
 * Включается флагом [PlayerSettings.volumeNormalization]; при выключенном —
 * прозрачный проброс (звук без изменений). Работает с 16-бит PCM.
 */
@UnstableApi
class VolumeNormalizationProcessor : BaseAudioProcessor() {

    private var loudnessEnv = 0f   // огибающая RMS трека (0..32768)
    private var gain = 1f          // текущее сглаженное усиление

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        return if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT) {
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

        // ── RMS текущего буфера ──
        var sumSq = 0.0
        var i = 0
        while (i < n) {
            val s = inShorts.get(i).toInt()
            sumSq += (s * s).toDouble()
            i++
        }
        val rms = sqrt(sumSq / n).toFloat()

        // ── Медленная интеграция громкости трека (только на не-тишине) ──
        if (rms > NOISE_FLOOR) {
            loudnessEnv = if (loudnessEnv <= 0f) rms
            else loudnessEnv + ENV_COEF * (rms - loudnessEnv)
        }

        // ── Целевое усиление к опорному уровню, плавно ──
        val ref = if (loudnessEnv > NOISE_FLOOR) loudnessEnv else rms.coerceAtLeast(1f)
        val targetGain = (TARGET_RMS / ref).coerceIn(MIN_GAIN, MAX_GAIN)
        gain += GAIN_SMOOTH * (targetGain - gain)

        // ── Применяем к сэмплам с жёстким клипом в диапазон short ──
        val outShorts = output.asShortBuffer()
        i = 0
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
    }

    private companion object {
        const val TARGET_RMS = 6500f     // ≈ −14 dBFS RMS для 16-бит
        const val NOISE_FLOOR = 200f     // ниже — тишина/интро, не учитываем
        const val ENV_COEF = 0.04f       // интеграция громкости трека (плавно)
        const val GAIN_SMOOTH = 0.05f    // сглаживание усиления (без «пыхтения»)
        const val MIN_GAIN = 0.4f        // ≈ −8 dB (приглушить громкий мастер)
        const val MAX_GAIN = 2.5f        // ≈ +8 dB (подтянуть тихий трек)
    }
}
