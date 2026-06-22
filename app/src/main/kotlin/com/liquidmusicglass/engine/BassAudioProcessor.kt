package com.liquidmusicglass.engine

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import kotlin.math.sqrt

/**
 * Публикует текущий уровень баса (0..1) для аудио-реактивных эффектов UI
 * (например, пульсация свечения на экране «Моя волна»).
 *
 * Обновляется из аудио-потока ([BassAudioProcessor]); читается из UI.
 */
object AudioReactor {
    /** Сглаженный уровень низких частот, 0..1. */
    @Volatile
    var level: Float = 0f
}

/**
 * Прозрачный [AudioProcessor]: НЕ меняет звук (копирует вход в выход
 * один-в-один), а попутно считает энергию низких частот через однополюсный
 * low-pass и публикует её в [AudioReactor].
 *
 * Работает только с 16-бит PCM; для других форматов остаётся неактивным
 * (звук проходит мимо без изменений).
 */
@UnstableApi
class BassAudioProcessor : BaseAudioProcessor() {

    private var lowpass = 0f
    private var envelope = 0f

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        return if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT) {
            inputAudioFormat
        } else {
            // Неподдерживаемый формат → процессор неактивен (passthrough).
            AudioProcessor.AudioFormat.NOT_SET
        }
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val sizeBytes = inputBuffer.remaining()
        if (sizeBytes == 0) return

        // ── Анализ низких частот (не двигаем позицию inputBuffer) ──
        val shorts = inputBuffer.asShortBuffer()
        val total = shorts.remaining()
        val channels = inputAudioFormat.channelCount.coerceAtLeast(1)
        var lp = lowpass
        var energy = 0.0
        var count = 0
        var i = 0
        while (i < total) {
            val sample = shorts.get(i) / 32768f
            lp += LP_ALPHA * (sample - lp)
            energy += (lp * lp).toDouble()
            count++
            i += channels // достаточно одного канала
        }
        lowpass = lp

        val rms = if (count > 0) sqrt(energy / count).toFloat() else 0f
        val norm = (rms * BASS_GAIN).coerceIn(0f, 1f)
        // Быстрая атака, медленный спад — чтобы «бить» по басам.
        envelope = if (norm > envelope) norm else envelope + (norm - envelope) * DECAY
        AudioReactor.level = envelope

        // ── Передаём звук дальше без изменений ──
        val output = replaceOutputBuffer(sizeBytes)
        output.put(inputBuffer)
        output.flip()
    }

    override fun onFlush() {
        lowpass = 0f
        envelope = 0f
        AudioReactor.level = 0f
    }

    override fun onReset() {
        lowpass = 0f
        envelope = 0f
        AudioReactor.level = 0f
    }

    private companion object {
        // ~150 Гц срез на 44.1 кГц (2π·fc/fs).
        const val LP_ALPHA = 0.02f
        // Масштаб громкости баса → 0..1 (подбирается на устройстве).
        const val BASS_GAIN = 3.5f
        // Скорость спада огибающей за буфер.
        const val DECAY = 0.06f
    }
}
