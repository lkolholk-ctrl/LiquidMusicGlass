package com.liquidmusicglass.automix

import android.content.Context
import android.net.Uri
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * AutoMix v3 — мульти-выходная модель (Pro).
 */
class MLTransitionPredictor(context: Context) {

    private val interpreter: Interpreter = ModelLoader.load(context)

    data class Prediction(
        val bpmDrift: Float,
        val lowPassCurve: FloatArray,
        val energyScore: Float
    )

    private fun melToByteBuffer(mel: Array<Array<FloatArray>>): ByteBuffer {
        val frames = mel[0]
        val numFrames = frames.size
        val numMels = frames[0].size
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

        val outBpmDrift = Array(1) { FloatArray(1) }
        val outLowPass = Array(1) { FloatArray(10) }
        val outEnergy = Array(1) { FloatArray(1) }

        val inputs = arrayOf(bufferA, bufferB, bufferAux)
        val outputs = mapOf(
            0 to outBpmDrift,
            1 to outLowPass,
            2 to outEnergy
        )

        interpreter.runForMultipleInputsOutputs(inputs, outputs)

        return Prediction(
            bpmDrift = outBpmDrift[0][0],
            lowPassCurve = outLowPass[0],
            energyScore = outEnergy[0][0].coerceIn(0f, 1f)
        )
    }

    fun close() {
        interpreter.close()
    }
}
