package com.liquidmusicglass.automix

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.liquidmusicglass.debug.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * AutoMix Controller Pro — прокачанный контроллер автомиксов.
 *
 * Цепочка анализа:
 * 1. Декодирует РЕАЛЬНЫЙ PCM из обоих треков (через MediaCodec)
 * 2. EnergyAnalyzer: энергетическая карта + BPM + Key
 * 3. SmartTransitionFinder: оптимальная точка, тип, длительность
 * 4. Если есть TFLite модель — комбинирует ML и алгоритмический анализ
 *
 * DJ эффекты (6 типов):
 * 0=Smooth Fade, 1=Energy Fade, 2=Beat Match,
 * 3=Hard Cut, 4=Filter Sweep, 5=Echo Out
 *
 * ВАЖНО про декодирование:
 * Раньше тут стоял MediaExtractor.readSampleData() — он отдаёт СЖАТЫЕ
 * (encoded) MP3/AAC данные, а не PCM. Анализ работал на мусоре.
 * Теперь честный MediaCodec-декодер: extractor -> decoder -> PCM float.
 */
class AutoMixController(
    context: Context
) {

    private val appContext = context.applicationContext
    private var predictor: MLTransitionPredictor? = null

    // Кэш анализа: uri → TrackEnergy
    private val energyCache = LinkedHashMap<String, EnergyAnalyzer.TrackEnergy>(16, 0.75f, true)
    private val maxCacheSize = 20

    init {
        predictor = try {
            MLTransitionPredictor(appContext).also {
                DebugLog.add("AutoMix.model LOADED ok")
            }
        } catch (t: Throwable) {
            // Главная причина ухода в алгоритмический путь — логируем явно,
            // чтобы видеть, ПОЧЕМУ модель не раскрылась (несовместимый op,
            // отсутствие нативной либы, битый файл и т.п.).
            DebugLog.add("AutoMix.model LOAD FAILED ${t.javaClass.simpleName}: ${t.message}")
            null
        }
    }

    /**
     * Полный анализ пары треков — Pro версия.
     */
    suspend fun analyzeTrackPair(
        currentTrackUri: Uri,
        nextTrackUri: Uri,
        currentTrackDurationMs: Long
    ): TrackFeatures = withContext(Dispatchers.Default) {

        // 1. Декодируем и анализируем оба трека
        val energyA = getOrAnalyze(currentTrackUri, currentTrackDurationMs)
        val nextDurationMs = estimateDuration(nextTrackUri) ?: currentTrackDurationMs
        val energyB = getOrAnalyze(nextTrackUri, nextDurationMs)

        // 2. ML предсказание (если модель доступна)
        val mlPrediction = if (predictor == null) {
            DebugLog.add("AutoMix.predict SKIP (model not loaded)")
            null
        } else try {
            predictor?.predictPair(appContext, currentTrackUri, nextTrackUri, currentTrackDurationMs)
        } catch (t: Throwable) {
            DebugLog.add("AutoMix.predict FAILED ${t.javaClass.simpleName}: ${t.message}")
            null
        }

        // 3. Алгоритмический анализ через SmartTransitionFinder
        val plan = SmartTransitionFinder.findTransition(
            energyA = energyA,
            energyB = energyB,
            durationA = currentTrackDurationMs,
            durationB = nextDurationMs
        )

        // 4. Модель РАСКРЫВАЕТСЯ ПОЛНОСТЬЮ. Если TFLite загрузилась — ВСЕ
        //    параметры перехода берём из её выхода: compatibility,
        //    crossfade_duration, entry_offset, transition_type и transition_start.
        //    Алгоритмический SmartTransitionFinder больше НЕ перетирает их — он
        //    остаётся только запасным путём (модель не загрузилась) и источником
        //    детектированных BPM/Key для отображения (это вход aux, не выход сети).
        if (mlPrediction != null) {
            // transition_start модель отдаёт как долю 0..1 от длительности трека A.
            // Единственная защита — чтобы кроссфейд успел завершиться до конца
            // трека (не выходим за durationA - crossfade). Сам выбор точки — модели.
            val latestStart = (currentTrackDurationMs - mlPrediction.crossfadeDurationMs)
                .coerceAtLeast(0L)
            val modelStartMs = (mlPrediction.transitionStartFraction * currentTrackDurationMs)
                .toLong()
                .coerceIn(0L, latestStart)
            return@withContext TrackFeatures(
                trackUri = nextTrackUri,
                compatibility = mlPrediction.compatibility,
                crossfadeDurationMs = mlPrediction.crossfadeDurationMs,
                entryOffsetMs = mlPrediction.entryOffsetMs,
                transitionType = mlPrediction.transitionType,
                transitionStartMs = modelStartMs,
                bpmA = plan.bpmA,
                bpmB = plan.bpmB,
                keyA = plan.keyA,
                keyB = plan.keyB,
                debugInfo = "ML: compat=%.2f xfade=%dms start=%dms(frac=%.2f) entry=%dms type=%d | %s"
                    .format(
                        mlPrediction.compatibility,
                        mlPrediction.crossfadeDurationMs,
                        modelStartMs,
                        mlPrediction.transitionStartFraction,
                        mlPrediction.entryOffsetMs,
                        mlPrediction.transitionType,
                        mlPrediction.debug
                    ),
                readyForTransition = mlPrediction.compatibility > MIN_COMPATIBILITY
            )
        }

        // 5. Запасной путь — модель недоступна: чистый алгоритмический анализ.
        return@withContext TrackFeatures(
            trackUri = nextTrackUri,
            compatibility = plan.compatibility,
            crossfadeDurationMs = plan.crossfadeDurationMs,
            entryOffsetMs = plan.entryOffsetMs,
            transitionType = plan.transitionType,
            transitionStartMs = plan.transitionStartMs,
            bpmA = plan.bpmA,
            bpmB = plan.bpmB,
            keyA = plan.keyA,
            keyB = plan.keyB,
            debugInfo = "Algo: ${plan.debugInfo}",
            readyForTransition = plan.compatibility > MIN_COMPATIBILITY
        )
    }

    /**
     * Упрощённый анализ одного трека.
     */
    suspend fun analyzeTrack(
        trackUri: Uri
    ): TrackFeatures = withContext(Dispatchers.Default) {
        TrackFeatures(
            trackUri = trackUri,
            compatibility = DEFAULT_COMPATIBILITY,
            crossfadeDurationMs = DEFAULT_CROSSFADE_MS,
            entryOffsetMs = 0L,
            transitionType = 0,
            transitionStartMs = 0L,
            bpmA = null,
            bpmB = null,
            keyA = null,
            keyB = null,
            debugInfo = "Single track fallback",
            readyForTransition = true
        )
    }

    /**
     * Определяет нужно ли начинать переход прямо сейчас.
     */
    fun shouldStartTransition(
        currentPositionMs: Long,
        remainingMs: Long,
        features: TrackFeatures?
    ): Transition {
        if (features == null || !features.readyForTransition) {
            return Transition.NONE
        }

        val shouldStart = if (features.transitionStartMs > 0) {
            currentPositionMs >= features.transitionStartMs
        } else {
            remainingMs in 1..features.crossfadeDurationMs
        }

        if (!shouldStart) return Transition.NONE

        return Transition(
            shouldStart = true,
            compatibility = features.compatibility,
            crossfadeDurationMs = features.crossfadeDurationMs,
            entryOffsetMs = features.entryOffsetMs,
            transitionType = features.transitionType,
            transitionStartMs = features.transitionStartMs,
            debugInfo = features.debugInfo
        )
    }

    // Обратная совместимость: старый метод
    fun shouldStartTransition(
        remainingMs: Long,
        features: TrackFeatures?
    ): Transition = shouldStartTransition(0L, remainingMs, features)

    /**
     * Кэшированный анализ трека.
     */
    private suspend fun getOrAnalyze(uri: Uri, durationMs: Long): EnergyAnalyzer.TrackEnergy {
        val key = uri.toString()
        energyCache[key]?.let { return it }

        val samples = decodePcmForAnalysis(uri, durationMs)
        val energy = EnergyAnalyzer.analyze(
            samples = samples,
            sampleRate = MelSpectrogram.SAMPLE_RATE,
            durationMs = durationMs
        )

        if (energyCache.size >= maxCacheSize) {
            energyCache.remove(energyCache.keys.first())
        }
        energyCache[key] = energy

        return energy
    }

    // ----------------------------------------------------------------
    //  PCM ДЕКОДИРОВАНИЕ (MediaCodec)
    // ----------------------------------------------------------------

    /**
     * Декодирует РЕАЛЬНЫЙ PCM из трека для анализа.
     *
     * - Использует MediaCodec (честный декодер), а не сырые сжатые байты.
     * - Берёт до [ANALYSIS_SECONDS] секунд. Если трек длинный — стартует
     *   не с самого начала (интро часто тихое/нерепрезентативное), а с ~20%.
     * - Стерео сводится в моно.
     * - Результат — моно FloatArray в диапазоне [-1f, 1f] на частоте
     *   MelSpectrogram.SAMPLE_RATE (с ресэмплингом при необходимости).
     *
     * При любой ошибке возвращает короткий пустой буфер — анализ не падает,
     * просто получит «тишину» (вызывающий код это переживает).
     */
    private fun decodePcmForAnalysis(uri: Uri, durationMs: Long): FloatArray {
        val targetRate = MelSpectrogram.SAMPLE_RATE
        val fallback = FloatArray(targetRate * 10)

        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(appContext, uri, null)
        } catch (_: Exception) {
            extractor.release()
            return fallback
        }

        // Находим первый аудио-трек
        var trackIndex = -1
        var inputFormat: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val fmt = extractor.getTrackFormat(i)
            val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) {
                trackIndex = i
                inputFormat = fmt
                break
            }
        }

        val format = inputFormat
        if (trackIndex == -1 || format == null) {
            extractor.release()
            return fallback
        }

        extractor.selectTrack(trackIndex)

        val mime = format.getString(MediaFormat.KEY_MIME)
        if (mime == null) {
            extractor.release()
            return fallback
        }

        val sourceRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
            format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        } else {
            targetRate
        }

        // Длинные треки — пропускаем интро, стартуем с ~20% длительности.
        if (durationMs > LONG_TRACK_MS) {
            val seekUs = (durationMs * 1000L) / 5  // 20%
            try {
                extractor.seekTo(seekUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            } catch (_: Exception) {
                // не критично — продолжаем с начала
            }
        }

        val codec = try {
            MediaCodec.createDecoderByType(mime)
        } catch (_: Exception) {
            extractor.release()
            return fallback
        }

        // Буфер под моно-сэмплы в исходном sample rate.
        val maxMonoSamples = sourceRate * ANALYSIS_SECONDS
        val mono = FloatArray(maxMonoSamples)
        var monoCount = 0

        // Каналы: точное значение узнаём из output-формата декодера.
        var channels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
            format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        } else {
            2
        }
        var pcmIsFloat = false

        try {
            codec.configure(format, null, null, 0)
            codec.start()

            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var idleIterations = 0

            while (!outputDone && monoCount < maxMonoSamples) {

                // Защита от вечного цикла на битом файле
                if (idleIterations > MAX_IDLE_ITERATIONS) break

                // --- подаём вход ---
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        val inBuf = codec.getInputBuffer(inIndex)
                        if (inBuf != null) {
                            val sampleSize = extractor.readSampleData(inBuf, 0)
                            if (sampleSize < 0) {
                                codec.queueInputBuffer(
                                    inIndex, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(
                                    inIndex, 0, sampleSize,
                                    extractor.sampleTime, 0
                                )
                                extractor.advance()
                            }
                        }
                    }
                }

                // --- забираем выход ---
                val outIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                when {
                    outIndex >= 0 -> {
                        idleIterations = 0

                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputDone = true
                        }

                        if (bufferInfo.size > 0) {
                            val outBuf = codec.getOutputBuffer(outIndex)
                            if (outBuf != null) {
                                outBuf.position(bufferInfo.offset)
                                outBuf.limit(bufferInfo.offset + bufferInfo.size)
                                monoCount = appendPcm(
                                    src = outBuf,
                                    dst = mono,
                                    dstIndex = monoCount,
                                    channels = channels,
                                    isFloat = pcmIsFloat
                                )
                            }
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                    }

                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        // Реальный формат выхода декодера — источник истины.
                        val outFormat = codec.outputFormat
                        if (outFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                            channels = outFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        }
                        if (outFormat.containsKey(KEY_PCM_ENCODING)) {
                            pcmIsFloat = outFormat.getInteger(KEY_PCM_ENCODING) ==
                                AudioFormat.ENCODING_PCM_FLOAT
                        }
                    }

                    outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        idleIterations++
                    }

                    else -> {
                        // INFO_OUTPUT_BUFFERS_CHANGED и прочее — игнорируем
                    }
                }
            }
        } catch (_: Exception) {
            // вернём то, что успели декодировать
        } finally {
            try { codec.stop() } catch (_: Exception) {}
            try { codec.release() } catch (_: Exception) {}
            extractor.release()
        }

        if (monoCount == 0) return fallback

        val decoded = mono.copyOf(monoCount)
        return if (sourceRate == targetRate) {
            decoded
        } else {
            resample(decoded, sourceRate, targetRate)
        }
    }

    /**
     * Конвертирует PCM из буфера декодера в моно float [-1f, 1f]
     * и дописывает в [dst] начиная с [dstIndex]. Возвращает новый индекс.
     *
     * Поддерживает 16-bit signed PCM (основной случай) и float PCM.
     * Interleaved-стерео сводится в моно усреднением каналов.
     */
    private fun appendPcm(
        src: ByteBuffer,
        dst: FloatArray,
        dstIndex: Int,
        channels: Int,
        isFloat: Boolean
    ): Int {
        var idx = dstIndex
        val ch = channels.coerceAtLeast(1)
        src.order(ByteOrder.LITTLE_ENDIAN)

        if (isFloat) {
            val fb = src.asFloatBuffer()
            val frames = fb.remaining() / ch
            var f = 0
            while (f < frames && idx < dst.size) {
                var sum = 0f
                for (c in 0 until ch) sum += fb.get()
                dst[idx++] = (sum / ch).coerceIn(-1f, 1f)
                f++
            }
        } else {
            val sb = src.asShortBuffer()
            val frames = sb.remaining() / ch
            var f = 0
            while (f < frames && idx < dst.size) {
                var sum = 0f
                for (c in 0 until ch) sum += sb.get() / 32768f
                dst[idx++] = sum / ch
                f++
            }
        }
        return idx
    }

    private fun estimateDuration(uri: Uri): Long? {
        return try {
            val extractor = MediaExtractor()
            extractor.setDataSource(appContext, uri, null)
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/") && format.containsKey(MediaFormat.KEY_DURATION)) {
                    val durationUs = format.getLong(MediaFormat.KEY_DURATION)
                    extractor.release()
                    return durationUs / 1000
                }
            }
            extractor.release()
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun resample(input: FloatArray, fromRate: Int, toRate: Int): FloatArray {
        if (fromRate == toRate || input.isEmpty()) return input
        val ratio = toRate.toFloat() / fromRate.toFloat()
        val outLength = (input.size * ratio).toInt().coerceAtLeast(1)
        val output = FloatArray(outLength)
        for (i in output.indices) {
            val pos = i / ratio
            val left = pos.toInt().coerceIn(0, input.lastIndex)
            val right = (left + 1).coerceIn(0, input.lastIndex)
            val frac = pos - left
            output[i] = input[left] * (1f - frac) + input[right] * frac
        }
        return output
    }

    fun release() {
        try { predictor?.close() } catch (_: Throwable) {}
        predictor = null
        energyCache.clear()
    }

    companion object {
        private const val MIN_COMPATIBILITY = 0.25f
        private const val DEFAULT_COMPATIBILITY = 0.75f
        private const val DEFAULT_CROSSFADE_MS = 8000L

        // Сколько секунд аудио декодировать для анализа
        private const val ANALYSIS_SECONDS = 30

        // Трек считается "длинным" (есть смысл пропускать интро)
        private const val LONG_TRACK_MS = 60_000L

        // MediaCodec
        private const val TIMEOUT_US = 10_000L
        private const val MAX_IDLE_ITERATIONS = 200

        // MediaFormat.KEY_PCM_ENCODING доступен с API 24, но строковый
        // ключ безопасно использовать напрямую.
        private const val KEY_PCM_ENCODING = "pcm-encoding"
    }
}
