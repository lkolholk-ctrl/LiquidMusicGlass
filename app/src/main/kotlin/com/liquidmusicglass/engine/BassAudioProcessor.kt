package com.liquidmusicglass.engine

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import kotlin.math.sqrt

/**
 * Публикует уровни звука по трём полосам (0..1) для аудио-реактивных
 * эффектов UI (пульсация ауры на «Моей волне» и в плеере).
 *
 * Обновляется из аудио-потока ([BassAudioProcessor]); читается из UI.
 */
object AudioReactor {
    /** Низкие частоты (бас), 0..1. */
    @Volatile
    var low: Float = 0f

    /** Средние частоты, 0..1. */
    @Volatile
    var mid: Float = 0f

    /** Высокие частоты, 0..1. */
    @Volatile
    var high: Float = 0f

    /** Алиас баса для обратной совместимости. */
    val level: Float get() = low
}

/**
 * Прозрачный [AudioProcessor]: НЕ меняет звук (копирует вход в выход
 * один-в-один), а попутно раскладывает сигнал на три полосы двумя
 * однополюсными low-pass'ами и публикует их уровни в [AudioReactor].
 *
 * Работает только с 16-бит PCM; для других форматов остаётся неактивным.
 */
@UnstableApi
class BassAudioProcessor : BaseAudioProcessor() {

    private var lpLow = 0f   // ~150 Гц
    private var lpMid = 0f   // ~1.8 кГц
    private var envLow = 0f
    private var envMid = 0f
    private var envHigh = 0f

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

        // ── Анализ полос (не двигаем позицию inputBuffer) ──
        val shorts = inputBuffer.asShortBuffer()
        val total = shorts.remaining()
        val channels = inputAudioFormat.channelCount.coerceAtLeast(1)
        var lo = lpLow
        var md = lpMid
        var eLow = 0.0
        var eMid = 0.0
        var eHigh = 0.0
        var count = 0
        var i = 0
        while (i < total) {
            val s = shorts.get(i) / 32768f
            lo += LP_LOW_ALPHA * (s - lo)        // всё ниже ~150 Гц
            md += LP_MID_ALPHA * (s - md)        // всё ниже ~1.8 кГц
            val bass = lo
            val middle = md - lo                 // 150 Гц .. 1.8 кГц
            val treble = s - md                  // выше ~1.8 кГц
            eLow += (bass * bass).toDouble()
            eMid += (middle * middle).toDouble()
            eHigh += (treble * treble).toDouble()
            count++
            i += channels
        }
        lpLow = lo
        lpMid = md

        if (count > 0) {
            val rLow = (sqrt(eLow / count).toFloat() * GAIN_LOW).coerceIn(0f, 1f)
            val rMid = (sqrt(eMid / count).toFloat() * GAIN_MID).coerceIn(0f, 1f)
            val rHigh = (sqrt(eHigh / count).toFloat() * GAIN_HIGH).coerceIn(0f, 1f)
            envLow = envelope(envLow, rLow)
            envMid = envelope(envMid, rMid)
            envHigh = envelope(envHigh, rHigh)
            AudioReactor.low = envLow
            AudioReactor.mid = envMid
            AudioReactor.high = envHigh
        }

        // ── Передаём звук дальше без изменений ──
        val output = replaceOutputBuffer(sizeBytes)
        output.put(inputBuffer)
        output.flip()
    }

    private fun envelope(current: Float, target: Float): Float =
        if (target > current) target else current + (target - current) * DECAY

    override fun onFlush() = reset0()
    override fun onReset() = reset0()

    private fun reset0() {
        lpLow = 0f; lpMid = 0f
        envLow = 0f; envMid = 0f; envHigh = 0f
        AudioReactor.low = 0f; AudioReactor.mid = 0f; AudioReactor.high = 0f
    }

    private companion object {
        const val LP_LOW_ALPHA = 0.02f   // ~150 Гц при 44.1к
        const val LP_MID_ALPHA = 0.25f   // ~1.8 кГц
        const val GAIN_LOW = 3.5f
        const val GAIN_MID = 4.5f
        const val GAIN_HIGH = 6.0f
        const val DECAY = 0.06f          // спад огибающей за буфер
    }
}
