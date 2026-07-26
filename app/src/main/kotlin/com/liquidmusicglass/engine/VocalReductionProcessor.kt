package com.liquidmusicglass.engine

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import kotlin.math.PI

/**
 * Подавление вокала для караоке-режима (Sing).
 *
 * Вокал в подавляющем большинстве сведений сидит ровно по центру стереобазы,
 * поэтому он гасится вычитанием центрального (mid) компонента: `mid = (L+R)/2`,
 * и дальше `L' = L - k·mid`, `R' = R - k·mid`. При k = 1 центр исчезает
 * полностью, при k = 0 сигнал не меняется — это и есть ползунок «сколько голоса
 * убрать».
 *
 * Одна тонкость решает, звучит это музыкой или кашей: по центру лежит не только
 * голос, но и бочка с басом. Если вычитать mid целиком, вместе с вокалом
 * пропадает весь низ, и минусовка разваливается. Поэтому центр сначала делится
 * на низ и верх однополюсным фильтром, и вычитается **только верхняя часть** —
 * бас и бочка остаются на месте.
 *
 * Удаление центра забирает часть энергии, поэтому применяется мягкая компенсация
 * громкости: без неё караоке-режим звучит заметно тише обычного и переключение
 * воспринимается как «сломалось».
 *
 * Работает только со стерео 16-бит PCM: у моно нет стереобазы, разделять нечего —
 * там сигнал проходит насквозь. Управляется настройкой
 * [PlayerSettings.vocalReductionPercent]; при нуле — прозрачный проброс.
 */
@UnstableApi
class VocalReductionProcessor : BaseAudioProcessor() {

    /** Ниже этой частоты центр не трогаем — там бас и бочка, а не голос. */
    private val bassProtectHz = 220f

    /** Состояние однополюсного ФНЧ, выделяющего низ из центра. */
    private var lowMidState = 0f

    /** Коэффициент фильтра, зависит от частоты дискретизации. */
    private var alpha = 0f

    private var isStereo = false

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        isStereo = inputAudioFormat.channelCount == 2
        val dt = 1f / inputAudioFormat.sampleRate
        val rc = 1f / (2f * PI.toFloat() * bassProtectHz)
        alpha = dt / (rc + dt)
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val sizeBytes = inputBuffer.remaining()
        if (sizeBytes == 0) return

        val output = replaceOutputBuffer(sizeBytes)
        val strength = PlayerSettings.vocalReductionPercent.value / 100f

        // Выключено или моно — звук один-в-один.
        if (strength <= 0f || !isStereo) {
            output.put(inputBuffer)
            output.flip()
            return
        }

        val input = inputBuffer.asShortBuffer()
        val out = output.asShortBuffer()
        val frames = input.remaining() / 2

        // Компенсация потери энергии: центр уносит заметную часть громкости.
        val makeUp = 1f + 0.45f * strength

        for (i in 0 until frames) {
            val left = input.get(i * 2).toFloat()
            val right = input.get(i * 2 + 1).toFloat()

            val mid = (left + right) * 0.5f
            lowMidState += alpha * (mid - lowMidState)
            val highMid = mid - lowMidState   // центр без баса — здесь живёт голос

            val newLeft = (left - strength * highMid) * makeUp
            val newRight = (right - strength * highMid) * makeUp

            out.put(i * 2, clampToPcm(newLeft))
            out.put(i * 2 + 1, clampToPcm(newRight))
        }

        inputBuffer.position(inputBuffer.limit())
        output.position(output.limit())
        output.flip()
    }

    override fun onFlush() {
        lowMidState = 0f
    }

    override fun onReset() {
        lowMidState = 0f
        isStereo = false
    }

    private fun clampToPcm(value: Float): Short = when {
        value > Short.MAX_VALUE -> Short.MAX_VALUE
        value < Short.MIN_VALUE -> Short.MIN_VALUE
        else -> value.toInt().toShort()
    }
}
