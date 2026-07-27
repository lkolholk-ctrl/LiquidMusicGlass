package com.liquidmusicglass.engine

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.liquidmusicglass.engine.vad.VadLyricsEngine
import com.liquidmusicglass.engine.vad.VocalState
import java.nio.ByteBuffer
import kotlin.math.sqrt

/**
 * Публикует уровни звука по трём полосам (0..1) для аудио-реактивных
 * эффектов UI (пульсация ауры на «Моей волне» и в плеере).
 *
 * Обновляется из аудио-потока ([BassAudioProcessor]); читается из UI.
 */
object AudioReactor {
    @Volatile private var _low = 0f
    @Volatile private var _mid = 0f
    @Volatile private var _high = 0f

    /**
     * Когда уровни читали в последний раз.
     *
     * По нему [hasListeners] решает, считать ли полосы вообще. Счётчик
     * подписчиков был бы точнее, но рассинхрон счётчика убивает пульсацию
     * намертво и молча; отметка времени сама себя чинит — как только кто-то
     * снова прочитает уровень, разбор возобновится со следующего же буфера.
     */
    @Volatile private var lastReadAt = 0L

    /** Низкие частоты (бас), 0..1. */
    var low: Float
        get() { lastReadAt = android.os.SystemClock.uptimeMillis(); return _low }
        set(value) { _low = value }

    /** Средние частоты, 0..1. */
    var mid: Float
        get() { lastReadAt = android.os.SystemClock.uptimeMillis(); return _mid }
        set(value) { _mid = value }

    /** Высокие частоты, 0..1. */
    var high: Float
        get() { lastReadAt = android.os.SystemClock.uptimeMillis(); return _high }
        set(value) { _high = value }

    /** Алиас баса для обратной совместимости. */
    val level: Float get() = low

    /**
     * Нужен ли кому-то разбор по полосам прямо сейчас.
     *
     * Раньше посэмпльный цикл с двумя фильтрами крутился всегда — и с закрытым
     * приложением тоже, хотя результат никто не читал.
     */
    val hasListeners: Boolean
        get() = android.os.SystemClock.uptimeMillis() - lastReadAt < IDLE_TIMEOUT_MS

    private const val IDLE_TIMEOUT_MS = 2_000L

    // ── Мост для JUCE-локалки ────────────────────────────────────────────
    // Стриминг кормит уровни из BassAudioProcessor (цепочка ExoPlayer); у
    // локального JUCE-пути этой цепочки нет — дым/пульс обложки были МЕРТВЫ
    // на локальной музыке. Мост нормализует нативную бас-огибающую
    // (automix LP ~110 Гц, уже считается для хаптики) в тот же 0..1
    // адаптивным пиком: быстрый захват вверх, медленный спад (~30с на тиках
    // ~100мс). Кормится из тикера JuceLocalPlayer.
    @Volatile private var jucePeak = 0.05f

    fun feedJuceBass(env: Float) {
        val e = if (env.isFinite() && env > 0f) env else 0f
        jucePeak = if (e > jucePeak) e else (jucePeak * 0.998f).coerceAtLeast(0.02f)
        low = (e / jucePeak).coerceIn(0f, 1f)
    }
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

        val shorts = inputBuffer.asShortBuffer()
        val channels = inputAudioFormat.channelCount.coerceAtLeast(1)

        // Разбор по полосам нужен только пока его читают (пульсация обложки,
        // аура, хаптика). Экран погашен или приложение свёрнуто — считать нечего,
        // а цикл по каждому сэмплу с двумя фильтрами крутился всё равно.
        val analyze = AudioReactor.hasListeners
        // ── Анализ полос (не двигаем позицию inputBuffer) ──
        val total = if (analyze) shorts.remaining() else 0
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

        // ── VAD-лирики: переиспользуем ТОТ ЖЕ PCM-тап (никакого второго source).
        // shorts.get(index) — абсолютный доступ, позиция inputBuffer не сдвинута,
        // поэтому ниже звук копируется в выход целиком как обычно. ──
        if (VocalState.enabled) {
            shorts.rewind()
            VadLyricsEngine.feed(shorts, channels, inputAudioFormat.sampleRate)
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
