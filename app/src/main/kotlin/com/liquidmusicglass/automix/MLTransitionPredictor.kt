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
 *   crossfade_duration: [1, 1]    — длительность (normalized 0..1 → 5000..30000мс)
 *   entry_offset:       [1, 1]    — смещение входа (normalized 0..1 → 0..10000мс)
 *   transition_type:    [1, 6]    — softmax по 6 типам перехода
 *   transition_start:   [1, 1]    — точка начала (normalized 0..1 от длительности)
 */
class MLTransitionPredictor(context: Context) {

    private val interpreter: Interpreter = ModelLoader.load(context)

    // Индексы выходов резолвятся ПО ИМЕНИ тензора, а не по жёсткой позиции.
    // Если модель пере-экспортируют с другим порядком голов — маппинг не
    // сломается, и каждая голова читается из своего выхода (модель раскрыта
    // на полную). Если имена не распознаны — честный фолбэк на исходный
    // порядок Keras (compat, crossfade, entry, transition, start).
    private val idxCompat: Int
    private val idxDuration: Int
    private val idxOffset: Int
    private val idxTransition: Int
    private val idxStart: Int
    // ВХОДЫ тоже резолвятся по имени: если реальный порядок входов модели не
    // [mel_a, mel_b, aux], позиционная подача отправила бы буфер мела (~220 КБ)
    // в тензор aux (128 б) → TFLite бросает по размеру → тихий уход в алгоритм.
    private val idxMelA: Int
    private val idxMelB: Int
    private val idxAux: Int
    private val mapInfo: String

    init {
        var c = -1; var d = -1; var o = -1; var t = -1; var s = -1
        val count = try { interpreter.outputTensorCount } catch (_: Throwable) { 0 }
        for (i in 0 until count) {
            val rawName = try { interpreter.getOutputTensor(i).name() ?: "" } catch (_: Throwable) { "" }
            when (outputSemanticId(rawName)) {
                0 -> c = i
                1 -> d = i
                2 -> o = i
                3 -> t = i
                4 -> s = i
            }
        }
        // Голову transition_type подтверждаем ПО ФОРМЕ: это единственный выход
        // с 6 элементами ([1,6]). Это железная гарантия от краша «копирование
        // [1,6] → [1,1]», даже если имена/суффиксы окажутся нестандартными —
        // именно на этом падала прошлая сборка (StatefulPartitionedCall_1:3).
        for (i in 0 until count) {
            val shape = try { interpreter.getOutputTensor(i).shape() } catch (_: Throwable) { IntArray(0) }
            val elems = shape.fold(1) { a, b -> a * b }
            if (elems == 6) { t = i; break }
        }
        val byName = intArrayOf(c, d, o, t, s)
        // Принимаем маппинг только если ВСЕ 5 голов распознаны и индексы
        // различны — иначе любой частичный резолв мог бы дать коллизию.
        val ok = count == 5 && byName.all { it in 0..4 } && byName.toSet().size == 5
        if (ok) {
            idxCompat = c; idxDuration = d; idxOffset = o; idxTransition = t; idxStart = s
        } else {
            idxCompat = 0; idxDuration = 1; idxOffset = 2; idxTransition = 3; idxStart = 4
        }

        // --- входы по имени ---
        var ma = -1; var mb = -1; var ax = -1
        val inCount = try { interpreter.inputTensorCount } catch (_: Throwable) { 0 }
        for (i in 0 until inCount) {
            val name = try {
                interpreter.getInputTensor(i).name().lowercase()
            } catch (_: Throwable) { "" }
            when {
                "mel_a" in name -> ma = i
                "mel_b" in name -> mb = i
                "aux" in name -> ax = i
            }
        }
        val inByName = intArrayOf(ma, mb, ax)
        val inOk = inCount == 3 && inByName.all { it in 0..2 } && inByName.toSet().size == 3
        if (inOk) {
            idxMelA = ma; idxMelB = mb; idxAux = ax
        } else {
            idxMelA = 0; idxMelB = 1; idxAux = 2
        }

        mapInfo = "in[a$idxMelA,b$idxMelB,x$idxAux]" +
            (if (ok) " out[c$idxCompat,d$idxDuration,o$idxOffset,t$idxTransition,s$idxStart]"
             else " out:positional(n=$count)")
    }

    /**
     * Семантический индекс выходной головы по имени тензора:
     * 0=compatibility, 1=crossfade_duration, 2=entry_offset,
     * 3=transition_type, 4=transition_start.
     *
     * В этой модели реальные имена выходов — "StatefulPartitionedCall_1:N",
     * где N и есть порядковый номер головы Keras → берём суффикс ":N".
     * Если экспорт сохранил человекочитаемые имена — ловим по подстроке.
     * Возвращает -1, если распознать не удалось.
     */
    private fun outputSemanticId(name: String): Int {
        val lower = name.lowercase()
        when {
            "transition_type" in lower -> return 3
            "transition_start" in lower -> return 4
            "compatibility" in lower || "compat" in lower -> return 0
            "crossfade" in lower || "duration" in lower -> return 1
            "entry_offset" in lower || "offset" in lower -> return 2
        }
        val colon = name.lastIndexOf(':')
        if (colon in 0 until name.length - 1)
            return name.substring(colon + 1).toIntOrNull() ?: -1
        return -1
    }

    data class Prediction(
        val compatibility: Float,
        val crossfadeDurationMs: Long,
        val entryOffsetMs: Long,
        val transitionType: Int,
        val transitionStartFraction: Float,
        /** Диагностика: сырые значения голов модели (до денормализации/коэрса). */
        val debug: String
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

        // Входы — по индексу, резолвнутому ПО ИМЕНИ (mel_a/mel_b/aux), чтобы
        // буфер каждого входа попал в свой тензор независимо от порядка в модели.
        val inputs = arrayOfNulls<Any>(3)
        inputs[idxMelA] = bufferA
        inputs[idxMelB] = bufferB
        inputs[idxAux] = bufferAux
        @Suppress("UNCHECKED_CAST")
        val inputArray = inputs as Array<Any>

        // Каждый буфер кладём по индексу, резолвнутому ПО ИМЕНИ — [1,6]-голова
        // transition_type гарантированно попадает на свой выход.
        val outputs = HashMap<Int, Any>(5)
        outputs[idxCompat] = outCompat
        outputs[idxDuration] = outDuration
        outputs[idxOffset] = outOffset
        outputs[idxTransition] = outTransition
        outputs[idxStart] = outStart

        interpreter.runForMultipleInputsOutputs(inputArray, outputs)

        val transProbs = outTransition[0]
        var bestType = 0
        var bestProb = transProbs[0]
        for (i in 1 until transProbs.size) {
            if (transProbs[i] > bestProb) {
                bestProb = transProbs[i]
                bestType = i
            }
        }

        val rawCompat = outCompat[0][0]
        val rawDuration = outDuration[0][0]
        val rawOffset = outOffset[0][0]
        val rawStart = outStart[0][0]
        // Сырые выходы голов — чтобы по экранному логу видеть, ЖИВЫ ли головы
        // entry_offset/transition_type, или они реально близки к нулю/argmax=0.
        val debug = "raw[c=%.3f d=%.3f o=%.3f s=%.3f] tlogits=[%s] %s".format(
            rawCompat, rawDuration, rawOffset, rawStart,
            transProbs.joinToString(",") { "%.2f".format(it) },
            mapInfo
        )

        return Prediction(
            compatibility = rawCompat.coerceIn(0f, 1f),
            // Модель сама управляет длительностью кроссфейда в окне 5..30с:
            // нормализованный выход 0..1 → 5000..30000мс.
            crossfadeDurationMs = (5000f + rawDuration * 25000f).toLong().coerceIn(5000L, 30000L),
            entryOffsetMs = (rawOffset * 10000f).toLong().coerceIn(0L, 10000L),
            transitionType = bestType,
            transitionStartFraction = rawStart.coerceIn(0f, 1f),
            debug = debug
        )
    }

    fun close() {
        interpreter.close()
    }
}
