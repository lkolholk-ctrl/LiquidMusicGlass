package com.liquidmusicglass.automix

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * AutoMix Controller Pro — прокачанный контроллер автомиксов.
 */
class AutoMixController(
    context: Context
) {

    private val appContext = context.applicationContext
    private var predictor: MLTransitionPredictor? = null

    private val energyCache = LinkedHashMap<String, EnergyAnalyzer.TrackEnergy>(16, 0.75f, true)
    private val maxCacheSize = 20

    init {
        predictor = try {
            MLTransitionPredictor(appContext)
        } catch (_: Throwable) {
            null
        }
    }

    suspend fun analyzeTrackPair(
        currentTrackUri: Uri,
        nextTrackUri: Uri,
        currentTrackDurationMs: Long
    ): TrackFeatures = withContext(Dispatchers.Default) {

        val energyA = getOrAnalyze(currentTrackUri, currentTrackDurationMs)
        val nextDurationMs = estimateDuration(nextTrackUri) ?: currentTrackDurationMs
        val energyB = getOrAnalyze(nextTrackUri, nextDurationMs)

        val mlPrediction = try {
            predictor?.predictPair(appContext, currentTrackUri, nextTrackUri, currentTrackDurationMs)
        } catch (_: Throwable) {
            null
        }

        val plan = SmartTransitionFinder.findTransition(
            energyA = energyA,
            energyB = energyB,
            durationA = currentTrackDurationMs,
            durationB = nextDurationMs
        )

        if (mlPrediction != null && mlPrediction.energyScore > COMPATIBILITY_THRESHOLD) {
            val finalCompat = (mlPrediction.energyScore * 0.6f + plan.compatibility * 0.4f)
            
            return@withContext TrackFeatures(
                trackUri = nextTrackUri,
                compatibility = finalCompat,
                crossfadeDurationMs = plan.crossfadeDurationMs,
                entryOffsetMs = plan.entryOffsetMs,
                transitionType = plan.transitionType,
                transitionStartMs = plan.transitionStartMs,
                bpmA = plan.bpmA,
                bpmB = plan.bpmB,
                keyA = plan.keyA,
                keyB = plan.keyB,
                bpmDrift = mlPrediction.bpmDrift,
                lowPassCurve = mlPrediction.lowPassCurve,
                debugInfo = "ML v3 applied (Energy score: ${mlPrediction.energyScore})",
                readyForTransition = true
            )
        }

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
     * ИСПРАВЛЕНО: Теперь ИИ-фичи (bpmDrift, lowPassCurve) честно передаются дальше в Transition!
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
            bpmDrift = features.bpmDrift,         // Фикс: сохраняем изменение темпа
            lowPassCurve = features.lowPassCurve, // Фикс: сохраняем срез частот
            debugInfo = features.debugInfo
        )
    }

    fun shouldStartTransition(
        remainingMs: Long,
        features: TrackFeatures?
    ): Transition = shouldStartTransition(0L, remainingMs, features)

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

        if (durationMs > LONG_TRACK_MS) {
            val seekUs = (durationMs * 1000L) / 5
            try {
                extractor.seekTo(seekUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            } catch (_: Exception) {}
        }

        val codec = try {
            MediaCodec.createDecoderByType(mime)
        } catch (_: Exception) {
            extractor.release()
            return fallback
        }

        val maxMonoSamples = sourceRate * ANALYSIS_SECONDS
        val mono = FloatArray(maxMonoSamples)
        var monoCount = 0

        var channels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
            format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        } else {
            2
        }
        var pcmIsFloat = false

        try {
            codec.configure(format, null, null, 0)
            codec.configure(format, null, null, 0)
            codec.start()

            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var idleIterations = 0

            while (!outputDone && monoCount < maxMonoSamples) {
                if (idleIterations > MAX_IDLE_ITERATIONS) break

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
                }
            }
        } catch (_: Exception) {
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
        private const val COMPATIBILITY_THRESHOLD = 0.45f
        private const val MIN_COMPATIBILITY = 0.25f
        private const val DEFAULT_COMPATIBILITY = 0.75f
        private const val DEFAULT_CROSSFADE_MS = 8000L
        private const val ANALYSIS_SECONDS = 30
        private const val LONG_TRACK_MS = 60_000L
        private const val TIMEOUT_US = 10_000L
        private const val MAX_IDLE_ITERATIONS = 200
        private const val KEY_PCM_ENCODING = "pcm-encoding"
    }
}
