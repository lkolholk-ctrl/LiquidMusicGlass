package com.liquidmusicglass.automix

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Извлечение фичей для AutoMix v2.
 *
 * Модель ожидает:
 *   mel_a:  [1, 431, 128]  — мел-спектрограмма конца трека A
 *   mel_b:  [1, 431, 128]  — мел-спектрограмма начала трека B
 *   aux:    [1, 32]         — вспомогательные фичи (BPM, chroma, energy и т.д.)
 */
object FeatureExtractor {

    private const val N_CHROMA = 12
    private const val AUX_PER_TRACK = 2   // BPM(1) + energy(1)

    /**
     * Результат извлечения фичей для пары треков.
     */
    data class PairFeatures(
        val melA: Array<Array<FloatArray>>,    // [1, 1200, 128, 1]
        val melB: Array<Array<FloatArray>>,    // [1, 1200, 128, 1]
        val aux: Array<FloatArray>             // [1, 2]
    )

    /**
     * Извлечь фичи для пары треков: конец A + начало B.
     */
    fun extractPairFromUri(
        context: Context,
        trackAUri: Uri,
        trackBUri: Uri,
        trackADurationMs: Long
    ): PairFeatures {
        // Конец трека A
        val samplesA = decodePcmSegment(context, trackAUri, fromEnd = true, trackDurationMs = trackADurationMs)
        val melA = MelSpectrogram.generate(samplesA)
        val normalizedA = normalizeFrames(melA)

        // Начало трека B
        val samplesB = decodePcmSegment(context, trackBUri, fromEnd = false, trackDurationMs = 0L)
        val melB = MelSpectrogram.generate(samplesB)
        val normalizedB = normalizeFrames(melB)
        val auxB = computeAuxFeatures(samplesB)

        return PairFeatures(
            melA = Array(1) { normalizedA },
            melB = Array(1) { normalizedB },
            aux = Array(1) { auxB }
        )
    }

    /**
     * Упрощённый вариант: извлечь фичи только для начала трека B
     * (для обратной совместимости, когда трек A ещё не закончился
     * и мы анализируем заранее).
     */
    fun extractSingleFromUri(
        context: Context,
        uri: Uri
    ): Array<Array<FloatArray>> {
        val samples = decodePcmSegment(context, uri, fromEnd = false, trackDurationMs = 0L)
        val melFrames = MelSpectrogram.generate(samples)
        val normalized = normalizeFrames(melFrames)
        return Array(1) { normalized }
    }

    // ──────────────────────────────────────
    // Auxiliary features
    // ──────────────────────────────────────

    /**
     * Вычислить вспомогательные фичи для сегмента:
     * [BPM(1), chroma(12), rms_energy(1), spectral_centroid(1), onset_strength(1)]
     */
    private fun computeAuxFeatures(samples: FloatArray): FloatArray {
        val features = FloatArray(AUX_PER_TRACK)
        var idx = 0

        // 1. BPM (нормализованный: /200)
        val bpm = estimateBpm(samples)
        features[idx++] = bpm / 200f

        // 2. RMS энергия
        features[idx++] = computeRms(samples)

        return features
    }

    /**
     * Оценка BPM через автокорреляцию.
     */
    private fun estimateBpm(samples: FloatArray): Float {
        val sr = MelSpectrogram.SAMPLE_RATE
        val hopLength = MelSpectrogram.HOP_LENGTH

        // Вычисляем onset envelope (спектральный поток)
        val envelope = computeOnsetEnvelope(samples)
        if (envelope.size < 4) return 120f

        // Автокорреляция
        val minLag = (60.0 / 200.0 * sr / hopLength).toInt()  // 200 BPM max
        val maxLag = (60.0 / 60.0 * sr / hopLength).toInt()    // 60 BPM min

        val safeLag = maxLag.coerceAtMost(envelope.size - 1)

        var bestLag = minLag
        var bestCorr = -1f

        for (lag in minLag..safeLag) {
            var corr = 0f
            var count = 0
            for (i in 0 until envelope.size - lag) {
                corr += envelope[i] * envelope[i + lag]
                count++
            }
            if (count > 0) {
                corr /= count
                if (corr > bestCorr) {
                    bestCorr = corr
                    bestLag = lag
                }
            }
        }

        val bpm = 60f * sr / hopLength / bestLag
        return bpm.coerceIn(60f, 200f)
    }

    /**
     * Chroma features: 12-мерный вектор (C, C#, D, ... B).
     */
    private fun computeChroma(samples: FloatArray): FloatArray {
        val nFft = MelSpectrogram.N_FFT
        val hopLength = MelSpectrogram.HOP_LENGTH
        val sr = MelSpectrogram.SAMPLE_RATE

        val chroma = FloatArray(N_CHROMA)
        var frameCount = 0

        var start = 0
        while (start + nFft <= samples.size) {
            val frame = FloatArray(nFft)
            for (i in 0 until nFft) {
                frame[i] = samples[start + i]
            }

            val spectrum = FFT.magnitudeSpectrum(frame, nFft)

            // Маппинг FFT bin → chroma bin
            for (k in 1 until spectrum.size) {
                val freq = k.toFloat() * sr / nFft
                if (freq < 20f || freq > 5000f) continue

                val midiNote = 12f * (ln(freq / 440f) / ln(2f)) + 69f
                val chromaBin = ((midiNote % 12 + 12) % 12).toInt()
                chroma[chromaBin] += spectrum[k] * spectrum[k]
            }

            frameCount++
            start += hopLength
        }

        // Нормализация
        if (frameCount > 0) {
            val maxVal = chroma.max().coerceAtLeast(1e-10f)
            for (i in chroma.indices) {
                chroma[i] = chroma[i] / (frameCount * maxVal)
            }
        }

        return chroma
    }

    /**
     * RMS энергия.
     */
    private fun computeRms(samples: FloatArray): Float {
        if (samples.isEmpty()) return 0f
        var sumSq = 0.0
        for (s in samples) {
            sumSq += s * s
        }
        return sqrt(sumSq / samples.size).toFloat()
    }

    /**
     * Spectral centroid (нормализованный 0..1).
     */
    private fun computeSpectralCentroid(samples: FloatArray): Float {
        val nFft = MelSpectrogram.N_FFT
        val sr = MelSpectrogram.SAMPLE_RATE

        // Берём один фрейм из середины
        val midStart = ((samples.size - nFft) / 2).coerceAtLeast(0)
        val frame = FloatArray(nFft)
        for (i in 0 until nFft) {
            val idx = midStart + i
            if (idx < samples.size) frame[i] = samples[idx]
        }

        val spectrum = FFT.magnitudeSpectrum(frame, nFft)

        var weightedSum = 0.0
        var sum = 0.0
        for (k in spectrum.indices) {
            val freq = k.toDouble() * sr / nFft
            weightedSum += freq * spectrum[k]
            sum += spectrum[k]
        }

        val centroid = if (sum > 0) weightedSum / sum else 0.0
        return (centroid / (sr / 2.0)).toFloat().coerceIn(0f, 1f)
    }

    /**
     * Onset strength (средняя спектральная разница между фреймами).
     */
    private fun computeOnsetStrength(samples: FloatArray): Float {
        val envelope = computeOnsetEnvelope(samples)
        if (envelope.isEmpty()) return 0f
        return (envelope.average() / 10.0).toFloat().coerceIn(0f, 1f)
    }

    /**
     * Onset envelope через спектральный поток.
     */
    private fun computeOnsetEnvelope(samples: FloatArray): FloatArray {
        val nFft = MelSpectrogram.N_FFT
        val hopLength = MelSpectrogram.HOP_LENGTH
        val bins = nFft / 2 + 1

        val frames = mutableListOf<FloatArray>()

        var start = 0
        while (start + nFft <= samples.size) {
            val frame = FloatArray(nFft)
            for (i in 0 until nFft) {
                frame[i] = samples[start + i]
            }
            val spectrum = FFT.magnitudeSpectrum(frame, nFft)
            frames.add(spectrum)
            start += hopLength
        }

        if (frames.size < 2) return floatArrayOf()

        val envelope = FloatArray(frames.size - 1)
        for (i in 1 until frames.size) {
            var flux = 0f
            for (k in 0 until bins) {
                val diff = frames[i][k] - frames[i - 1][k]
                if (diff > 0) flux += diff
            }
            envelope[i - 1] = flux
        }

        return envelope
    }

    // ──────────────────────────────────────
    // Audio decoding
    // ──────────────────────────────────────

    private fun normalizeFrames(frames: Array<FloatArray>): Array<FloatArray> {
        val flat = frames.flatMap { it.asIterable() }
        val mean = flat.average().toFloat()
        val variance = flat.map { (it - mean) * (it - mean) }.average().toFloat()
        val std = sqrt(variance + 1e-6f)

        return Array(frames.size) { t ->
            FloatArray(frames[t].size) { m ->
                (frames[t][m] - mean) / std
            }
        }
    }

    /**
     * Декодируем PCM моно из URI.
     * @param fromEnd если true, берём последние SEGMENT_DURATION_SEC секунд.
     * @param trackDurationMs длительность трека (нужна для fromEnd).
     */
    private fun decodePcmSegment(
        context: Context,
        uri: Uri,
        fromEnd: Boolean,
        trackDurationMs: Long
    ): FloatArray {
        val targetRate = MelSpectrogram.SAMPLE_RATE
        val targetSamples = MelSpectrogram.SEGMENT_DURATION_SEC * targetRate
        val fallback = FloatArray(targetSamples)

        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
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

        // Если fromEnd — seekTo к последним SEGMENT_DURATION_SEC секундам
        if (fromEnd && trackDurationMs > 0L) {
            val seekToMs = (trackDurationMs - MelSpectrogram.SEGMENT_DURATION_SEC * 1000L).coerceAtLeast(0L)
            try {
                extractor.seekTo(seekToMs * 1000L, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            } catch (_: Exception) {}
        }

        val codec = try {
            MediaCodec.createDecoderByType(mime)
        } catch (_: Exception) {
            extractor.release()
            return fallback
        }

        // Буфер под моно-сэмплы в исходном sample rate.
        val maxMonoSamples = sourceRate * MelSpectrogram.SEGMENT_DURATION_SEC
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
            codec.start()

            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var idleIterations = 0
            val TIMEOUT_US = 10000L
            val MAX_IDLE_ITERATIONS = 200
            val KEY_PCM_ENCODING = "pcm-encoding"

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
        val resampled = if (sourceRate == targetRate) {
            decoded
        } else {
            resampleLinear(decoded, sourceRate, targetRate)
        }

        return fitToExactSamples(resampled, targetSamples)
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
        src.order(java.nio.ByteOrder.LITTLE_ENDIAN)

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

    private fun resampleLinear(
        input: FloatArray,
        inputRate: Int,
        outputRate: Int
    ): FloatArray {
        if (inputRate == outputRate) return input

        val ratio = outputRate.toFloat() / inputRate.toFloat()
        val outLength = (input.size * ratio).toInt().coerceAtLeast(1)
        val output = FloatArray(outLength)

        for (i in output.indices) {
            val position = i / ratio
            val left = position.toInt().coerceIn(0, input.lastIndex)
            val right = (left + 1).coerceIn(0, input.lastIndex)
            val frac = position - left

            output[i] = input[left] * (1f - frac) + input[right] * frac
        }

        return output
    }

    private fun fitToExactSamples(
        input: FloatArray,
        targetSize: Int
    ): FloatArray {
        return when {
            input.size == targetSize -> input
            input.size > targetSize -> input.copyOf(targetSize)
            else -> FloatArray(targetSize).also { out ->
                input.copyInto(out, endIndex = input.size)
            }
        }
    }
}
