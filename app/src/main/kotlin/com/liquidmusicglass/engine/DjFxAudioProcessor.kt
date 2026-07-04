package com.liquidmusicglass.engine

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.exp
import kotlin.math.pow

/**
 * Состояние DJ-эффектов СТРИМИНГОВОГО перехода (dual-ExoPlayer кроссфейд).
 * Пишет [com.liquidmusicglass.automix.DJEffectsEngine.crossfadeWithEffects],
 * читает [DjFxAudioProcessor] в аудио-цепочке уходящего плеера.
 */
object DjStreamFx {
    const val MODE_NONE = 0
    const val MODE_SWEEP = 4       // FILTER_SWEEP: LP-муффл уходящего
    const val MODE_ECHO = 5        // ECHO_OUT: делэй-повторы уходящего
    const val MODE_ECHO_TAIL = 6   // дозвучка хвоста: dry замьючен, звенят повторы

    private val modeA = AtomicInteger(MODE_NONE)
    private val progressMilli = AtomicInteger(0)
    private val outVolMilli = AtomicInteger(1000)

    val mode: Int get() = modeA.get()
    val progress: Float get() = progressMilli.get() / 1000f
    /** Текущая громкость уходящего плеера — для компенсации post-fader эха. */
    val outVolume: Float get() = outVolMilli.get() / 1000f

    fun begin(transitionType: Int) {
        progressMilli.set(0)
        outVolMilli.set(1000)
        modeA.set(
            when (transitionType) {
                4 -> MODE_SWEEP
                5 -> MODE_ECHO
                else -> MODE_NONE
            }
        )
    }

    fun update(p: Float, outVol: Float) {
        progressMilli.set((p.coerceIn(0f, 1f) * 1000).toInt())
        outVolMilli.set((outVol.coerceIn(0f, 1f) * 1000).toInt())
    }

    /** Переход кончился (type 5): dry глушится процессором, повторы дозвучивают. */
    fun beginTail() {
        outVolMilli.set(1000)
        modeA.set(MODE_ECHO_TAIL)
    }

    fun stop() {
        modeA.set(MODE_NONE)
        progressMilli.set(0)
        outVolMilli.set(1000)
    }
}

/**
 * DJ-эффекты стримингового перехода в цепочке ExoPlayer — зеркало нативных
 * эффектов JUCE-пути (AutoMixAudioEngine):
 *  - FILTER_SWEEP: one-pole LP, срез уезжает экспоненциально 16кГц→150Гц;
 *  - ECHO_OUT: feedback-делэй 375мс (fb 0.42), send растёт с прогрессом;
 *    в фазе TAIL dry замьючен — поверх нового трека звенят только повторы,
 *    гаснущие в последние 0.25с.
 * Эхо post-fader (громкость плеера умножает и его), поэтому wet
 * компенсируется текущей громкостью уходящего (кап 2.5x).
 * Вне перехода полностью прозрачен: ни одного умножения на сэмпл.
 */
@UnstableApi
class DjFxAudioProcessor : BaseAudioProcessor() {

    private var sweepLp = FloatArray(2)
    private var echoBuf = FloatArray(0)      // кольцо [pos*2 + channel]
    private var echoLen = 0
    private var echoPos = 0
    private var tailRemaining = 0L
    private var tailArmed = false
    private var dirty = false                // нужен сброс состояний перед новым переходом
    private var sampleRate = 44100

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        sampleRate = inputAudioFormat.sampleRate
        echoLen = (sampleRate * 0.375).toInt().coerceAtLeast(1)
        echoBuf = FloatArray(echoLen * 2)
        echoPos = 0
        tailRemaining = 0
        tailArmed = false
        return inputAudioFormat
    }

    private fun resetFxState() {
        sweepLp[0] = 0f; sweepLp[1] = 0f
        echoBuf.fill(0f)
        echoPos = 0
        tailRemaining = 0
        tailArmed = false
        dirty = false
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val sizeBytes = inputBuffer.remaining()
        if (sizeBytes == 0) return

        val mode = DjStreamFx.mode

        // Быстрый путь: вне перехода — прозрачная передача.
        if (mode == DjStreamFx.MODE_NONE || echoLen == 0) {
            if (dirty) resetFxState()
            val output = replaceOutputBuffer(sizeBytes)
            output.put(inputBuffer)
            output.flip()
            return
        }
        dirty = true

        val channels = inputAudioFormat.channelCount.coerceAtLeast(1)
        val inShorts = inputBuffer.asShortBuffer()
        val output = replaceOutputBuffer(sizeBytes)
        val outShorts = output.asShortBuffer()
        val frames = inShorts.remaining() / channels

        val p = DjStreamFx.progress

        // FILTER_SWEEP: коэффициент на блок (~5-20мс) — для one-pole достаточно.
        var sweepAlpha = 0f
        if (mode == DjStreamFx.MODE_SWEEP) {
            val cutoff = 16000f * (150f / 16000f).pow(p)
            sweepAlpha = 1f - exp(-2f * Math.PI.toFloat() * cutoff / sampleRate)
        }

        // ECHO: send растёт с прогрессом; wet компенсирует post-fader громкость.
        val echoActive = mode == DjStreamFx.MODE_ECHO
        val tailActive = mode == DjStreamFx.MODE_ECHO_TAIL
        if (tailActive && !tailArmed) {
            tailArmed = true
            tailRemaining = (sampleRate * 2.2).toLong()
        }
        val smoothP = p * p * (3f - 2f * p)
        val send = if (echoActive) smoothP * 0.55f else 0f
        // Кап 1.8x: выход процессора — PCM16, клампится ДО громкости плеера;
        // больший буст срезал бы волну эха на жирном материале.
        val volComp = (1f / DjStreamFx.outVolume.coerceAtLeast(0.45f)).coerceAtMost(1.8f)
        val tailFadeSamples = (sampleRate * 0.25f)

        var base = 0
        for (f in 0 until frames) {
            val wet = when {
                echoActive -> volComp
                tailActive && tailRemaining > 0 ->
                    (tailRemaining / tailFadeSamples).coerceAtMost(1f)
                else -> 0f
            }
            for (c in 0 until channels) {
                var s = inShorts.get(base + c) / 32768f
                if (c < 2) {
                    if (sweepAlpha > 0f) {
                        sweepLp[c] += sweepAlpha * (s - sweepLp[c])
                        s = sweepLp[c]
                    }
                    if (echoActive || tailActive) {
                        val bi = echoPos * 2 + c
                        val y = echoBuf[bi]
                        echoBuf[bi] = s * send + y * 0.42f
                        // TAIL: dry замьючен — поверх нового трека только повторы.
                        s = if (tailActive) y * wet else s + y * wet
                    }
                }
                // Мягкое колено (только в FX-режиме): dry+эхо в 16-бит цепочке
                // может превысить 1.0 — асимптотический лимитер вместо жёсткого
                // среза. 0.88 + 0.12·x/(x+0.25) < 1.0 всегда.
                val a = if (s >= 0f) s else -s
                if (a > 0.88f) {
                    val x = a - 0.88f
                    val lim = 0.88f + 0.12f * (x / (x + 0.25f))
                    s = if (s >= 0f) lim else -lim
                }
                val v = (s * 32767f).coerceIn(-32768f, 32767f)
                outShorts.put(base + c, v.toInt().toShort())
            }
            if (echoActive || tailActive) {
                if (++echoPos >= echoLen) echoPos = 0
                if (tailActive && tailRemaining > 0) tailRemaining--
            }
            base += channels
        }

        inputBuffer.position(inputBuffer.limit())
        output.position(sizeBytes)
        output.flip()
    }

    override fun onFlush() {
        resetFxState()
    }

    override fun onReset() {
        resetFxState()
    }
}
