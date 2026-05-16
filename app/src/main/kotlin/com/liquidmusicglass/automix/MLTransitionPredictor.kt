package com.liquidmusicglass.automix

import android.content.Context
import android.net.Uri
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * AutoMix v3 — мульти-выходная модель (Pro).
 *
 * Входы:
 *   mel_a:  [1, 431, 128, 1]  — конец трека A
 *   mel_b:  [1, 431, 128, 1]  — начало трека B
 *   aux:    [1, 32]            — BPM, chroma, energy, spectral
 *
 * Выходы:
 *   compatibility:      [1, 1]    — совместимость 0..1
 *   crossfade_duration: [1, 1]    — длительность (normalized 0..1 → 2000..16000мс)
 *   entry_offset:       [1, 1]    — смещение входа (normalized 0..1 → 0..10000мс)
 *   transition_type:    [1, 6]    — softmax по 6 типам перехода
 *   transition_start:   [1, 1]    — точка начала (normalized 0..1 от длительности)
 */
class MLTransitionPredictor(context: Context) {

    private val interpreter: Interpreter = ModelLoader.load(context)

    data class Prediction(
        val compatibility: Float,
        val crossfadeDurationMs: Long,
        val entryOffsetMs: Long,
        val transitionType: Int,
        val transitionStartFraction: Float
    )

    /**
     * Конвертирует [1, 431, 128] в ByteBuffer формата [1, 431, 128, 1]
     * который ожидает TFLite модель (Conv2D требует channel dimension).
     */
    private fun melToByteBuffer(mel: Array<Array<FloatArray>>): ByteBuffer {
        val frames = mel[0]
        val numFrames = frames.size       // 431
        val numMels = frames[0].size      // 128
        val buffer = ByteBuffer.allocateDirect(numFrames * numMels * 4)
        buffer.order(ByteOrder.nativeOrder())
        for (frame in frames) {
            for (value in frame) {
                buffer.putFloat(value)
            }
        }
        buffer.rewind()
        return buffer
    }

    private fun auxToByteBuffer(aux: Array<FloatArray>): ByteBuffer {
        val values = aux[0]
        val buffer = ByteBuffer.allocateDirect(values.size * 4)
        buffer.order(ByteOrder.nativeOrder())
        for (v in values) {
            buffer.putFloat(v)
        }
        buffer.rewind()
        return buffer
    }

    fun predictPair(
        context: Context,
        trackAUri: Uri,
        trackBUri: Uri,
        trackADurationMs: Long
    ): Prediction {
        val features = FeatureExtractor.extractPairFromUri(
            context, trackAUri, trackBUri, trackADurationMs
        )

        val bufferA = melToByteBuffer(features.melA)
        val bufferB = melToByteBuffer(features.melB)
        val bufferAux = auxToByteBuffer(features.aux)

        val outCompat = Array(1) { FloatArray(1) }
        val outDuration = Array(1) { FloatArray(1) }
        val outOffset = Array(1) { FloatArray(1) }
        val outTransition = Array(1) { FloatArray(6) }
        val outStart = Array(1) { FloatArray(1) }

        val inputs = arrayOf(bufferA, bufferB, bufferAux)
        val outputs = mapOf(
            0 to outCompat,
            1 to outDuration,
            2 to outOffset,
            3 to outTransition,
            4 to outStart
        )

        interpreter.runForMultipleInputsOutputs(inputs, outputs)

        val transProbs = outTransition[0]
        var bestType = 0
        var bestProb = transProbs[0]
        for (i in 1 until transProbs.size) {
            if (transProbs[i] > bestProb) {
                bestProb = transProbs[i]
                bestType = i
            }
        }

        return Prediction(
            compatibility = outCompat[0][0].coerceIn(0f, 1f),
            crossfadeDurationMs = (outDuration[0][0] * 16000f).toLong().coerceIn(2000L, 16000L),
            entryOffsetMs = (outOffset[0][0] * 10000f).toLong().coerceIn(0L, 10000L),
            transitionType = bestType,
            transitionStartFraction = outStart[0][0].coerceIn(0f, 1f)
        )
    }

    fun close() {
        interpreter.close()
    }
}
